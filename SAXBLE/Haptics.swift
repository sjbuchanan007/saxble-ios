import UIKit

/// Tiny wrapper over the system haptic generators so call sites stay readable.
/// All calls hop to the main actor (UIKit requirement) and are no-ops on
/// devices without a Taptic Engine.
enum Haptics {
    /// Light tap for routine actions (tapping a device, sending a command).
    static func tap() {
        DispatchQueue.main.async {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }

    /// Success notification (login confirmed, password updated).
    static func success() {
        DispatchQueue.main.async {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
    }

    /// Warning notification (destructive command fired).
    static func warning() {
        DispatchQueue.main.async {
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
        }
    }
}
