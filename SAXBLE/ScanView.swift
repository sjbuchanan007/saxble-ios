import SwiftUI

/// Pick the encoder from nearby BLE devices (the encoder advertises under its
/// Location name; a likely match is floated to the top).
struct ScanView: View {
    @EnvironmentObject var ble: BLEManager

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(ble.devices) { dev in
                        Button { ble.connect(dev) } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(dev.name).font(.headline)
                                    Text(dev.id.uuidString.prefix(8) + "…")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text("\(dev.rssi) dBm")
                                    .font(.subheadline).foregroundStyle(.secondary)
                                if dev.name == Encoder.nameHint {
                                    Image(systemName: "star.fill").foregroundStyle(.yellow)
                                }
                            }
                        }
                    }
                } header: {
                    Text(ble.phase == .connecting ? "Connecting…" : "Nearby devices")
                }
            }
            .navigationTitle("SAXBLE")
            .toolbar {
                Button { ble.startScan() } label: { Image(systemName: "arrow.clockwise") }
            }
            .overlay {
                if ble.devices.isEmpty {
                    ContentUnavailableView("Scanning…",
                        systemImage: "dot.radiowaves.left.and.right",
                        description: Text("Make sure the encoder is powered and not connected to another app."))
                }
            }
        }
    }
}
