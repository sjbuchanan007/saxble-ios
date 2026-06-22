import SwiftUI

/// Live transcript of everything to/from the encoder, a raw-command field, and
/// a Share button to export the session for a commissioning report.
struct ConsoleTab: View {
    @EnvironmentObject var ble: BLEManager
    @State private var entry = ""
    @FocusState private var entryFocused: Bool
    @State private var share: ShareItem?

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
                        .focused($entryFocused)
                        .submitLabel(.send)
                        .onSubmit(sendRaw)
                    Button("Send", action: sendRaw).disabled(entry.isEmpty)
                }
                .padding(8)
            }
            .navigationTitle("Console")
            .toolbar {
                AuthToolbar()
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button {
                            let rx = ble.log.filter { $0.kind == .rx }.map(\.text)
                            if let url = Report.commissioningPDF(
                                rxLines: rx,
                                transcript: ble.transcript(),
                                fallbackName: ble.peerName.isEmpty ? "encoder" : ble.peerName) {
                                share = ShareItem(items: [url])
                            }
                        } label: { Label("Export PDF report", systemImage: "doc.richtext") }
                        Button {
                            share = ShareItem(items: [ble.transcript()])
                        } label: { Label("Share transcript (text)", systemImage: "doc.plaintext") }
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { entryFocused = false }
                }
            }
            .sheet(item: $share) { ShareSheet(items: $0.items) }
        }
    }

    private func sendRaw() {
        let line = entry.trimmingCharacters(in: .whitespaces)
        guard !line.isEmpty else { return }
        ble.send(line)
        entry = ""
        entryFocused = false
    }

    private func color(for kind: LogLine.Kind) -> Color {
        switch kind {
        case .tx: return .green
        case .info: return .secondary
        case .rx: return .primary
        }
    }
}
