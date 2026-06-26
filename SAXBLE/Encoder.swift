import Foundation
import CoreBluetooth

/// Facts about the SAX-D encoder's BLE interface, learned from the live unit and
/// the Cardputer firmware. Centralised here so the rest of the app stays generic.
enum Encoder {
    /// Microchip/ISSC "transparent UART" service (NOT Nordic UART).
    static let service = CBUUID(string: "49535343-FE7D-4AE5-8FA9-9FAFD205E455")

    /// Write+Notify characteristic used as both TX (commands) and RX (replies).
    static let writeNotifyChar = CBUUID(string: "49535343-1E4D-4BD9-BA61-23C647249616")

    /// CR+LF is REQUIRED for login on this encoder (LF-only is rejected,
    /// CR-only gets no reply).
    static let lineEnding = "\r\n"

    /// Tried in order on each "Password:" prompt until one logs in.
    static let passwordCandidates = ["MMSmms659", "studio3"]

    /// Substring of the post-login banner ("Welcome to Shire SAX Command Line
    /// Interface"). Kept short to tolerate wording differences.
    static let loginMarker = "Welcome to Shire"

    /// The encoder advertises under its Location string and does NOT include the
    /// service UUID in its advertising packet, so we can't filter the scan by
    /// service. This hint just floats a likely match to the top of the list.
    static let nameHint = "mmssjbt1"

    /// A single bulk write drops the last password character on this encoder, so
    /// the password is sent one byte at a time with this gap (mimics typing).
    static let slowByteGap: TimeInterval = 0.035

    /// Extra pause after the last content byte, before the CR+LF, on paced
    /// sends. The encoder tends to drop the final character if the terminator
    /// treads on its heels — this gives it time to commit.
    static let slowTerminatorGap: TimeInterval = 0.12
}
