import SwiftUI
import UIKit

@main
struct NativeAutoApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var settings: SettingsStore
    @StateObject private var trip: TripStore
    @StateObject private var dashboard: DashboardModel
    @StateObject private var battery = BatteryMonitor()

    init() {
        let s = SettingsStore()
        _settings = StateObject(wrappedValue: s)
        _trip = StateObject(wrappedValue: TripStore(settings: s))
        _dashboard = StateObject(wrappedValue: DashboardModel(settings: s))
        // The orientation is asked for before the first frame (Android `MainActivity` does the same).
        AppDelegate.orientationMask = s.orientationMode.mask
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(settings)
                .environmentObject(trip)
                .environmentObject(dashboard)
                .environmentObject(battery)
        }
    }
}

/// Theme, orientation and the app's life around the dashboard.
struct RootView: View {
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var trip: TripStore
    @EnvironmentObject private var dashboard: DashboardModel
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.scenePhase) private var phase

    var body: some View {
        let colors = AppColors.palette(settings.themeMode, systemDark: systemScheme == .dark).withAccent(settings.accent)
        DashboardView()
            .padding(.horizontal, 2)
            .ignoresSafeArea(.container, edges: .bottom)
            .background(colors.bg.ignoresSafeArea())
            .environment(\.appColors, colors)
            .font(.geist(16))
            .tint(colors.accent)
            .preferredColorScheme(settings.themeMode == .dark ? .dark : settings.themeMode == .light ? .light : nil)
            .statusBarHidden()
            .persistentSystemOverlays(.hidden)
            .sheet(isPresented: $dashboard.showSettings) { sheet(colors) { SettingsSheet() } }
            .sheet(isPresented: $dashboard.showLayouts) { sheet(colors) { LayoutSwitcher() } }
            .sheet(item: $dashboard.pickerFor) { pane in sheet(colors) { PanePicker(slot: pane) } }
            .onAppear { applyOrientation(settings.orientationMode) }
            .onChange(of: settings.orientationMode) { _, m in applyOrientation(m) }
            .onChange(of: phase, initial: true) { _, p in
                if p == .active { trip.appActive() } else if p == .background { trip.appInactive() }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willTerminateNotification)) { _ in
                trip.appTerminating()
            }
    }

    private func sheet<C: View>(_ colors: AppColors, @ViewBuilder _ content: () -> C) -> some View {
        content()
            .environment(\.appColors, colors)
            .environmentObject(settings).environmentObject(trip).environmentObject(dashboard)
            .font(.geist(16))
            .tint(colors.accent)
            .preferredColorScheme(colors.isDark ? .dark : .light)
            .presentationDetents([.large])
            .presentationBackground(colors.surface)
    }
}

extension Pane: Identifiable { var id: String { rawValue } }

extension OrientationMode {
    /// Landscape and Portrait are fixed (both landscape sides, like Android's sensor-landscape); Auto turns with the
    /// phone, and iOS keeps it still when the rider's rotation lock is on.
    var mask: UIInterfaceOrientationMask {
        switch self {
        case .landscape: .landscape
        case .portrait: .portrait
        case .auto: .allButUpsideDown
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    static var orientationMask: UIInterfaceOrientationMask = .landscape

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        Self.orientationMask
    }
}

@MainActor
func applyOrientation(_ mode: OrientationMode) {
    AppDelegate.orientationMask = mode.mask
    for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
        for w in scene.windows { w.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations() }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mode.mask)) { _ in }
    }
}
