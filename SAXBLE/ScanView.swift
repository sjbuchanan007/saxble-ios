import SwiftUI

/// Pick the encoder from nearby BLE devices (the encoder advertises under its
/// Location name; a likely match is floated to the top).
struct ScanView: View {
    @EnvironmentObject var ble: BLEManager

    var body: some View {
        NavigationStack {
            ScrollView {
                if !ble.devices.isEmpty {
                    Text(ble.phase == .connecting ? "Connecting…" : "Nearby devices")
                        .font(.footnote).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16).padding(.top, 8)
                }
                LazyVStack(spacing: 12) {
                    ForEach(ble.devices) { dev in
                        Button { ble.connect(dev) } label: { DeviceRow(dev: dev) }
                            .buttonStyle(PressableCardStyle())
                    }
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground))
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
        .tint(.shireTeal)
    }
}

/// A single discovered-device card in the scan list.
struct DeviceRow: View {
    let dev: DiscoveredDevice
    private var likely: Bool { dev.name == Encoder.nameHint }

    var body: some View {
        HStack(spacing: 14) {
            IconBadge(systemImage: likely ? "star.fill" : "dot.radiowaves.left.and.right",
                      tint: likely ? .shireGreen : .shireTeal, size: 44)
            VStack(alignment: .leading, spacing: 3) {
                Text(dev.name).font(.headline).foregroundStyle(.primary)
                Text(likely ? "Likely encoder" : String(dev.id.uuidString.prefix(8)) + "…")
                    .font(.caption).foregroundStyle(likely ? .shireGreen : .secondary)
            }
            Spacer()
            VStack(spacing: 2) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.footnote).foregroundStyle(.secondary)
                Text("\(dev.rssi) dBm").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(likely ? .shireGreen : .shireTeal, radius: 16)
    }
}
