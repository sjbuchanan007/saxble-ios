import SwiftUI

@main
struct SAXBLEApp: App {
    @StateObject private var ble = BLEManager()

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(ble)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var ble: BLEManager

    var body: some View {
        // Only show the commands once actually logged in (AUTH), not merely
        // connected — otherwise we'd land on commands while still at the password
        // prompt. The scan screen handles connecting + the password prompt.
        if ble.loggedIn {
            ConnectedView()
        } else {
            ScanView()
        }
    }
}
