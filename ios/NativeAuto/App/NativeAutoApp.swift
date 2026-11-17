import SwiftUI
import UIKit

@main
struct NativeAutoApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var settings: SettingsStore
    @StateObject private var trip: TripStore
    @StateObject private var dashboard: DashboardModel
    @StateObject private var battery = BatteryMonitor()
    @StateObject private var navigator: Navigator
    @StateObject private var map = MapStore()
    @StateObject private var garage: GarageStore
    @StateObject private var history: HistoryStore
    @StateObject private var media = MediaStore()
    @StateObject private var call: CallStore
    @StateObject private var appStart: AppStart

    init() {
        #if DEBUG
        // UI tests and screenshots: `-reset_data 1` starts from an empty history and Garage.
        if UserDefaults.standard.bool(forKey: "reset_data") {
            AppDatabase.deleteStore()
            FileTripCheckpoints().clear()
        }
        #endif
        // 4.2: decided before anything writes a preference (an empty store = a new install = first-run pages).
        _appStart = StateObject(wrappedValue: AppStart())
        let s = SettingsStore()
        _settings = StateObject(wrappedValue: s)
        let t = TripStore(settings: s)
        _trip = StateObject(wrappedValue: t)
        _call = StateObject(wrappedValue: CallStore(settings: s))
        // 15d: one database for the history and the Garage; the Garage store first, so the recorder can tag trips.
        let db = AppDatabase()
        let g = GarageStore(db: db, trip: t)
        _garage = StateObject(wrappedValue: g)
        _history = StateObject(wrappedValue: HistoryStore(db: db, trip: t, garage: g))
        _navigator = StateObject(wrappedValue: Navigator(settings: s, trip: t))
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
                .environmentObject(navigator)
                .environmentObject(map)
                .environmentObject(garage)
                .environmentObject(history)
                .environmentObject(media)
                .environmentObject(call)
                .environmentObject(appStart)
        }
    }
}

/// Theme, orientation and the app's life around the dashboard.
struct RootView: View {
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var trip: TripStore
    @EnvironmentObject private var dashboard: DashboardModel
    @EnvironmentObject private var navigator: Navigator
    @EnvironmentObject private var map: MapStore
    @EnvironmentObject private var garage: GarageStore
    @EnvironmentObject private var history: HistoryStore
    @EnvironmentObject private var media: MediaStore
    @EnvironmentObject private var call: CallStore
    @EnvironmentObject private var appStart: AppStart
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.scenePhase) private var phase

    var body: some View {
        let colors = AppColors.palette(settings.themeMode, systemDark: systemScheme == .dark).withAccent(settings.accent)
        Group {
            if appStart.setupPending {
                SetupScreen().transition(.opacity)
            } else {
                DashboardView().padding(.horizontal, 2)
            }
        }
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
            .sheet(isPresented: $dashboard.showSearch) { sheet(colors) { DestinationSearchSheet() } }
            // 13a: what's new after an update, never while a trip runs / is paused or navigation is on.
            .sheet(isPresented: Binding(get: { showChangelog },
                                        set: { if !$0 { appStart.changelogSeen() } })) {
                sheet(colors) { ChangelogSheet(entries: appStart.newEntries, title: "What's new in \(appStart.newEntries.first?.versionName ?? "")") { appStart.changelogSeen() } }
            }
            .fullScreenCover(isPresented: $dashboard.showHistory) { screen(colors) { HistoryScreen() } }
            .fullScreenCover(isPresented: $dashboard.showGarage) { screen(colors) { GarageScreen() } }
            // 11a: "Gen 2's odometer now?" after days away (and "Which bike?" in debug builds).
            .sheet(item: Binding(get: { dashboard.screenOver ? nil : garage.prompt.map(PromptBox.init) },
                                 set: { if $0 == nil, garage.prompt != nil { garage.dismissPrompt() } })) { box in
                sheet(colors) { GaragePromptSheet(prompt: box.prompt) }
            }
            .overlay(alignment: .top) {
                if let status = navigator.linkStatus {
                    LinkStatusPill(status: status).padding(.top, 8).transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.easeOut(duration: 0.2), value: navigator.linkStatus)
            // 14b: a call over a fullscreen card (the card never takes it) shows as a pill at the top.
            .overlay(alignment: .top) {
                if call.active, !dashboard.layout.isOnScreen(.call) {
                    CallPill().padding(.top, 8).transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.easeOut(duration: 0.2), value: call.active)
            // 13b: "Trip started" after an auto-start (a tap or a swipe up dismisses it).
            .overlay(alignment: .top) {
                if trip.autoStarted {
                    Text("Trip started · riding detected").font(.geist(16, .semibold)).foregroundStyle(colors.text)
                        .padding(.horizontal, 18).frame(minHeight: 44)
                        .background(colors.surface, in: Capsule()).overlay(Capsule().strokeBorder(colors.accent, lineWidth: 1))
                        .padding(.top, 8)
                        .onTapGesture { trip.dismissAutoStartBanner() }
                        .gesture(DragGesture(minimumDistance: 10).onEnded { _ in trip.dismissAutoStartBanner() })
                        .accessibilityIdentifier("auto_start_pill")
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.easeOut(duration: 0.2), value: trip.autoStarted)
            .onChange(of: call.active, initial: true) { _, on in dashboard.callChanged(active: on) }
            .onChange(of: navigator.state.isNavigating) { _, on in
                dashboard.navigationChanged(active: on, autoLayout: settings.autoLayout)
            }
            // 10b: `geo:` links, and `nativeauto://open?text=…` (what a share extension or a Shortcut hands over).
            .onOpenURL { url in
                if let text = incomingLinkText(url) { navigator.openLink(text) }
            }
            #if DEBUG
            // Screenshots: `-debug_start_nav 1` presses Start as soon as a preview arrives.
            .onChange(of: navigator.state) { _, s in
                if case .preview = s, UserDefaults.standard.bool(forKey: "debug_start_nav") { navigator.start() }
            }
            // Simulator checks and UI tests: `-debug_open_link "geo:…"` opens a link at launch, as if shared.
            .task {
                // Screenshots: `-debug_open_screen GARAGE|HISTORY` opens that screen at launch.
                switch UserDefaults.standard.string(forKey: "debug_open_screen") {
                case "GARAGE": dashboard.showGarage = true
                case "HISTORY": dashboard.showHistory = true
                case "SETTINGS": dashboard.showSettings = true
                default: break
                }
                if let link = UserDefaults.standard.string(forKey: "debug_open_link"), !link.isEmpty {
                    try? await Task.sleep(nanoseconds: 1_500_000_000) // a first fix to route from
                    navigator.openLink(link)
                }
            }
            #endif
            .onAppear { applyOrientation(settings.orientationMode) }
            .onChange(of: settings.orientationMode) { _, m in applyOrientation(m) }
            .onChange(of: phase, initial: true) { _, p in
                if p == .active {
                    // 11b: a trip lost with the process is resumed or saved, before anything else starts one.
                    history.recover()
                    trip.appActive()
                } else if p == .background { trip.appInactive() }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willTerminateNotification)) { _ in
                trip.appTerminating()
            }
    }

    private var showChangelog: Bool {
        !appStart.setupPending && !appStart.newEntries.isEmpty && !dashboard.showSettings && !dashboard.screenOver
            && (trip.state == .idle || trip.state == .ended) && !navigator.state.isNavigating
    }

    /// A full screen over the dashboard (history, Garage): the same environment as a sheet, the app's own background.
    private func screen<C: View>(_ colors: AppColors, @ViewBuilder _ content: () -> C) -> some View {
        content()
            .environment(\.appColors, colors)
            .environmentObject(settings).environmentObject(trip).environmentObject(dashboard)
            .environmentObject(navigator).environmentObject(map)
            .environmentObject(garage).environmentObject(history)
            .environmentObject(media).environmentObject(call).environmentObject(appStart)
            .font(.geist(16))
            .tint(colors.accent)
            .preferredColorScheme(colors.isDark ? .dark : .light)
            .statusBarHidden()
    }

    private func sheet<C: View>(_ colors: AppColors, @ViewBuilder _ content: () -> C) -> some View {
        content()
            .environment(\.appColors, colors)
            .environmentObject(settings).environmentObject(trip).environmentObject(dashboard)
            .environmentObject(navigator).environmentObject(map)
            .environmentObject(garage).environmentObject(history)
            .environmentObject(media).environmentObject(call).environmentObject(appStart)
            .font(.geist(16))
            .tint(colors.accent)
            .preferredColorScheme(colors.isDark ? .dark : .light)
            .presentationDetents([.large])
            .presentationBackground(colors.surface)
    }
}

extension Pane: Identifiable { var id: String { rawValue } }

/// A Garage prompt as a sheet item.
struct PromptBox: Identifiable {
    let prompt: GaragePrompt
    var id: String {
        switch prompt {
        case .odometerNow(let b, _): "odo-\(b.id)"
        case .whichBike: "which"
        }
    }
}

/// The text to resolve from a URL opened in the app: a `geo:` URI as it is, or the `text` / `url` of
/// `nativeauto://open?...`. Nil for anything else.
func incomingLinkText(_ url: URL) -> String? {
    switch url.scheme?.lowercased() {
    case "geo": return url.absoluteString
    case "nativeauto":
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return items.first { $0.name == "text" || $0.name == "url" }?.value.flatMap { $0.isEmpty ? nil : $0 }
    default: return nil
    }
}

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
