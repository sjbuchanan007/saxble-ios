import SwiftUI
import UIKit
import CoreText

// MARK: - Parsed commissioning data

/// One gas channel parsed from the encoder's `gas a list` output.
struct GasChannel {
    let index: Int
    var type: String?
    var bottle: String?
    var pressure: String?
    var condition: String?     // live state (Normal / OC / Alarm …)
    var highAlarm: String?
    var lowAlarm: String?
    var dropAlarm: String?
    var units: String?
    var display: String?
    var name: String?
    var enabled: Bool { type != nil }
}

/// System fields parsed from the encoder's `settings` output.
struct CommissioningData {
    var channels: [GasChannel] = []
    var settings: [(key: String, value: String)] = []

    func setting(_ key: String) -> String? {
        settings.first { $0.key.caseInsensitiveCompare(key) == .orderedSame }?.value
    }
}

// MARK: - Report builder

enum Report {

    private static let gasKeys: Set<String> = [
        "Gas Type", "Bottle Color", "Pressure", "High Alarm", "Low Alarm",
        "Drop Alarm", "Hi_Diff", "Lo_Diff", "Pd_Diff", "Display", "Units",
        "Demo", "Gas Name", "Condition",
    ]
    private static let settingKeys: Set<String> = [
        "Location", "Password", "Engineer No", "Supply", "Battery",
        "Modbus Addr", "Modbus speed", "Tone Type", "Screen saver",
        "Mute timer", "Logout Time", "Log Time", "EEPROM", "Bluetooth Ver",
        "Hardware Ver", "Software Ver", "Build", "Serial",
    ]

    /// Parse the encoder's reply lines (rx only) into structured data.
    static func parse(rxLines: [String]) -> CommissioningData {
        var data = CommissioningData()
        var channelByIndex: [Int: GasChannel] = [:]
        var currentGas: Int?
        var awaitingCondition: Int?

        for raw in rxLines {
            // Strip any leading "> " console prompt so keys parse cleanly.
            let line = raw.trimmingCharacters(in: CharacterSet(charactersIn: "> \t"))
            if line.isEmpty { continue }

            // New gas block?
            if let r = line.range(of: #"^\*+\s*Gas:\s*(\d+)"#, options: .regularExpression) {
                let digits = line[r].filter(\.isNumber)
                if let n = Int(digits) {
                    currentGas = n
                    if channelByIndex[n] == nil { channelByIndex[n] = GasChannel(index: n) }
                }
                awaitingCondition = nil
                continue
            }
            if line.hasPrefix("*") { continue }   // separator row

            // A bare value following a "Condition:" with no inline value.
            if let g = awaitingCondition, !line.contains(":") {
                if channelByIndex[g]?.condition == nil { channelByIndex[g]?.condition = clean(line) }
                awaitingCondition = nil
                continue
            }

            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = String(line[..<colon]).trimmingCharacters(in: .whitespaces)
            let value = clean(String(line[line.index(after: colon)...]))

            if gasKeys.contains(key), let g = currentGas {
                apply(key: key, value: value, to: &channelByIndex[g]!, awaiting: &awaitingCondition, gas: g)
            } else if settingKeys.contains(key) {
                // Keep first occurrence of each setting.
                if data.setting(key) == nil { data.settings.append((key, value)) }
            }
        }

        data.channels = channelByIndex.keys.sorted().compactMap { channelByIndex[$0] }
        return data
    }

    private static func apply(key: String, value: String, to ch: inout GasChannel,
                              awaiting: inout Int?, gas: Int) {
        switch key {
        case "Gas Type":     ch.type = value
        case "Bottle Color": ch.bottle = value
        case "Pressure":     ch.pressure = value
        case "High Alarm":   ch.highAlarm = value
        case "Low Alarm":    ch.lowAlarm = value
        case "Drop Alarm":   ch.dropAlarm = value
        case "Units":        ch.units = value
        case "Display":      ch.display = value
        case "Gas Name":     ch.name = value
        case "Condition":
            if ch.condition == nil {
                if value.isEmpty { awaiting = gas }           // value is on the next line
                else if value != "on" && value != "off" { ch.condition = value }
                else if ch.type == nil { ch.condition = value } // disabled block: "Condition: off"
            }
        default: break
        }
    }

    /// Strip surrounding quotes and collapse runs of whitespace.
    private static func clean(_ s: String) -> String {
        var v = s.trimmingCharacters(in: .whitespaces)
        if v.hasPrefix("\""), v.hasSuffix("\""), v.count >= 2 { v = String(v.dropFirst().dropLast()) }
        return v.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    // MARK: PDF

    /// Build a structured commissioning PDF (summary page + appended transcript)
    /// and return a temp-file URL named with the location and date.
    static func commissioningPDF(rxLines: [String], transcript: String,
                                 fallbackName: String) -> URL? {
        let data = parse(rxLines: rxLines)
        let now = Date()

        let summary = summaryDocument(data: data, fallbackName: fallbackName, date: now)
        let log = transcriptDocument(transcript)

        let pageRect = CGRect(x: 0, y: 0, width: 595.2, height: 841.8)   // A4
        let margin: CGFloat = 40

        let loc = data.setting("Location") ?? fallbackName
        let df = DateFormatter(); df.dateFormat = "yyyy-MM-dd"
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("SAXBLE-\(slug(loc))-\(df.string(from: now)).pdf")

        let renderer = UIGraphicsPDFRenderer(bounds: pageRect)
        do {
            try renderer.writePDF(to: url) { ctx in
                draw(summary, into: ctx, pageRect: pageRect, margin: margin)
                draw(log, into: ctx, pageRect: pageRect, margin: margin)
            }
            return url
        } catch {
            return nil
        }
    }

    /// Draw an attributed string across as many pages as needed.
    private static func draw(_ text: NSAttributedString, into ctx: UIGraphicsPDFRendererContext,
                             pageRect: CGRect, margin: CGFloat) {
        guard text.length > 0 else { return }
        let content = pageRect.insetBy(dx: margin, dy: margin)
        let fs = CTFramesetterCreateWithAttributedString(text)
        var pos = 0
        let total = text.length
        repeat {
            ctx.beginPage()
            let cg = ctx.cgContext
            cg.textMatrix = .identity
            cg.translateBy(x: 0, y: pageRect.height)
            cg.scaleBy(x: 1, y: -1)
            let path = CGPath(rect: content, transform: nil)
            let frame = CTFramesetterCreateFrame(fs, CFRangeMake(pos, 0), path, nil)
            CTFrameDraw(frame, cg)
            let drawn = CTFrameGetVisibleStringRange(frame).length
            if drawn <= 0 { break }
            pos += drawn
        } while pos < total
    }

    // MARK: Document content

    private static func summaryDocument(data: CommissioningData, fallbackName: String,
                                        date: Date) -> NSAttributedString {
        let df = DateFormatter(); df.dateFormat = "dd/MM/yyyy HH:mm"
        let out = NSMutableAttributedString()

        out.append(line("SAXBLE Commissioning Report\n", .boldSystemFont(ofSize: 18)))
        out.append(line("\(data.setting("Location") ?? fallbackName)   ·   \(df.string(from: date))\n\n",
                        .systemFont(ofSize: 10), .secondaryLabel))

        // Header facts
        let facts: [(String, String?)] = [
            ("Location", data.setting("Location")),
            ("Engineer", data.setting("Engineer No")),
            ("Serial", data.setting("Serial")),
            ("Software", data.setting("Software Ver")),
            ("Hardware", data.setting("Hardware Ver")),
            ("Supply", data.setting("Supply")),
            ("Battery", data.setting("Battery")),
        ]
        for (k, v) in facts where v != nil {
            out.append(line("\(k.padding(toLength: 10, withPad: " ", startingAt: 0)) \(v!)\n",
                            .monospacedSystemFont(ofSize: 9, weight: .regular)))
        }

        // Gas table
        out.append(line("\nGas Channels\n", .boldSystemFont(ofSize: 13)))
        let cols = [("Ch", 4), ("Type", 7), ("Pressure", 11), ("High Alarm", 12),
                    ("Low Alarm", 12), ("Drop Alarm", 12), ("Display", 18)]
        var table = row(cols.map { ($0.0, $0.1) }) + "\n"
        table += String(repeating: "─", count: cols.reduce(0) { $0 + $1.1 }) + "\n"
        if data.channels.isEmpty {
            table += "(no `gas a list` output captured in this session)\n"
        } else {
            for ch in data.channels {
                if ch.enabled {
                    table += row([
                        ("\(ch.index)", 4),
                        (ch.type ?? "—", 7),
                        (ch.pressure ?? "—", 11),
                        (ch.highAlarm ?? "—", 12),
                        (ch.lowAlarm ?? "—", 12),
                        (ch.dropAlarm ?? "—", 12),
                        (ch.display ?? "—", 18),
                    ]) + "\n"
                } else {
                    table += row([("\(ch.index)", 4), ("disabled", 7)]) + "\n"
                }
            }
        }
        out.append(line(table, .monospacedSystemFont(ofSize: 8, weight: .regular)))

        // System settings
        out.append(line("\nSystem Settings\n", .boldSystemFont(ofSize: 13)))
        let order = ["Modbus Addr", "Modbus speed", "Tone Type", "Screen saver",
                     "Mute timer", "Logout Time", "Log Time", "EEPROM",
                     "Bluetooth Ver", "Build"]
        var sys = ""
        for k in order { if let v = data.setting(k) {
            sys += "\(k.padding(toLength: 14, withPad: " ", startingAt: 0)) \(v)\n"
        } }
        if sys.isEmpty { sys = "(no `settings` output captured in this session)\n" }
        out.append(line(sys, .monospacedSystemFont(ofSize: 9, weight: .regular)))

        return out
    }

    private static func transcriptDocument(_ transcript: String) -> NSAttributedString {
        let out = NSMutableAttributedString()
        out.append(line("Console Transcript\n\n", .boldSystemFont(ofSize: 13)))
        out.append(line(transcript, .monospacedSystemFont(ofSize: 8, weight: .regular)))
        return out
    }

    // MARK: small helpers

    private static func line(_ s: String, _ font: UIFont,
                             _ color: UIColor = .label) -> NSAttributedString {
        NSAttributedString(string: s, attributes: [.font: font, .foregroundColor: color])
    }

    /// Fixed-width row: each cell padded/truncated to its column width.
    private static func row(_ cells: [(String, Int)]) -> String {
        cells.map { text, width -> String in
            if text.count >= width { return String(text.prefix(max(0, width - 1))) + " " }
            return text + String(repeating: " ", count: width - text.count)
        }.joined()
    }

    /// Filesystem-safe token from a location string.
    private static func slug(_ s: String) -> String {
        let allowed = CharacterSet.alphanumerics
        let mapped = s.unicodeScalars.map { allowed.contains($0) ? Character($0) : "-" }
        let collapsed = String(mapped).split(separator: "-").joined(separator: "-")
        return collapsed.isEmpty ? "encoder" : collapsed
    }
}

// MARK: - Share sheet

/// Something the share sheet can present (PDF URL and/or plain text).
struct ShareItem: Identifiable {
    let id = UUID()
    let items: [Any]
}

/// Thin SwiftUI wrapper over UIActivityViewController.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
