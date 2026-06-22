import SwiftUI

/// Main screen once connected: command menus, presets, and the live console.
struct ConnectedView: View {
    @EnvironmentObject var ble: BLEManager

    var body: some View {
        TabView {
            CommandsTab().tabItem { Label("Commands", systemImage: "list.bullet") }
            PresetsTab().tabItem { Label("Presets", systemImage: "wand.and.stars") }
            ConsoleTab().tabItem { Label("Console", systemImage: "terminal") }
        }
    }
}

// MARK: - Commands

struct CommandsTab: View {
    @EnvironmentObject var ble: BLEManager

    var body: some View {
        NavigationStack {
            List {
                ForEach(SAXCommands.categories) { cat in
                    Section(cat.title) {
                        ForEach(cat.commands) { cmd in
                            NavigationLink {
                                CommandDetailView(cmd: cmd)
                            } label: {
                                HStack {
                                    Text(cmd.label)
                                    if cmd.destructive {
                                        Image(systemName: "exclamationmark.triangle.fill")
                                            .foregroundStyle(.orange).font(.caption)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Commands")
            .toolbar { AuthToolbar() }
        }
    }
}

/// Channel picker (gas commands) + parameter entry + Send.
struct CommandDetailView: View {
    @EnvironmentObject var ble: BLEManager
    @Environment(\.dismiss) private var dismiss
    let cmd: CommandDef

    @State private var channel = "1"
    @State private var text = ""
    @State private var enumValue = ""
    @State private var showConfirm = false

    var body: some View {
        Form {
            if !cmd.help.isEmpty {
                Section { Text(cmd.help).foregroundStyle(.secondary) }
            }
            if cmd.needsChannel {
                Section("Channel") {
                    Picker("Channel", selection: $channel) {
                        ForEach(SAXCommands.channels, id: \.self) { c in
                            Text(c == "a" ? "All" : c).tag(c)
                        }
                    }.pickerStyle(.segmented)
                }
            }
            switch cmd.param {
            case .none: EmptyView()
            case .numeric:
                Section(cmd.paramHint ?? "Value") {
                    TextField(cmd.paramHint ?? "Value", text: $text)
                        .keyboardType(.numbersAndPunctuation)
                }
            case .text:
                Section(cmd.paramHint ?? "Text") {
                    TextField(cmd.paramHint ?? "Text", text: $text)
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                }
            case .enumPick:
                Section("Option") {
                    Picker("Option", selection: $enumValue) {
                        Text("—").tag("")
                        ForEach(cmd.enumOptions) { o in Text(o.label).tag(o.value) }
                    }
                }
            }
            Section {
                Button(cmd.destructive ? "Send (destructive)…" : "Send") {
                    if cmd.destructive { showConfirm = true } else { fire() }
                }
                .foregroundStyle(cmd.destructive ? .red : .accentColor)
            }
        }
        .navigationTitle(cmd.label)
        .toolbar { AuthToolbar() }
        .alert("Confirm \(cmd.label)?", isPresented: $showConfirm) {
            Button("Send", role: .destructive) { fire() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This is a destructive command. Any \"Y or N\" prompt will be answered Y automatically.")
        }
    }

    private func currentParam() -> String {
        switch cmd.param {
        case .none: return ""
        case .enumPick: return enumValue
        default: return text.trimmingCharacters(in: .whitespaces)
        }
    }

    private func fire() {
        if cmd.destructive { ble.expectConfirm() }
        // Password change is rate-sensitive on this encoder → send slowly.
        let line = cmd.build(channel: channel, param: currentParam())
        if cmd.id == "gen_password" {
            ble.sendSlow(line)
        } else {
            ble.send(line)
        }
        dismiss()
    }
}

// MARK: - Auth indicator

struct AuthToolbar: ToolbarContent {
    @EnvironmentObject var ble: BLEManager
    var body: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            HStack(spacing: 8) {
                Label(ble.loggedIn ? "AUTH" : "no auth",
                      systemImage: ble.loggedIn ? "lock.open.fill" : "lock.fill")
                    .font(.caption2)
                    .foregroundStyle(ble.loggedIn ? .green : .secondary)
                Button(role: .destructive) { ble.disconnect() } label: {
                    Image(systemName: "xmark.circle")
                }
            }
        }
    }
}
