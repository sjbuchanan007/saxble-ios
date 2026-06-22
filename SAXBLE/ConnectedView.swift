import SwiftUI

/// Main screen once connected: Gas / General command grids, presets, console.
struct ConnectedView: View {
    @EnvironmentObject var ble: BLEManager

    var body: some View {
        TabView {
            CommandGridTab(category: SAXCommands.gasCategory, tint: .orange)
                .tabItem { Label("Gas", systemImage: "flame.fill") }
            CommandGridTab(category: SAXCommands.generalCategory, tint: .shireTeal)
                .tabItem { Label("General", systemImage: "gearshape.2.fill") }
            PresetsTab().tabItem { Label("Presets", systemImage: "wand.and.stars") }
            ConsoleTab().tabItem { Label("Console", systemImage: "terminal") }
        }
        .tint(.shireTeal)
    }
}

// MARK: - Theme & shared layout

extension Color {
    /// Shire Controls brand colours (from the logo).
    static let shireTeal  = Color(red: 0.05, green: 0.44, blue: 0.49)
    static let shireGreen = Color(red: 0.55, green: 0.78, blue: 0.25)
}

/// Two equal, comfortably spaced columns used by the card grids.
let cardColumns = [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)]

/// Brand gradient navigation bar (white title/buttons over teal→green).
struct BrandNavBar: ViewModifier {
    func body(content: Content) -> some View {
        content
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(
                LinearGradient(colors: [.shireTeal, .shireGreen],
                               startPoint: .leading, endPoint: .trailing),
                for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
    }
}
extension View { func brandNavBar() -> some View { modifier(BrandNavBar()) } }

/// Subtle tactile press for card buttons.
struct PressableCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Frosted, lightly tinted card background shared by every launcher card.
struct CardSurface: ViewModifier {
    var tint: Color
    var radius: CGFloat = 18
    func body(content: Content) -> some View {
        content.background(
            ZStack {
                RoundedRectangle(cornerRadius: radius, style: .continuous).fill(.ultraThinMaterial)
                RoundedRectangle(cornerRadius: radius, style: .continuous).fill(tint.opacity(0.10))
            }
        )
        .overlay(
            RoundedRectangle(cornerRadius: radius, style: .continuous)
                .strokeBorder(tint.opacity(0.22), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.06), radius: 8, x: 0, y: 4)
    }
}
extension View {
    func cardSurface(_ tint: Color, radius: CGFloat = 18) -> some View {
        modifier(CardSurface(tint: tint, radius: radius))
    }
}

/// Tinted rounded icon badge.
struct IconBadge: View {
    let systemImage: String
    var tint: Color = .accentColor
    var size: CGFloat = 40
    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(tint.opacity(0.15),
                        in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous))
    }
}

/// A big launcher / preset card.
struct BigCard: View {
    let title: String
    let subtitle: String
    let systemImage: String
    var tint: Color = .accentColor
    var enabled: Bool = true

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            IconBadge(systemImage: systemImage, tint: tint, size: 48)
            Spacer(minLength: 10)
            Text(title).font(.headline).foregroundStyle(.primary)
            Text(subtitle).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 140, alignment: .leading)
        .padding(16)
        .cardSurface(tint)
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
                        .buttonStyle(PressableCardStyle())
                    }
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(category.title)
            .toolbar { AuthToolbar() }
            .brandNavBar()
        }
    }
}

/// A compact command button used in the grids.
struct CommandCard: View {
    let cmd: CommandDef
    var tint: Color = .accentColor
    private var color: Color { cmd.destructive ? .orange : tint }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            IconBadge(systemImage: icon, tint: color, size: 38)
            Spacer(minLength: 6)
            Text(cmd.label)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            Text(cmd.token)
                .font(.system(.caption2, design: .monospaced))
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, minHeight: 112, alignment: .leading)
        .padding(14)
        .cardSurface(color, radius: 16)
    }

    /// Icon hints at what the command does.
    private var icon: String {
        if cmd.destructive { return "exclamationmark.triangle.fill" }
        switch cmd.param {
        case .enumPick: return "slider.horizontal.3"
        case .numeric:  return "number"
        case .text:     return "textformat"
        case .none:     return "bolt.fill"
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
                Button {
                    if cmd.destructive { showConfirm = true } else { fire() }
                } label: {
                    Text(cmd.destructive ? "Send (destructive)" : "Send")
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(cmd.destructive ? .red : .shireTeal)
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                .listRowBackground(Color.clear)
            }
        }
        .navigationTitle(cmd.label)
        .toolbar { AuthToolbar() }
        .brandNavBar()
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
        if cmd.destructive { ble.expectConfirm(); Haptics.warning() } else { Haptics.tap() }
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
                    .foregroundStyle(ble.loggedIn ? Color.green : Color.secondary)
                // Logs out of the encoder and returns to the scan screen.
                Button { ble.logout() } label: {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                }
            }
        }
    }
}
