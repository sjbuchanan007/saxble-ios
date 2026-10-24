package com.shj.saxble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * BLE central toward the SAX-D encoder — the Kotlin sibling of the iOS
 * BLEManager. Scan + connect + login (with manual-password fallback) + command
 * send (paced/confirmed/verified) + preset runner + auto commissioning report
 * on a deliberate disconnect.
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    enum class Phase { IDLE, SCANNING, CONNECTING, CONNECTED }

    data class FoundDevice(
        val name: String, val address: String, val rssi: Int,
        val device: BluetoothDevice, val likely: Boolean
    )

    /** One transcript line. `rx` marks replies from the encoder (used by the report). */
    data class LogLine(val text: String, val rx: Boolean, val time: Long = System.currentTimeMillis())

    // ---- Observable state ----
    val phase = MutableStateFlow(Phase.IDLE)
    val devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val log = MutableStateFlow<List<LogLine>>(emptyList())
    val loggedIn = MutableStateFlow(false)
    val status = MutableStateFlow("")                 // human feedback on the landing screen
    val needsPassword = MutableStateFlow(false)       // auto-login exhausted → prompt
    val peerName = MutableStateFlow("")
    val passwordWarning = MutableStateFlow<String?>(null)

    val showAll = MutableStateFlow(false)
    val showingAllFallback = MutableStateFlow(false)
    var diagnostics = true

    val presetRunning = MutableStateFlow(false)
    val presetStatus = MutableStateFlow("")
    val needLocation = MutableStateFlow(false)

    // ---- Internals ----
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner get() = adapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val found = LinkedHashMap<String, FoundDevice>()
    private val rxBuf = StringBuilder()

    private var candidates = Encoder.passwordCandidates
    private var loginIndex = 0
    private var lastAttempted: String? = null

    private var awaitingConfirm = false
    private var pendingRetype: String? = null
    private var expectedPassword: String? = null
    private var verifyingPassword = false

    private val pending = ArrayDeque<Pair<ByteArray, Long>>()
    private var writing = false
    private var lastDelay = 0L

    private var locationCont: CancellableContinuation<String>? = null

    // ---- Public control ----

    fun startScan() {
        found.clear()
        devices.value = emptyList()
        showingAllFallback.value = false
        phase.value = Phase.SCANNING
        status.value = "Scanning…"
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(null, settings, scanCallback)
    }

    fun stopScan() { scanner?.stopScan(scanCallback) }

    fun setShowAll(v: Boolean) { showAll.value = v; publishDevices() }

    fun connect(device: BluetoothDevice) {
        stopScan()
        phase.value = Phase.CONNECTING
        loginIndex = 0
        loggedIn.value = false
        needsPassword.value = false
        peerName.value = device.name ?: device.address
        status.value = "Connecting to ${peerName.value}…"
        info("connecting to ${peerName.value}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Plain disconnect (no report) — used on errors / drop-outs. */
    fun disconnect() { gatt?.disconnect() }

    /**
     * Deliberate disconnect: if logged in, run `gas a list` + `settings` to grab
     * the latest state, build the PDF, pop the share sheet, then drop the link.
     */
    fun disconnectAndReport() {
        if (writeChar == null || !loggedIn.value) { disconnect(); return }
        status.value = "Capturing latest settings for report…"
        send("gas a list")
        scope.launch {
            delay(2800)
            send("settings")
            delay(2800)
            val file = Report.buildPdf(context, reportRxLines(), transcript(),
                peerName.value.ifEmpty { "encoder" })
            if (file != null) Report.share(context, file)
            status.value = ""
            disconnect()
        }
    }

    /** The user typed a password after auto-login failed. */
    fun submitPassword(pw: String) {
        needsPassword.value = false
        lastAttempted = pw
        status.value = "Logging in…"
        sendSlow(pw)
    }

    /** Mark that the next "Y or N" prompt should be auto-answered "Y". */
    fun expectConfirm() { awaitingConfirm = true }

    fun clearPasswordWarning() { passwordWarning.value = null }

    // ---- Presets ----

    fun runPreset(preset: Preset) {
        if (presetRunning.value || !loggedIn.value) return
        presetRunning.value = true
        scope.launch {
            for (step in preset.steps) {
                when (step) {
                    is PresetStep.Command -> {
                        presetStatus.value = step.line
                        send(step.line)
                        delay(1500)
                    }
                    is PresetStep.PromptLocation -> {
                        presetStatus.value = "Waiting for location…"
                        val loc = awaitLocation()
                        val token = loc.trim().replace(" ", "\\s")
                        if (token.isNotEmpty()) { send("location $token"); delay(1500) }
                    }
                }
            }
            presetStatus.value = "Done"
            presetRunning.value = false
        }
    }

    fun submitLocation(s: String) {
        needLocation.value = false
        locationCont?.resume(s)
        locationCont = null
    }

    private suspend fun awaitLocation(): String = suspendCancellableCoroutine { cont ->
        locationCont = cont
        needLocation.value = true
    }

    // ---- Sending ----

    fun sendSlow(line: String) = send(line, paced = true)

    /** Send a command line (CR+LF appended). `password <new>` is always paced. */
    fun send(line: String, paced: Boolean = false) {
        val pace = paced || line.lowercase().startsWith("password ")
        rememberRetype(line)
        val full = (line + Encoder.LINE_ENDING).toByteArray(Charsets.US_ASCII)
        val gap = if (pace) Encoder.SLOW_BYTE_GAP_MS else 0L
        synchronized(pending) {
            if (pace) full.forEach { pending.addLast(byteArrayOf(it) to gap) }
            else pending.addLast(full to 0L)
        }
        tx(line)
        drainWrites()
    }

    private fun rememberRetype(line: String) {
        val parts = line.split(" ", limit = 2)
        if (parts.size == 2 && parts[0].equals("password", ignoreCase = true)) {
            pendingRetype = parts[1]
            expectedPassword = parts[1]
        }
    }

    private fun drainWrites() {
        if (writing) return
        val next = synchronized(pending) { if (pending.isEmpty()) null else pending.removeFirst() } ?: return
        writing = true
        lastDelay = next.second
        writeValue(next.first)
    }

    private fun writeValue(bytes: ByteArray) {
        val ch = writeChar ?: return
        val type =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(ch, bytes, type)
        } else {
            @Suppress("DEPRECATION") ch.writeType = type
            @Suppress("DEPRECATION") ch.value = bytes
            @Suppress("DEPRECATION") gatt?.writeCharacteristic(ch)
        }
    }

    // ---- Scanning ----

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val rec = result.scanRecord
            val localName = rec?.deviceName
            val name = localName ?: dev.name ?: "(unknown)"
            val noMfg = (rec?.manufacturerSpecificData?.size() ?: 0) == 0
            val noSvc = rec?.serviceUuids.isNullOrEmpty()
            val likely = name != "(unknown)" && noMfg && noSvc
            val isNew = !found.containsKey(dev.address)
            found[dev.address] = FoundDevice(name, dev.address, result.rssi, dev, likely)
            if (isNew) {
                publishDevices()
                if (diagnostics) info(describeAdv(result, localName, dev.name))
            }
        }
        override fun onScanFailed(errorCode: Int) { info("scan failed: $errorCode") }
    }

    private fun describeAdv(result: ScanResult, localName: String?, gapName: String?): String {
        val rec = result.scanRecord
        val svc = rec?.serviceUuids?.joinToString(",") { it.uuid.toString().substring(0, 8) } ?: "-"
        val mfg = StringBuilder()
        rec?.manufacturerSpecificData?.let { data ->
            for (i in 0 until data.size()) {
                mfg.append("0x%04X:".format(data.keyAt(i)))
                    .append(data.valueAt(i).joinToString("") { "%02X".format(it) }).append(' ')
            }
        }
        return "ADV local='${localName ?: "-"}' gap='${gapName ?: "-"}' " +
            "svc=$svc mfg=${if (mfg.isBlank()) "-" else mfg.trim()} rssi=${result.rssi}"
    }

    private fun publishDevices() {
        val all = found.values.sortedWith(
            compareByDescending<FoundDevice> { it.likely }.thenByDescending { it.rssi }
        )
        val likelyOnly = all.filter { it.likely }
        val fallback = likelyOnly.isEmpty() && all.isNotEmpty()
        val show = if (showAll.value || likelyOnly.isEmpty()) all else likelyOnly
        main.post {
            devices.value = show
            showingAllFallback.value = fallback && !showAll.value
        }
    }

    // ---- GATT ----

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    this@BleManager.status.value = "Discovering services…"
                    info("connected, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    info("disconnected")
                    writeChar = null
                    loggedIn.value = false
                    needsPassword.value = false
                    phase.value = Phase.IDLE
                    this@BleManager.status.value = ""
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(Encoder.SERVICE)
            val ch = svc?.getCharacteristic(Encoder.WRITE_NOTIFY_CHAR)
            if (ch == null) { info("transparent-UART characteristic not found"); return }
            writeChar = ch
            enableNotify(ch)
            phase.value = Phase.CONNECTED
            this@BleManager.status.value = "Logging in…"
            info("ready (notifications enabled)")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val delay = lastDelay
            main.postDelayed({ writing = false; drainWrites() }, delay)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            ingest(value)
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") ingest(ch.value ?: ByteArray(0))
        }
    }

    private fun enableNotify(ch: BluetoothGattCharacteristic) {
        gatt?.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(Encoder.CCCD) ?: return
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION") cccd.value = value
            @Suppress("DEPRECATION") gatt?.writeDescriptor(cccd)
        }
    }

    // ---- Receiving / line assembly ----

    private fun ingest(data: ByteArray) {
        for (b in data) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == '\n') flush()
            else if (c != '\r') rxBuf.append(c)
        }
        val last = rxBuf.lastOrNull()
        if (last != null && ":>?#".contains(last)) flush()
    }

    private fun flush() {
        val s = rxBuf.toString().trim()
        rxBuf.setLength(0)
        if (s.isNotEmpty()) handleLine(s)
    }

    private fun handleLine(line: String) {
        rx(line)
        val lower = line.lowercase()

        if (awaitingConfirm && (lower.contains("y or n") || lower.contains("y/n"))) {
            awaitingConfirm = false
            main.postDelayed({ send("Y") }, 300)
            return
        }
        if (line.contains(Encoder.LOGIN_MARKER)) {
            loggedIn.value = true
            needsPassword.value = false
            status.value = "Logged in"
            return
        }
        if (lower.contains("retype") && pendingRetype != null) {
            val pw = pendingRetype!!
            main.postDelayed({ sendSlow(pw) }, 400)
            return
        }
        if (lower.contains("password updated")) {
            info("password updated successfully")
            pendingRetype = null
            if (expectedPassword != null) {
                verifyingPassword = true
                main.postDelayed({ send("settings") }, 800)
            }
            return
        }
        if (verifyingPassword) {
            val m = Regex("^Password:\\s*\"(.*)\"").find(line.trim())
            if (m != null) {
                verifyingPassword = false
                val stored = m.groupValues[1]
                val want = expectedPassword ?: ""
                expectedPassword = null
                if (stored == want) {
                    info("OK - password verified, encoder stored it correctly")
                } else {
                    val msg = "WARNING - password mismatch: set \"$want\" but encoder stored \"$stored\". Change it again."
                    info(msg); passwordWarning.value = msg
                }
                return
            }
        }

        val isPrompt = lower.contains("password") && line.endsWith(":")
        val isInvalid = lower.contains("invalid password")
        if (!isPrompt && !isInvalid) return

        loggedIn.value = false
        if (loginIndex < candidates.size) {
            val pw = candidates[loginIndex]; loginIndex++
            lastAttempted = pw
            status.value = "Logging in…"
            main.postDelayed({ sendSlow(pw) }, 600)
        } else {
            needsPassword.value = true
            status.value = "Enter the encoder password"
        }
    }

    // ---- Transcript / logging ----

    fun transcript(): String {
        val df = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.UK)
        return log.value.joinToString("\n") { "${df.format(Date(it.time))}  ${it.text}" }
    }

    fun reportRxLines(): List<String> = log.value.filter { it.rx }.map { it.text }

    private fun tx(line: String) = append(LogLine(">> $line", false))
    private fun rx(line: String) = append(LogLine(line, true))
    private fun info(s: String) = append(LogLine(s, false))
    private fun append(l: LogLine) {
        Log.d("SAXBLE", l.text)
        main.post { log.value = (log.value + l).takeLast(1000) }
    }
}
