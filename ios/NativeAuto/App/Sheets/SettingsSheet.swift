import SwiftUI
import UniformTypeIdentifiers

/// Settings for 15b: display (theme, accent, units, battery), layout (orientation, hold time) and the trip (auto-pause,
/// auto-end, figures, reset). Android's full category sheet (13c) comes with 15e.
struct SettingsSheet: View {
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var trip: TripStore
    @EnvironmentObject private var dashboard: DashboardModel
    @EnvironmentObject private var garage: GarageStore
    @EnvironmentObject private var history: HistoryStore
    @Environment(\.appColors) private var colors
    @State private var hex = ""
    @State private var share: SharedFile?
    @State private var importing = false

    var body: some View {
        SheetScaffold(title: "Settings") {
            VStack(alignment: .leading, spacing: 28) {
                section("Display") {
                    row("Theme") { Segments(options: ThemeMode.allCases, selection: $settings.themeMode) { $0.label } }
                    row("Speed unit") {
                        Segments(options: SpeedUnit.allCases, selection: $settings.speedUnit) { $0.speedLabel }
                            .onChange(of: settings.speedUnit) { _, _ in trip.unitChanged() }
                    }
                    accent
                    switchRow("Battery in the corner", $settings.showBattery)
                }
                section("Layout") {
                    row("Orientation") { Segments(options: OrientationMode.allCases, selection: $settings.orientationMode) { $0.label } }
                    row("Hold to move a card") { Segments(options: PaneDrag.holdChoicesMs, selection: $settings.dragHoldMs) { "\($0 / 1000) s" } }
                }
                section("Navigation") {
                    switchRow("Voice guidance (English)", $settings.voiceGuidance)
                    switchRow("Auto layout: show the map when navigation starts", $settings.autoLayout)
                }
                section("Trip and Garage") {
                    HStack(spacing: 12) {
                        BigButton(title: "Trip history", icon: "clock.arrow.circlepath", style: .quiet) { open { dashboard.showHistory = true } }
                            .accessibilityIdentifier("settings_history")
                        BigButton(title: garage.active.map { "Garage · \($0.name)" } ?? "Garage", icon: "scooter", style: .quiet) {
                            open { dashboard.showGarage = true }
                        }
                        .accessibilityIdentifier("settings_garage")
                    }
                    row("Total on the trip log") {
                        Segments(options: TotalPeriod.allCases, selection: $settings.totalPeriod) { $0.label }
                    }
                    row("Auto-pause after no movement") {
                        Segments(options: AutoTripConfig.pauseChoicesMin, selection: $settings.autoPauseMin) { $0 == 0 ? "Off" : "\($0) min" }
                    }
                    row("Auto-end after a pause of") {
                        Segments(options: AutoTripConfig.endChoicesMin, selection: $settings.autoEndMin) { $0 == 0 ? "Off" : "\($0) min" }
                    }
                    row("Figures on the log face") { figures }
                    VStack(alignment: .leading, spacing: 6) {
                        BigButton(title: trip.resetArmed ? "Tap again to reset" : "Reset trip", icon: "arrow.counterclockwise",
                                  style: trip.resetArmed ? .danger : .quiet) { trip.tapReset() }
                        Text("Drops the trip in progress without saving it.").font(.geist(13)).foregroundStyle(colors.textDim)
                    }
                }
                section("Backup") {
                    Text("Every trip with its full track, and the Garage, in one ZIP. The Android app reads the same file.")
                        .font(.geist(14)).foregroundStyle(colors.textDim)
                    HStack(spacing: 12) {
                        BigButton(title: "Back up", icon: "square.and.arrow.up", style: .quiet) {
                            share = history.backupFile().map(SharedFile.init)
                        }
                        .accessibilityIdentifier("settings_backup")
                        BigButton(title: "Import", icon: "square.and.arrow.down", style: .quiet) { importing = true }
                            .accessibilityIdentifier("settings_import")
                    }
                    if let m = history.message { Text(m).font(.geist(14)).foregroundStyle(colors.textDim).accessibilityIdentifier("settings_backup_message") }
                }
                #if DEBUG
                section("Debug") {
                    switchRow("Fake GPS (scripted drive; resets the trip)", Binding(get: { settings.useFakeGps }, set: { trip.setFakeGps($0) }))
                    switchRow("Simulate navigation (drives the planned route)", $settings.simulateNavigation)
                }
                #endif
                Text("Native Auto iOS \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") (\(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""))")
                    .font(.geist(13)).foregroundStyle(colors.textDim)
            }
            .frame(maxWidth: 720, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .onAppear { hex = settings.accent.hexString; history.message = nil }
        .sheet(item: $share) { ShareSheet(url: $0.url).presentationDetents([.medium, .large]) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.zip]) { result in
            if case .success(let url) = result { history.importBackup(url) }
        }
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

    private var accent: some View {
        row("Accent") {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    ForEach(accentPresets, id: \.key) { p in
                        let on = p.dark.argb == settings.accentArgb
                        Button {
                            Haptics.tick(); settings.accentArgb = p.dark.argb; hex = p.dark.hexString
                        } label: {
                            Circle().fill(Color(colors.isDark ? p.dark : p.light))
                                .frame(width: 40, height: 40)
                                .overlay(Circle().strokeBorder(colors.text, lineWidth: on ? 3 : 0).padding(-5))
                                .frame(width: 52, height: 52)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(p.label)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
                HStack {
                    TextField("#RRGGBB", text: $hex)
                        .font(.geist(17)).foregroundStyle(colors.text)
                        .textInputAutocapitalization(.characters).autocorrectionDisabled()
                        .padding(.horizontal, 14).frame(width: 160, height: 48)
                        .background(colors.buttonQuiet, in: RoundedRectangle(cornerRadius: 12))
                        .onSubmit(applyHex)
                    Button("Apply", action: applyHex).font(.geist(16, .semibold)).foregroundStyle(colors.accent).frame(minHeight: 48)
                }
                if let c = parseHexColor(hex) {
                    ForEach(accentWarnings(c), id: \.self) { Text($0).font(.geist(13)).foregroundStyle(colors.amber) }
                } else if !hex.isEmpty {
                    Text("Not a colour: use six hex digits, like #3FC8E0.").font(.geist(13)).foregroundStyle(colors.red)
                }
            }
        }
    }

    /// The field applies only a full six-digit colour.
    private func applyHex() {
        let t = hex.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: "")
        guard t.count == 6, let c = parseHexColor(t) else { return }
        settings.accentArgb = c.argb
        hex = c.hexString
    }

    private var figures: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 10)], alignment: .leading, spacing: 10) {
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
            }
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title.uppercased()).font(.geist(13, .semibold)).foregroundStyle(colors.textDim)
            content()
        }
    }

    private func row<C: View>(_ label: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label).font(.geist(16, .medium)).foregroundStyle(colors.text)
            content()
        }
    }

    private func switchRow(_ label: String, _ on: Binding<Bool>) -> some View {
        Toggle(isOn: on) { Text(label).font(.geist(16, .medium)).foregroundStyle(colors.text) }
            .tint(colors.accent)
            .frame(minHeight: 48)
    }
}
