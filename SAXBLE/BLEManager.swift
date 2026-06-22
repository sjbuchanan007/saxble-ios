import Foundation
import CoreBluetooth
import Combine

/// A device seen during scanning.
struct DiscoveredDevice: Identifiable {
    let id: UUID            // peripheral.identifier
    let name: String
    var rssi: Int
    let peripheral: CBPeripheral
}

/// One line in the session transcript.
struct LogLine: Identifiable {
    enum Kind { case rx, tx, info }
    let id = UUID()
    let time = Date()
    let kind: Kind
    let text: String
}

/// BLE central toward the SAX-D encoder. Mirrors the Cardputer firmware's
/// ble_uart + the login/confirm wiring from main.cpp, but uses CoreBluetooth.
final class BLEManager: NSObject, ObservableObject {

    enum Phase: String { case off, scanning, connecting, connected }

    @Published var phase: Phase = .off
    @Published var devices: [DiscoveredDevice] = []
    @Published var peerName: String = ""
    @Published var loggedIn = false
    @Published var log: [LogLine] = []
    @Published var autoLogin = true

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var writeChar: CBCharacteristic?
    private var rxBuffer = ""

    // Login state (deferred, like the firmware's serviceAutoLogin()).
    private var loginCandidates: [String] = []
    private var loginIndex = 0

    // When the user fires a destructive command we answer the encoder's
    // "Y or N" prompt with "Y" automatically.
    private var awaitingConfirm = false

    // A "password <new>" command makes the encoder prompt "Retype password";
    // we resend the same value (slowly) when we see that prompt.
    private var pendingRetype: String?

    // All writes funnel through this single serial queue so a paced
    // (byte-by-byte) password can never interleave with a bulk command write.
    // Interleaved bytes reach the encoder as garbage and it rejects the login.
    private let writeQueue = DispatchQueue(label: "saxble.write")

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: - Public control

    func startScan() {
        guard central.state == .poweredOn else { return }
        devices.removeAll()
        phase = .scanning
        // Encoder doesn't advertise its service UUID, so scan for everything and
        // let the user pick (the name hint floats a likely match to the top).
        central.scanForPeripherals(withServices: nil,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    func connect(_ device: DiscoveredDevice) {
        central.stopScan()
        phase = .connecting
        peripheral = device.peripheral
        peripheral?.delegate = self
        central.connect(device.peripheral, options: nil)
    }

    func disconnect() {
        if let p = peripheral { central.cancelPeripheralConnection(p) }
    }

    /// Log out of the encoder console, then drop the BLE link so the UI returns
    /// to the scan screen. The `logout` command clears the encoder's session;
    /// disconnecting flips `phase` back to `.scanning` via the delegate.
    func logout() {
        if writeChar != nil { send("logout") }
        loggedIn = false
        // Let the logout bytes flush before we tear down the connection.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            self?.disconnect()
        }
    }

    /// Mark that the next "Y or N" prompt should be auto-answered "Y".
    func expectConfirm() { awaitingConfirm = true }

    func clearLog() { log.removeAll() }

    /// Whole-transcript text for sharing/export.
    func transcript() -> String {
        let df = DateFormatter(); df.dateFormat = "dd/MM/yyyy HH:mm:ss"
        return log.map { line in
            let tag = line.kind == .tx ? ">> " : (line.kind == .info ? "// " : "")
            return "\(df.string(from: line.time))  \(tag)\(line.text)"
        }.joined(separator: "\n")
    }

    // MARK: - Sending

    /// Send a command line; the configured CR+LF is appended automatically.
    func send(_ line: String) {
        // A "password <new>" line is rate-sensitive like the login prompt, so
        // pace it; everything else goes out back-to-back (but still serialised).
        let paced = line.lowercased().hasPrefix("password ")
        enqueueWrite(line, paced: paced)
    }

    /// Send a line one byte at a time with a small gap — needed for the password
    /// prompt, which drops characters from a single bulk write.
    func sendSlow(_ line: String) {
        enqueueWrite(line, paced: true)
    }

    /// Queue a write on the serial pipeline. `paced` inserts the slow per-byte
    /// gap (password prompt); otherwise the bytes go out back-to-back. Either
    /// way the whole line is written before the next queued line begins, so a
    /// paced password can never interleave with a command and get garbled.
    private func enqueueWrite(_ line: String, paced: Bool) {
        guard let p = peripheral, let c = writeChar else { return }
        rememberRetype(line)
        info(tx: line)
        let bytes = Array((line + Encoder.lineEnding).utf8)
        let type: CBCharacteristicWriteType =
            c.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        let gap = paced ? Encoder.slowByteGap : 0
        writeQueue.async {
            for b in bytes {
                DispatchQueue.main.async { p.writeValue(Data([b]), for: c, type: type) }
                if gap > 0 { Thread.sleep(forTimeInterval: gap) }
            }
        }
    }

    // MARK: - Logging helpers

    /// Remember the argument of a "password <new>" command so we can answer the
    /// encoder's "Retype password" prompt.
    private func rememberRetype(_ line: String) {
        let parts = line.split(separator: " ", maxSplits: 1)
        if parts.count == 2, parts[0].lowercased() == "password" {
            pendingRetype = String(parts[1])
        }
    }

    private func info(_ text: String) { append(.init(kind: .info, text: text)) }
    private func info(tx: String)     { append(.init(kind: .tx, text: tx)) }
    private func rx(_ text: String)   { append(.init(kind: .rx, text: text)) }
    private func append(_ l: LogLine) {
        log.append(l)
        if log.count > 1000 { log.removeFirst(log.count - 1000) }
    }

    // MARK: - Received-line handling (mirrors firmware handleEncoderLine)

    private func handle(line: String) {
        rx(line)
        let lower = line.lowercased()

        if awaitingConfirm, lower.contains("y or n") || lower.contains("y/n") {
            awaitingConfirm = false
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                self?.send("Y")
            }
            return
        }
        if line.contains(Encoder.loginMarker) { loggedIn = true; return }

        // "Retype password" → resend the same value slowly, then forget it.
        if lower.contains("retype"), let pw = pendingRetype {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
                self?.sendSlow(pw)
            }
            return
        }
        if lower.contains("password updated") { info("password updated successfully"); pendingRetype = nil; return }

        let isPrompt = lower.contains("password") && line.hasSuffix(":")
        let isInvalid = lower.contains("invalid password")
        guard isPrompt || isInvalid else { return }

        loggedIn = false
        guard autoLogin, loginIndex < loginCandidates.count else { return }
        let pw = loginCandidates[loginIndex]; loginIndex += 1
        // Settle, then send the password char-by-char.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
            self?.sendSlow(pw)
        }
    }

    private func ingest(_ data: Data) {
        for byte in data {
            let ch = Character(UnicodeScalar(byte))
            if ch == "\n" { flush() }
            else if ch != "\r" { rxBuffer.append(ch) }
        }
        if let last = rxBuffer.last, ":>?#".contains(last) { flush() }   // prompt
    }

    private func flush() {
        let s = rxBuffer.trimmingCharacters(in: .whitespacesAndNewlines)
        rxBuffer = ""
        if !s.isEmpty { handle(line: s) }
    }
}

// MARK: - CBCentralManagerDelegate

extension BLEManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn { startScan() }
        else { phase = .off }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?? peripheral.name ?? "(unknown)"
        let dev = DiscoveredDevice(id: peripheral.identifier, name: name,
                                   rssi: RSSI.intValue, peripheral: peripheral)
        if let i = devices.firstIndex(where: { $0.id == dev.id }) {
            devices[i].rssi = dev.rssi
        } else {
            devices.append(dev)
        }
        // Likely encoder first, then strongest signal.
        devices.sort {
            let a = $0.name == Encoder.nameHint, b = $1.name == Encoder.nameHint
            return a != b ? a : $0.rssi > $1.rssi
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        loggedIn = false
        loginCandidates = Encoder.passwordCandidates
        loginIndex = 0
        peerName = peripheral.name ?? "encoder"
        info("connected to \(peerName)")
        peripheral.discoverServices(nil)   // service UUID isn't advertised
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        writeChar = nil
        loggedIn = false
        phase = .scanning
        info("disconnected")
        startScan()
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        info("connect failed: \(error?.localizedDescription ?? "?")")
        phase = .scanning
        startScan()
    }
}

// MARK: - CBPeripheralDelegate

extension BLEManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        for s in peripheral.services ?? [] {
            peripheral.discoverCharacteristics(nil, for: s)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        for c in service.characteristics ?? [] {
            // Subscribe to every notify/indicate characteristic so we capture
            // the encoder's replies regardless of which one it uses.
            if c.properties.contains(.notify) || c.properties.contains(.indicate) {
                peripheral.setNotifyValue(true, for: c)
            }
            // Prefer the known write+notify char; else first writable.
            let writable = c.properties.contains(.write) || c.properties.contains(.writeWithoutResponse)
            if writable, writeChar == nil || c.uuid == Encoder.writeNotifyChar {
                writeChar = c
            }
        }
        if writeChar != nil, phase != .connected {
            phase = .connected
            info("ready (rx subscribed)")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        if let d = characteristic.value { ingest(d) }
    }
}
