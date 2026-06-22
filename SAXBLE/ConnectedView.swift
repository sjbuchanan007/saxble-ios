import SwiftUI

/// Main screen once connected: Gas / General command grids, presets, console.
struct ConnectedView: View {
    @EnvironmentObject var ble: BLEManager

    var body: some View {
        TabView {
            CommandGridTab(category: SAXCommands.gasCategory, tint: .orange)
                .tabItem { Label("Gas", systemImage: "flame.fill") }
            CommandGridTab(category: SAXCommands.generalCategory, tint: .blue)
                .tabItem { Label("General", systemImage: "gearshape.2.fill") }
            PresetsTab().tabItem { Label("Presets", systemImage: "wand.and.stars") }
            ConsoleTab().tabItem { Label("Console", systemImage: "terminal") }
        }
    }
}

// MARK: - Shared layout

/// Two equal, comfortably spaced columns used by the card grids.
let cardColumns = [GridItem(.flexible(), spacing: 16), GridItem(.flexible(), spacing: 16)]

/// A big tappable card (category launcher / preset). Looks the same whether it
/// wraps a NavigationLink or a Button so the whole UI feels consistent.
struct BigCard: View {
    let title: String
    let subtitle: String
    let systemImage: String
    var tint: Color = .accentColor
    var enabled: Bool = true

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(tint)
            Spacer(minLength: 8)
            Text(title).font(.headline).foregroundStyle(.primary)
            Text(subtitle).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 130, alignment: .leading)
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(.quaternary))
        .opacity(enabled ? 1 : 0.5)
    }
}

// MARK: - Commands

/// One command category (Gas or General) shown as a 2-column grid of buttons.
struct CommandGridTab: View {
    @EnvironmentObject var ble: BLEManager
    let category: CommandCategory
    var tint: Color = .accentColor

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: cardColumns, spacing: 14) {
                    ForEach(category.commands) { cmd in
                        NavigationLink {
                            CommandDetailView(cmd: cmd)
                        } label: {
                            CommandCard(cmd: cmd, tint: tint)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(16)
            }
            .navigationTitle(category.title)
            .toolbar { AuthToolbar() }
        }
    }
}

/// A compact command button used in the grids.
struct CommandCard: View {
    let cmd: CommandDef
    var tint: Color = .accentColor

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: cmd.destructive
                      ? "exclamationmark.triangle.fill" : "chevron.right.circle.fill")
                    .font(.title3)
                    .foregroundStyle(cmd.destructive ? .orange : tint)
                Spacer()
            }
            Spacer(minLength: 4)
            Text(cmd.label)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, minHeight: 88, alignment: .leading)
        .padding(14)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(.quaternary))
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
            HStack(spacing: 12) {
                Label(ble.loggedIn ? "AUTH" : "no auth",
                      systemImage: ble.loggedIn ? "lock.open.fill" : "lock.fill")
                    .labelStyle(.titleAndIcon)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(ble.loggedIn ? .green : .secondary)
                // Logs out of the encoder and returns to the scan screen.
                Button { ble.logout() } label: {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                }
            }
        }
    }
}
