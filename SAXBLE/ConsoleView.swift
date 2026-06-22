import SwiftUI

/// Live transcript of everything to/from the encoder, a raw-command field, and
/// a Share button to export the session for a commissioning report.
struct ConsoleTab: View {
    @EnvironmentObject var ble: BLEManager
    @State private var entry = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 2) {
                            ForEach(ble.log) { line in
                                Text(line.text)
                                    .font(.system(.footnote, design: .monospaced))
                                    .foregroundStyle(color(for: line.kind))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .id(line.id)
                            }
                        }
                        .padding(.horizontal, 8)
                    }
                    .onChange(of: ble.log.count) { _, _ in
                        if let last = ble.log.last { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
                Divider()
                HStack {
                    TextField("raw command", text: $entry)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                        .onSubmit(sendRaw)
                    Button("Send", action: sendRaw).disabled(entry.isEmpty)
                }
                .padding(8)
            }
            .navigationTitle("Console")
            .toolbar {
                AuthToolbar()
                ToolbarItem(placement: .topBarLeading) {
                    ShareLink(item: ble.transcript(),
                              preview: SharePreview("SAXBLE session")) {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
            }
        }
    }

    private func sendRaw() {
        let line = entry.trimmingCharacters(in: .whitespaces)
        guard !line.isEmpty else { return }
        ble.send(line)
        entry = ""
    }

    private func color(for kind: LogLine.Kind) -> Color {
        switch kind {
        case .tx: return .green
        case .info: return .secondary
        case .rx: return .primary
        }
    }
}
