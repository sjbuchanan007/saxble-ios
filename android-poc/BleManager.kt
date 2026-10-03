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

    data class FoundDevice(val name: String, val address: String, val rssi: Int, val device: BluetoothDevice)

    // Observable state for Compose.
    val phase = MutableStateFlow(Phase.IDLE)
    val devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val log = MutableStateFlow<List<String>>(emptyList())
    val loggedIn = MutableStateFlow(false)

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

    // ---- Scanning ---------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = dev.name ?: result.scanRecord?.deviceName ?: "(unknown)"
            val fd = FoundDevice(name, dev.address, result.rssi, dev)
            val isNew = !found.containsKey(dev.address)
            found[dev.address] = fd
            if (isNew) publishDevices()  // update signal in place, don't reorder constantly
        }
        override fun onScanFailed(errorCode: Int) { info("scan failed: $errorCode") }
    }

    private fun publishDevices() {
        // Named devices first, then by signal — stable enough to tap.
        val list = found.values.sortedWith(
            compareByDescending<FoundDevice> { it.name != "(unknown)" }.thenByDescending { it.rssi }
        )
        main.post { devices.value = list }
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
        main.post { log.value = (log.value + s).takeLast(500) }
    }
}
