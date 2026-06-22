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
            List {
                Section {
                    ForEach(Presets.all) { preset in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(preset.name).font(.headline)
                                Text("\(preset.steps.count) steps")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Run") { runner.run(preset, ble: ble) }
                                .buttonStyle(.borderedProminent)
                                .disabled(runner.running || !ble.loggedIn)
                        }
                    }
                } footer: {
                    Text("Presets set gas types, disable unused gases, enable pressure display, set the password, prompt for the location, then list the result. Watch the Console tab for progress.")
                }
                if runner.running || !runner.status.isEmpty {
                    Section("Status") {
                        HStack {
                            if runner.running { ProgressView() }
                            Text(runner.status).font(.system(.body, design: .monospaced))
                        }
                    }
                }
            }
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
