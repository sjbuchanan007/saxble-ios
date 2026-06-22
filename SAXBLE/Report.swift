import SwiftUI
import UIKit
import CoreText

/// Builds a PDF commissioning report from the session transcript and wraps the
/// system share sheet. Kept UIKit-side so the SwiftUI views stay simple.
enum Report {

    /// Render `body` (the session transcript) into a multi-page A4 PDF with a
    /// header, returning a temp-file URL ready to share. Returns nil on failure.
    static func makePDF(title: String, subtitle: String, body: String) -> URL? {
        let pageRect = CGRect(x: 0, y: 0, width: 595.2, height: 841.8)   // A4, points
        let margin: CGFloat = 40
        let contentRect = pageRect.insetBy(dx: margin, dy: margin)

        let df = DateFormatter(); df.dateFormat = "dd/MM/yyyy HH:mm"

        let text = NSMutableAttributedString()
        text.append(NSAttributedString(string: "\(title)\n",
            attributes: [.font: UIFont.boldSystemFont(ofSize: 18)]))
        text.append(NSAttributedString(string: "\(subtitle)\nGenerated \(df.string(from: Date()))\n\n",
            attributes: [.font: UIFont.systemFont(ofSize: 10),
                         .foregroundColor: UIColor.secondaryLabel]))
        text.append(NSAttributedString(string: body,
            attributes: [.font: UIFont.monospacedSystemFont(ofSize: 8, weight: .regular)]))

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("SAXBLE-report-\(Int(Date().timeIntervalSince1970)).pdf")

        let framesetter = CTFramesetterCreateWithAttributedString(text)
        let renderer = UIGraphicsPDFRenderer(bounds: pageRect)
        do {
            try renderer.writePDF(to: url) { ctx in
                var pos = 0
                let total = text.length
                while pos < total {
                    ctx.beginPage()
                    let cg = ctx.cgContext
                    cg.textMatrix = .identity
                    cg.translateBy(x: 0, y: pageRect.height)
                    cg.scaleBy(x: 1, y: -1)   // CoreText draws bottom-up

                    let path = CGPath(rect: CGRect(x: margin, y: margin,
                        width: contentRect.width, height: contentRect.height), transform: nil)
                    let frame = CTFramesetterCreateFrame(framesetter, CFRangeMake(pos, 0), path, nil)
                    CTFrameDraw(frame, cg)

                    let drawn = CTFrameGetVisibleStringRange(frame).length
                    if drawn <= 0 { break }            // avoid an infinite loop
                    pos += drawn
                }
            }
            return url
        } catch {
            return nil
        }
    }
}

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
