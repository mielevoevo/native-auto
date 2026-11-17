import CoreLocation
import MediaPlayer
import SwiftUI

/// The location permission as the setup sees it (asked only when the rider turns it on).
@MainActor
final class LocationAuth: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var status: CLAuthorizationStatus
    private let manager = CLLocationManager()

    override init() {
        status = manager.authorizationStatus
        super.init()
        manager.delegate = self
    }

    var granted: Bool { status == .authorizedWhenInUse || status == .authorizedAlways }

    func request() {
        if status == .notDetermined {
            manager.requestWhenInUseAuthorization()
        } else if let url = URL(string: UIApplication.openSettingsURLString) {
            // Asked before and refused: only the iPhone's Settings can change it now.
            UIApplication.shared.open(url)
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let s = manager.authorizationStatus
        Task { @MainActor in self.status = s }
    }
}

/// 4.2's first-run pages, on a new install (or after Reset app) only. Each switch asks iOS right there; nothing is asked
/// before the rider taps. Everything stays changeable in Settings. Port of Android `SetupScreen`.
struct SetupScreen: View {
    @EnvironmentObject private var start: AppStart
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var garage: GarageStore
    @EnvironmentObject private var media: MediaStore
    @Environment(\.appColors) private var colors
    @StateObject private var location = LocationAuth()
    @State private var page: Int = {
        #if DEBUG
        // Screenshots: `-setup_page N` opens that page.
        return UserDefaults.standard.integer(forKey: "setup_page")
        #else
        return 0
        #endif
    }()
    @State private var askNoLocation = false
    @State private var editingBike = false

    static let pages = 7

    var body: some View {
        GeometryReader { g in
            let portrait = g.size.height > g.size.width
            VStack(spacing: 0) {
                content(portrait: portrait)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .id(page)
                    .transition(.asymmetric(insertion: .opacity.combined(with: .move(edge: .trailing)), removal: .opacity))
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("setup_page_\(page)")
                footer
            }
            .padding(.horizontal, portrait ? 20 : 40).padding(.vertical, 12)
        }
        .background(colors.bg.ignoresSafeArea())
        .alert("Continue without location?", isPresented: $askNoLocation) {
            Button("Go back", role: .cancel) {}.accessibilityIdentifier("setup_confirm_back")
            Button("Continue") { go(page + 1) }.accessibilityIdentifier("setup_confirm_continue")
        } message: {
            Text("Without location there is no speed and the map can't follow you. You can allow it later in the iPhone's Settings.")
        }
        .sheet(isPresented: $editingBike) {
            BikeEditor(bike: nil, onSave: { draft, odo in _ = garage.addBike(draft, odometerKm: odo); editingBike = false })
                .environment(\.appColors, colors).presentationBackground(colors.surface)
        }
    }

    // MARK: Pages

    @ViewBuilder private func content(portrait: Bool) -> some View {
        switch page {
        case 0:
            VStack(spacing: 18) {
                Spacer(minLength: 0)
                Image("LaunchMark").resizable().scaledToFit().frame(width: 96, height: 96).accessibilityHidden(true)
                Text("Native Auto").font(.geist(34, .bold)).foregroundStyle(colors.text)
                Text("Turns your phone on the handlebars into a motorcycle dashboard: speed, map, music, navigation and your garage, all readable at a glance.")
                    .font(.geist(17)).foregroundStyle(colors.textDim).multilineTextAlignment(.center).frame(maxWidth: 560)
                Spacer(minLength: 0)
            }
        case 1:
            twoParts(portrait, "Make it yours", "How the phone sits on the bike and how the app looks. You can change all of this later in Settings.") {
                labelled("Orientation") { Segments(options: OrientationMode.allCases, selection: $settings.orientationMode) { $0.label } }
                    .accessibilityIdentifier("setup_orientation")
                labelled("Theme") { Segments(options: ThemeMode.allCases, selection: $settings.themeMode) { $0.label } }
                    .accessibilityIdentifier("setup_theme")
                labelled("Units") {
                    Segments(options: SpeedUnit.allCases, selection: $settings.speedUnit) { $0 == .kmh ? "Metric (km/h)" : "Imperial (mph)" }
                }
                .accessibilityIdentifier("setup_units")
            }
        case 2:
            twoParts(portrait, "Location", "For your speed, the map and trips, only while the app is open. Native Auto never reads it in the background.") {
                permissionRow("Location while the app is open", on: location.granted, id: "setup_location") { location.request() }
                if location.status == .denied || location.status == .restricted {
                    note("Location was refused before: the switch opens the iPhone's Settings for Native Auto.")
                }
            }
        case 3:
            twoParts(portrait, "Media and calls",
                     "iOS lets an app control Apple Music and see that a call is on. Spotify and other apps play as usual, with the output picker on the card.") {
                SwitchRow(label: "Call card while a call is on", on: $settings.callControls, id: "setup_calls")
                note("The card shows the call's time; answer and end the call with the phone or the headset.")
                permissionRow("Show Apple Music's track", on: media.access == .authorized, id: "setup_music") { media.requestAccess() }
            }
        case 4:
            twoParts(portrait, "Add your bike", "Optional. A bike turns on the odometer, fuel range and service reminders.") {
                ForEach(garage.bikes) { b in
                    HStack {
                        Image(systemName: "scooter").foregroundStyle(colors.textDim)
                        Text(b.name).font(.geist(18, .semibold)).foregroundStyle(colors.text)
                        Text(b.bikeModel.label).font(.geist(15)).foregroundStyle(colors.textDim)
                        Spacer()
                    }
                    .padding(.horizontal, 16).frame(minHeight: 56)
                    .background(colors.surface, in: RoundedRectangle(cornerRadius: 16))
                    .accessibilityIdentifier("setup_bike_\(b.name)")
                }
                BigButton(title: garage.bikes.isEmpty ? "Add bike" : "Add another bike", icon: "plus", style: garage.bikes.isEmpty ? .main : .quiet) {
                    editingBike = true
                }
                .accessibilityIdentifier("setup_add_bike")
                note("You can add more bikes any time in Settings, Trip and Garage, Garage.")
            }
        case 5:
            twoParts(portrait, "Riding", "What the app may do on its own while it is open.") {
                SwitchRow(label: "Auto-start trip", on: $settings.autoStart, id: "setup_auto_start")
                if settings.autoStart {
                    labelled("Start after") { Segments(options: AutoStartConfig.holdChoicesS, selection: $settings.autoStartHoldS) { "\($0) s" } }
                }
                SwitchRow(label: "Auto layout on navigation", on: $settings.autoLayout, id: "setup_auto_layout")
                SwitchRow(label: "Voice guidance", on: $settings.voiceGuidance, id: "setup_voice")
            }
        default:
            twoParts(portrait, "Ready", "What is on and off. Everything is also in Settings.") {
                check("Location", location.granted)
                check("Call card", settings.callControls)
                check("Apple Music track", media.access == .authorized)
                check(garage.bikes.first.map { "Bike: \($0.name)" } ?? "No bike yet", !garage.bikes.isEmpty)
                check("Auto-start trip", settings.autoStart)
                check("Voice guidance", settings.voiceGuidance)
            }
        }
    }

    private var footer: some View {
        HStack(spacing: 12) {
            if page > 0 {
                BigButton(title: "Back", icon: "chevron.backward", style: .quiet) { go(page - 1) }
                    .frame(maxWidth: 200).accessibilityIdentifier("setup_back")
            } else {
                // Keeps the page dots centred on the welcome page.
                BigButton(title: "Back", icon: "chevron.backward", style: .quiet) {}.frame(maxWidth: 200).hidden().accessibilityHidden(true)
            }
            Spacer()
            HStack(spacing: 6) {
                ForEach(0..<Self.pages, id: \.self) { i in
                    Circle().fill(i == page ? colors.text : colors.outline).frame(width: 7, height: 7)
                }
            }
            .accessibilityHidden(true)
            Spacer()
            if page < Self.pages - 1 {
                BigButton(title: "Next", icon: "chevron.forward", style: .main) {
                    if page == 2, !location.granted { askNoLocation = true } else { go(page + 1) }
                }
                .frame(maxWidth: 200).accessibilityIdentifier("setup_next")
            } else {
                BigButton(title: "Start riding", icon: "checkmark", style: .main) { Haptics.confirm(); start.finishSetup() }
                    .frame(maxWidth: 240).accessibilityIdentifier("setup_finish")
            }
        }
    }

    private func go(_ p: Int) {
        Haptics.tap()
        withAnimation(.easeInOut(duration: 0.25)) { page = min(max(p, 0), Self.pages - 1) }
    }

    // MARK: Pieces

    /// Title and text beside the controls in landscape, above them in portrait.
    private func twoParts<C: View>(_ portrait: Bool, _ title: String, _ text: String, @ViewBuilder _ controls: () -> C) -> some View {
        let head = VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.geist(28, .semibold)).foregroundStyle(colors.text)
            Text(text).font(.geist(16)).foregroundStyle(colors.textDim).fixedSize(horizontal: false, vertical: true)
        }
        let body = ScrollView { VStack(alignment: .leading, spacing: 14) { controls() }.padding(.vertical, 4).padding(.horizontal, 4) }
            .scrollBounceBehavior(.basedOnSize)
        return Group {
            if portrait {
                VStack(alignment: .leading, spacing: 20) { head; body }.padding(.top, 24)
            } else {
                HStack(alignment: .top, spacing: 40) {
                    head.frame(maxWidth: .infinity, alignment: .leading)
                    body.frame(maxWidth: .infinity)
                }
                .padding(.top, 16)
            }
        }
    }

    private func labelled<C: View>(_ label: String, @ViewBuilder _ c: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.geist(15, .medium)).foregroundStyle(colors.textDim)
            c()
        }
        .accessibilityElement(children: .contain)
    }

    /// A switch that asks iOS when turned on; once on, it stays on (turning it off is in the iPhone's Settings).
    private func permissionRow(_ label: String, on: Bool, id: String, ask: @escaping () -> Void) -> some View {
        Toggle(isOn: Binding(get: { on }, set: { want in if want != on { Haptics.toggle(); ask() } })) {
            Text(label).font(.geist(17, .medium)).foregroundStyle(colors.text)
        }
        .tint(colors.navy)
        .frame(minHeight: 56)
        .accessibilityIdentifier(id)
    }

    private func check(_ label: String, _ on: Bool) -> some View {
        HStack(spacing: 12) {
            Image(systemName: on ? "checkmark.circle.fill" : "minus.circle").font(.system(size: 20))
                .foregroundStyle(on ? colors.accent : colors.textDim)
            Text(label).font(.geist(17)).foregroundStyle(colors.text)
        }
        .frame(minHeight: 36)
    }

    private func note(_ t: String) -> some View {
        Text(t).font(.geist(14)).foregroundStyle(colors.textDim).fixedSize(horizontal: false, vertical: true)
    }
}
