import SwiftUI
import Combine

/// Runs a preset's steps in order, pausing mid-sequence to ask for the system
/// Location (mirrors the firmware's preset runner).
@MainActor
final class PresetRunner: ObservableObject {
    @Published var running = false
    @Published var needLocation = false
    @Published var status = ""

    private var locationCont: CheckedContinuation<String, Never>?

    func run(_ preset: Preset, ble: BLEManager) {
        guard !running else { return }
        // Presets only make sense once logged in — running before the AUTH light
        // means every command is rejected and (worse) collides with auto-login.
        guard ble.loggedIn else {
            status = "Not logged in — wait for the AUTH light before running a preset."
            return
        }
        running = true
        Task {
            for step in preset.steps {
                switch step {
                case .command(let line):
                    status = line
                    ble.send(line)
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                case .promptLocation:
                    status = "Waiting for location…"
                    let loc = await withCheckedContinuation { c in
                        locationCont = c
                        needLocation = true
                    }
                    needLocation = false
                    let token = loc.trimmingCharacters(in: .whitespaces)
                        .replacingOccurrences(of: " ", with: "\\s")   // encoder wants \s
                    if !token.isEmpty {
                        ble.send("location \(token)")
                        try? await Task.sleep(nanoseconds: 1_500_000_000)
                    }
                }
            }
            status = "Done"
            running = false
        }
    }

    func submitLocation(_ s: String) {
        locationCont?.resume(returning: s)
        locationCont = nil
    }
}

struct PresetsTab: View {
    @EnvironmentObject var ble: BLEManager
    @StateObject private var runner = PresetRunner()
    @State private var location = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    LazyVGrid(columns: cardColumns, spacing: 16) {
                        ForEach(Presets.all) { preset in
                            Button { runner.run(preset, ble: ble) } label: {
                                BigCard(title: preset.name,
                                        subtitle: "\(preset.steps.count) steps",
                                        systemImage: "wand.and.stars",
                                        tint: .shireGreen,
                                        enabled: ble.loggedIn)
                            }
                            .buttonStyle(PressableCardStyle())
                            .disabled(runner.running || !ble.loggedIn)
                        }
                    }

                    if runner.running || !runner.status.isEmpty {
                        HStack {
                            if runner.running { ProgressView() }
                            Text(runner.status)
                                .font(.system(.body, design: .monospaced))
                            Spacer()
                        }
                        .padding(16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }

                    Text(ble.loggedIn
                         ? "Presets set gas types, disable unused gases, enable pressure display, set the password, prompt for the location, then list the result and full settings. Watch the Console tab for progress."
                         : "Log in first — presets are disabled until the AUTH light is on.")
                        .font(.footnote).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Presets")
            .toolbar { AuthToolbar() }
            .alert("System location", isPresented: $runner.needLocation) {
                TextField("e.g. Ward 4 Theatre 2", text: $location)
                Button("Send") { runner.submitLocation(location); location = "" }
                Button("Skip", role: .cancel) { runner.submitLocation("") }
            } message: {
                Text("Spaces are converted to \\s for the encoder.")
            }
        }
    }
}
