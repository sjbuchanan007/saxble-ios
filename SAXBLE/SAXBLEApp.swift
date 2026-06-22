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
        if ble.phase == .connected {
            ConnectedView()
        } else {
            ScanView()
        }
    }
}
