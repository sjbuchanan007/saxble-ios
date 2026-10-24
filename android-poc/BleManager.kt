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
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Proof-of-concept BLE central for the SAX-D encoder on Android — the Kotlin
 * sibling of the iOS BLEManager. Scope: scan -> connect -> enable notifications
 * -> paced CR+LF login -> detect "Welcome to Shire". No commands/presets/report
 * yet; this just proves the handshake works on Android.
 *
 * Permissions are assumed granted by the UI before use, hence @SuppressLint.
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    enum class Phase { IDLE, SCANNING, CONNECTING, CONNECTED }

    data class FoundDevice(
        val name: String, val address: String, val rssi: Int,
        val device: BluetoothDevice, val likely: Boolean
    )

    // Observable state for Compose.
    val phase = MutableStateFlow(Phase.IDLE)
    val devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val log = MutableStateFlow<List<String>>(emptyList())
    val loggedIn = MutableStateFlow(false)

    /** Show every device, not just likely encoders. */
    val showAll = MutableStateFlow(false)
    /** True when the filter found nothing, so we're showing all as a fallback. */
    val showingAllFallback = MutableStateFlow(false)

    /** When true, the scan logs a full advertisement dump per device (for
     *  reverse-engineering a scan filter). */
    var diagnostics = true

    private val main = Handler(Looper.getMainLooper())
    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner get() = adapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val found = LinkedHashMap<String, FoundDevice>()
    private val rx = StringBuilder()

    // Login state.
    private var candidates = Encoder.passwordCandidates
    private var loginIndex = 0

    // Serial write pipeline: (bytes, delay-after-ms). One chunk is sent, we wait
    // for onCharacteristicWrite, then (after the delay) send the next — so paced
    // password bytes never collide and never outrun the encoder.
    private val pending = ArrayDeque<Pair<ByteArray, Long>>()
    private var writing = false

    // ---- Public control ---------------------------------------------------

    fun startScan() {
        found.clear()
        devices.value = emptyList()
        phase.value = Phase.SCANNING
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No service filter: the encoder doesn't advertise its UUID.
        scanner?.startScan(null, settings, scanCallback)
    }

    fun stopScan() { scanner?.stopScan(scanCallback) }

    fun connect(device: BluetoothDevice) {
        stopScan()
        phase.value = Phase.CONNECTING
        loginIndex = 0
        loggedIn.value = false
        info("connecting to ${device.name ?: device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() { gatt?.disconnect() }

    /** Toggle between "likely encoders only" and all devices. */
    fun setShowAll(v: Boolean) { showAll.value = v; publishDevices() }

    // ---- Scanning ---------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val rec = result.scanRecord
            val localName = rec?.deviceName        // from advertisement / scan response
            val gapName = dev.name                 // GAP device name (may be null until connected)
            val name = localName ?: gapName ?: "(unknown)"
            // The encoder is a bare RN4870 UART module: it advertises a name and
            // NOTHING else (no manufacturer data, no service UUID). Most consumer
            // BLE clutter carries one or both, so "named + neither" is a reliable,
            // rename-proof signature.
            val noMfg = (rec?.manufacturerSpecificData?.size() ?: 0) == 0
            val noSvc = rec?.serviceUuids.isNullOrEmpty()
            val likely = name != "(unknown)" && noMfg && noSvc
            val isNew = !found.containsKey(dev.address)
            found[dev.address] = FoundDevice(name, dev.address, result.rssi, dev, likely)
            if (isNew) {
                publishDevices()
                // Diagnostic: dump exactly what each device broadcasts, so we can
                // work out a reliable scan filter (e.g. a "Shire" prefix) without
                // guessing — and compare before/after renaming the encoder.
                if (diagnostics) info(describeAdv(result, localName, gapName))
            }
        }
        override fun onScanFailed(errorCode: Int) { info("scan failed: $errorCode") }
    }

    /** One-line dump of a device's advertisement for reverse-engineering. */
    private fun describeAdv(result: ScanResult, localName: String?, gapName: String?): String {
        val rec = result.scanRecord
        val svc = rec?.serviceUuids
            ?.joinToString(",") { it.uuid.toString().substring(0, 8) } ?: "-"
        val mfg = StringBuilder()
        rec?.manufacturerSpecificData?.let { data ->
            for (i in 0 until data.size()) {
                val id = data.keyAt(i)
                val bytes = data.valueAt(i).joinToString("") { "%02X".format(it) }
                mfg.append("0x%04X:".format(id)).append(bytes).append(' ')
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
        // Show likely encoders; if there are none (e.g. a future firmware adds a
        // manufacturer ID), fall back to showing everything so nothing is lost.
        val fallback = likelyOnly.isEmpty() && all.isNotEmpty()
        val show = if (showAll.value || likelyOnly.isEmpty()) all else likelyOnly
        main.post {
            devices.value = show
            showingAllFallback.value = fallback && !showAll.value
        }
    }

    // ---- GATT -------------------------------------------------------------

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    info("connected, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    info("disconnected")
                    writeChar = null
                    loggedIn.value = false
                    phase.value = Phase.IDLE
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
            info("ready (notifications enabled)")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val delay = lastDelay
            main.postDelayed({ writing = false; drainWrites() }, delay)
        }

        // Android 13+ delivers the value directly.
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            ingest(value)
        }
        // Legacy (API < 33).
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

    // ---- Sending (serialized, optionally paced) ---------------------------

    private var lastDelay = 0L

    /** Send a command line (CR+LF appended). `paced` = one byte at a time. */
    fun send(line: String, paced: Boolean = false) {
        val full = (line + Encoder.LINE_ENDING).toByteArray(Charsets.US_ASCII)
        val gap = if (paced) Encoder.SLOW_BYTE_GAP_MS else 0L
        synchronized(pending) {
            if (paced) full.forEach { pending.addLast(byteArrayOf(it) to gap) }
            else pending.addLast(full to 0L)
        }
        info(">> $line")
        drainWrites()
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

    // ---- Receiving / line assembly ---------------------------------------

    private fun ingest(data: ByteArray) {
        for (b in data) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == '\n') flush()
            else if (c != '\r') rx.append(c)
        }
        // Prompts have no trailing newline — flush when the buffer ends in one.
        val last = rx.lastOrNull()
        if (last != null && ":>?#".contains(last)) flush()
    }

    private fun flush() {
        val s = rx.toString().trim()
        rx.setLength(0)
        if (s.isNotEmpty()) handleLine(s)
    }

    private fun handleLine(line: String) {
        info(line)
        if (line.contains(Encoder.LOGIN_MARKER)) { loggedIn.value = true; return }

        val lower = line.lowercase()
        val isPrompt = lower.contains("password") && line.endsWith(":")
        val isInvalid = lower.contains("invalid password")
        if (!isPrompt && !isInvalid) return

        loggedIn.value = false
        if (loginIndex >= candidates.size) { info("// no more passwords to try"); return }
        val pw = candidates[loginIndex]; loginIndex++
        // Settle, then send the password slowly.
        main.postDelayed({ send(pw, paced = true) }, 600)
    }

    // ---- Logging ----------------------------------------------------------

    private fun info(s: String) {
        android.util.Log.d("SAXBLE", s)   // also to Logcat (selectable/copyable)
        main.post { log.value = (log.value + s).takeLast(500) }
    }
}
