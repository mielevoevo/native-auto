import SwiftUI
import UniformTypeIdentifiers

/// The settings' pages (13c, order of 4.1.1): Trip and Garage first, where the sheet opens.
enum SettingsCategory: String, CaseIterable, Identifiable {
    case tripGarage = "TRIP_GARAGE", display = "DISPLAY", layout = "LAYOUT", navigation = "NAVIGATION", audio = "AUDIO"
    case backup = "BACKUP", privacy = "PRIVACY", debug = "DEBUG"

    var id: String { rawValue }
    var label: String {
        switch self {
        case .tripGarage: "Trip and Garage"; case .display: "Display"; case .layout: "Layout"; case .navigation: "Navigation"
        case .audio: "Audio"; case .backup: "Backup"; case .privacy: "Privacy and data"; case .debug: "Debug"
        }
    }

    static var shown: [SettingsCategory] {
        #if DEBUG
        allCases
        #else
        allCases.filter { $0 != .debug }
        #endif
    }
}

/// Settings in categories (13c): the category row on top, each page two short columns on a wide screen, one when narrow.
struct SettingsSheet: View {
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var trip: TripStore
    @EnvironmentObject private var dashboard: DashboardModel
    @EnvironmentObject private var garage: GarageStore
    @EnvironmentObject private var history: HistoryStore
    @Environment(\.appColors) private var colors
    @Environment(\.dismiss) private var dismiss
    @State private var category = SettingsCategory.tripGarage
    @State private var hex = ""
    @State private var routeHex = ""
    @State private var share: SharedFile?
    @State private var importing = false
    @State private var resetArmed = false
    @State private var showChangelog = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Settings").font(.geist(20, .semibold)).foregroundStyle(colors.text)
                Spacer()
                Button("Done") { dismiss() }
                    .font(.geist(17, .semibold)).foregroundStyle(colors.accent)
                    .frame(minWidth: 56, minHeight: 48)
            }
            .padding(.horizontal, 20).padding(.top, 12)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(SettingsCategory.shown) { c in
                        Chip(title: c.label, on: c == category) { category = c }.accessibilityIdentifier("settings_cat_\(c.rawValue)")
                    }
                }
                .padding(.horizontal, 20)
            }
            .padding(.vertical, 8)
            ScrollView {
                page(category)
                    .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 24)
                    .frame(maxWidth: 1000, alignment: .leading)
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("settings_page_\(category.rawValue)")
            }
        }
        .background(colors.surface.ignoresSafeArea())
        .onAppear {
            hex = settings.accent.hexString
            routeHex = settings.route?.hexString ?? ""
            history.message = nil
            #if DEBUG
            // UI tests: `-settings_category DISPLAY` opens that page.
            if let c = UserDefaults.standard.string(forKey: "settings_category").flatMap(SettingsCategory.init) { category = c }
            #endif
        }
        .sheet(item: $share) { ShareSheet(url: $0.url).presentationDetents([.medium, .large]) }
        .sheet(isPresented: $showChangelog) {
            ChangelogSheet(entries: Changelog.shipped, title: "What's new").environment(\.appColors, colors)
                .presentationBackground(colors.surface)
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.zip]) { result in
            if case .success(let url) = result { history.importBackup(url) }
        }
    }

    // MARK: Pages

    @ViewBuilder private func page(_ c: SettingsCategory) -> some View {
        switch c {
        case .tripGarage: columns { tripLeft } right: { tripRight }
        case .display: columns { displayLeft } right: { colours }
        case .layout:
            columns {
                row("Orientation") { Segments(options: OrientationMode.allCases, selection: $settings.orientationMode) { $0.label } }
            } right: {
                row("Hold to move a card") { Segments(options: PaneDrag.holdChoicesMs, selection: $settings.dragHoldMs) { "\($0 / 1000) s" } }
            }
        case .navigation:
            columns {
                row("Speed unit") {
                    Segments(options: SpeedUnit.allCases, selection: $settings.speedUnit) { $0.speedLabel }
                        .onChange(of: settings.speedUnit) { _, _ in trip.unitChanged() }
                }
            } right: {
                switchRow("Auto layout: show the map when navigation starts", $settings.autoLayout, id: "settings_auto_layout")
            }
        case .audio:
            columns {
                switchRow("Voice guidance (English)", $settings.voiceGuidance, id: "settings_voice")
            } right: {
                switchRow("Call card while a call is on", $settings.callControls, id: "settings_call_controls")
                note("iOS tells an app only that a call rings or is on: answer and end it with the phone or the headset's button.")
            }
        case .backup: columns { backup } right: { EmptyView() }
        case .privacy: columns { privacyLeft } right: { privacyRight }
        case .debug:
            columns {
                switchRow("Fake GPS (scripted drive; resets the trip)", Binding(get: { settings.useFakeGps }, set: { trip.setFakeGps($0) }), id: "settings_fake_gps")
                switchRow("Simulate navigation (drives the planned route)", $settings.simulateNavigation, id: "settings_simulate_nav")
            } right: { EmptyView() }
        }
    }

    @ViewBuilder private var tripLeft: some View {
        BigButton(title: garage.active.map { "Garage · \($0.name)" } ?? "Garage", icon: "scooter", style: .quiet) {
            open { dashboard.showGarage = true }
        }
        .accessibilityIdentifier("settings_garage")
        VStack(alignment: .leading, spacing: 6) {
            BigButton(title: trip.resetArmed ? "Tap again to reset" : "Reset trip", icon: "arrow.counterclockwise",
                      style: trip.resetArmed ? .danger : .quiet) { trip.tapReset() }
                .accessibilityIdentifier("settings_reset_trip")
            note("Drops the trip in progress without saving it.")
        }
        row("Total on the trip log") { Segments(options: TotalPeriod.allCases, selection: $settings.totalPeriod) { $0.label } }
        row("Figures on the log face") { figures }
    }

    @ViewBuilder private var tripRight: some View {
        BigButton(title: "Trip history", icon: "clock.arrow.circlepath", style: .quiet) { open { dashboard.showHistory = true } }
            .accessibilityIdentifier("settings_history")
        row("Auto-pause after no movement") {
            Segments(options: AutoTripConfig.pauseChoicesMin, selection: $settings.autoPauseMin) { $0 == 0 ? "Off" : "\($0) min" }
        }
        row("Auto-end after a pause of") {
            Segments(options: AutoTripConfig.endChoicesMin, selection: $settings.autoEndMin) { $0 == 0 ? "Off" : "\($0) min" }
        }
        switchRow("Auto-start: begin a trip when riding", $settings.autoStart, id: "settings_auto_start")
        if settings.autoStart {
            row("Start after") {
                Segments(options: AutoStartConfig.holdChoicesS, selection: $settings.autoStartHoldS) { "\($0) s" }
            }
            note("Above 15 km/h for that long, while the app is open.")
        }
    }

    @ViewBuilder private var displayLeft: some View {
        row("Theme") { Segments(options: ThemeMode.allCases, selection: $settings.themeMode) { $0.label } }
        switchRow("Battery in the corner", $settings.showBattery, id: "settings_battery")
        BigButton(title: "What's new", icon: "sparkles", style: .quiet) { showChangelog = true }
            .accessibilityIdentifier("settings_whats_new")
    }

    @ViewBuilder private var colours: some View {
        row("Accent") {
            swatches(selected: settings.accentArgb, idPrefix: "accent_preset") { settings.accentArgb = $0.argb; hex = $0.hexString }
            hexField(text: $hex, id: "accent_hex") { settings.accentArgb = $0.argb }
        }
        row("Route line") {
            switchRow("Follow the accent", Binding(get: { settings.routeArgb == nil }, set: { follow in
                settings.routeArgb = follow ? nil : settings.accentArgb
                routeHex = follow ? "" : settings.accent.hexString
            }), id: "route_follow")
            if settings.routeArgb != nil {
                swatches(selected: settings.routeArgb ?? 0, idPrefix: "route_preset") { settings.routeArgb = $0.argb; routeHex = $0.hexString }
                hexField(text: $routeHex, id: "route_hex") { settings.routeArgb = $0.argb }
            }
        }
    }

    @ViewBuilder private var backup: some View {
        note("Every trip with its full track, and the Garage, in one ZIP. The Android app reads the same file, and this one reads Android's.")
        HStack(spacing: 12) {
            BigButton(title: "Back up", icon: "square.and.arrow.up", style: .quiet) { share = history.backupFile().map(SharedFile.init) }
                .accessibilityIdentifier("settings_backup")
            BigButton(title: "Import", icon: "square.and.arrow.down", style: .quiet) { importing = true }
                .accessibilityIdentifier("settings_import")
        }
        if let m = history.message { note(m).accessibilityIdentifier("settings_backup_message") }
    }

    @ViewBuilder private var privacyLeft: some View {
        note("Location, Apple Music and other permissions are in the iPhone's Settings for Native Auto.")
        BigButton(title: "Open iPhone Settings", icon: "gear", style: .quiet) {
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        }
        .accessibilityIdentifier("settings_privacy_permissions")
        note("Trips, the Garage and settings stay on this phone. Addresses are looked up with Apple's geocoder (Photon if it has none); routes come from FOSSGIS Valhalla and search from Photon.")
    }

    @ViewBuilder private var privacyRight: some View {
        BigButton(title: resetArmed ? "Tap again: delete everything" : "Reset app", icon: "trash", style: resetArmed ? .danger : .quiet) {
            if resetArmed {
                Haptics.confirm()
                AppReset.run()
            } else {
                Haptics.tap(); resetArmed = true
                Task { try? await Task.sleep(nanoseconds: 3_000_000_000); resetArmed = false }
            }
        }
        .accessibilityIdentifier("settings_reset_app")
        note("Deletes every trip, the Garage and all settings, then closes the app. The next start shows the first-run pages. Back up first if you may want them.")
    }

    // MARK: Pieces

    /// Two columns at 600 pt and wider, one below.
    private func columns<L: View, R: View>(@ViewBuilder _ left: () -> L, @ViewBuilder right: () -> R) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 32) {
                VStack(alignment: .leading, spacing: 20) { left() }.frame(minWidth: 280, maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 20) { right() }.frame(minWidth: 280, maxWidth: .infinity, alignment: .leading)
            }
            VStack(alignment: .leading, spacing: 20) { left(); right() }
        }
    }

    private func swatches(selected: UInt32, idPrefix: String, _ pick: @escaping (RGB) -> Void) -> some View {
        HStack(spacing: 12) {
            ForEach(accentPresets, id: \.key) { p in
                let on = p.dark.argb == selected
                Button { Haptics.tick(); pick(p.dark) } label: {
                    Circle().fill(Color(colors.isDark ? p.dark : p.light))
                        .frame(width: 40, height: 40)
                        .overlay(Circle().strokeBorder(colors.text, lineWidth: on ? 3 : 0).padding(-5))
                        .frame(width: 52, height: 52)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(p.label)
                .accessibilityIdentifier("\(idPrefix)_\(p.key)")
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }

    /// A hex field: applies only a full six-digit colour; warns when it's hard to see on black or white.
    private func hexField(text: Binding<String>, id: String, apply: @escaping (RGB) -> Void) -> some View {
        let submit = {
            let t = text.wrappedValue.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: "")
            if t.count == 6, let c = parseHexColor(t) { apply(c); text.wrappedValue = c.hexString }
        }
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                TextField("#RRGGBB", text: text)
                    .font(.geist(17)).foregroundStyle(colors.text)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled()
                    .padding(.horizontal, 14).frame(width: 160, height: 48)
                    .background(colors.buttonQuiet, in: RoundedRectangle(cornerRadius: 12))
                    .onSubmit(submit)
                    .accessibilityIdentifier(id)
                Button("Apply", action: submit).font(.geist(16, .semibold)).foregroundStyle(colors.accent).frame(minHeight: 48)
            }
            if let c = parseHexColor(text.wrappedValue) {
                // Presets carry their own light variant; only a typed colour can be hard to see.
                ForEach(accentPresets.contains { $0.dark.argb == c.argb } ? [] : accentWarnings(c), id: \.self) { Text($0).font(.geist(13)).foregroundStyle(colors.amber) }
            } else if !text.wrappedValue.isEmpty {
                Text("Not a colour: use six hex digits, like #3FC8E0.").font(.geist(13)).foregroundStyle(colors.red)
                    .accessibilityIdentifier("\(id)_error")
            }
        }
    }

    private var figures: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 10)], alignment: .leading, spacing: 10) {
            ForEach(TelemetryStat.allCases, id: \.self) { s in
                let on = !settings.hiddenStats.contains(s)
                Button {
                    Haptics.tick()
                    if on { settings.hiddenStats.insert(s) } else { settings.hiddenStats.remove(s) }
                } label: {
                    HStack(spacing: 6) {
                        if on { Image(systemName: "checkmark").font(.system(size: 13, weight: .semibold)) }
                        Text(s.label).font(.geist(15, .medium))
                    }
                    .foregroundStyle(on ? colors.onNavy : colors.text)
                    .padding(.horizontal, 14).frame(minHeight: 48)
                    .background(on ? colors.navy : colors.buttonQuiet, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("settings_stat_\(s.key)")
            }
        }
    }

    private func row<C: View>(_ label: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label).font(.geist(16, .medium)).foregroundStyle(colors.text)
            content()
        }
    }

    private func switchRow(_ label: String, _ on: Binding<Bool>, id: String) -> some View {
        Toggle(isOn: Binding(get: { on.wrappedValue }, set: { Haptics.toggle(); on.wrappedValue = $0 })) {
            Text(label).font(.geist(16, .medium)).foregroundStyle(colors.text)
        }
        .tint(colors.navy)
        .frame(minHeight: 56)
        .accessibilityIdentifier(id)
    }

    private func note(_ text: String) -> some View {
        Text(text).font(.geist(14)).foregroundStyle(colors.textDim).fixedSize(horizontal: false, vertical: true)
    }

    /// Closes the sheet, then opens a full screen (one can't be presented while the sheet is still going away).
    private func open(_ f: @escaping () -> Void) {
        Haptics.tap()
        dashboard.showSettings = false
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 450_000_000)
            f()
        }
    }
}

/// "Reset app" (4.1.1): everything the app keeps on the phone is deleted, then it closes. iOS has no
/// `clearApplicationUserData`, so the app deletes its own files and settings and exits; the next start is a first run.
enum AppReset {
    @MainActor static func run() {
        AppDatabase.deleteStore()
        FileTripCheckpoints().clear()
        let fm = FileManager.default
        for dir in [fm.urls(for: .cachesDirectory, in: .userDomainMask)[0], fm.temporaryDirectory] {
            for f in (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] { try? fm.removeItem(at: f) }
        }
        if let id = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: id) }
        UserDefaults.standard.synchronize()
        exit(0)
    }
}
