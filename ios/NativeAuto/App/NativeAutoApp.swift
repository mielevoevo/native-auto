import SwiftUI

@main
struct NativeAutoApp: App {
    var body: some Scene {
        WindowGroup {
            SpeedScreen()
                .preferredColorScheme(.dark)
                .onAppear { UIApplication.shared.isIdleTimerDisabled = true }   // dashboard: screen stays on
        }
    }
}
