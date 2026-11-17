import Foundation
import SwiftUI

/// All settings, in `UserDefaults` behind one store (the iOS counterpart of Android's `SettingsRepository`).
/// Keys use Android's DataStore names so the two read alike.
@MainActor
final class SettingsStore: ObservableObject {
    private let d: UserDefaults

    @Published var speedUnit: SpeedUnit { didSet { d.set(speedUnit.rawValue, forKey: "speed_unit") } }
    @Published var themeMode: ThemeMode { didSet { d.set(themeMode.rawValue, forKey: "theme_mode") } }
    /// 0xFFRRGGBB, as Android stores it.
    @Published var accentArgb: UInt32 { didSet { d.set(Int(accentArgb), forKey: "accent_color") } }
    @Published var clockFace: ClockFace { didSet { d.set(clockFace.rawValue, forKey: "clock_face") } }
    @Published var clockSeconds: Bool { didSet { d.set(clockSeconds, forKey: "clock_seconds") } }
    @Published var autoPauseMin: Int { didSet { d.set(autoPauseMin, forKey: "auto_pause_min") } }
    @Published var autoEndMin: Int { didSet { d.set(autoEndMin, forKey: "auto_end_min") } }
    @Published var dragHoldMs: Int64 { didSet { d.set(Int(dragHoldMs), forKey: "drag_hold_ms") } }
    @Published var orientationMode: OrientationMode { didSet { d.set(orientationMode.rawValue, forKey: "orientation_mode") } }
    @Published var showBattery: Bool { didSet { d.set(showBattery, forKey: "show_battery") } }
    @Published var useFakeGps: Bool { didSet { d.set(useFakeGps, forKey: "use_fake_gps") } }
    @Published var hiddenStats: Set<TelemetryStat> { didSet { d.set(Array(hiddenStats.map(\.key)), forKey: "hidden_stats") } }
    // 15c: navigation
    /// Spoken turn instructions; the mute button flips it mid-route.
    @Published var voiceGuidance: Bool { didSet { d.set(voiceGuidance, forKey: "voice_guidance") } }
    /// Show the map when navigation starts (8b auto layout).
    @Published var autoLayout: Bool { didSet { d.set(autoLayout, forKey: "auto_layout") } }
    /// Debug: guidance drives the planned route instead of reading the GPS.
    @Published var simulateNavigation: Bool { didSet { d.set(simulateNavigation, forKey: "simulate_navigation") } }
    /// Places navigated to, newest first (8 kept). Android keeps them in Room; here they are small enough for
    /// `UserDefaults` (JSON).
    @Published var recentPlaces: [Place] { didSet { d.set(try? JSONEncoder().encode(recentPlaces), forKey: "recent_places") } }

    // 15d: history and Garage
    /// The log face's total: today, this week or this month (12b).
    @Published var totalPeriod: TotalPeriod { didSet { d.set(totalPeriod.rawValue, forKey: "total_period") } }
    /// The small Garage card's figure.
    @Published var garageValue: GarageValue { didSet { d.set(garageValue.rawValue, forKey: "garage_value") } }
    @Published var tankIcon: TankIcon { didSet { d.set(tankIcon.rawValue, forKey: "tank_icon") } }

    init(defaults: UserDefaults = .standard) {
        d = defaults
        speedUnit = SpeedUnit(rawValue: d.string(forKey: "speed_unit") ?? "") ?? .kmh
        themeMode = ThemeMode(rawValue: d.string(forKey: "theme_mode") ?? "") ?? .dark
        accentArgb = (d.object(forKey: "accent_color") as? Int).map { UInt32(truncatingIfNeeded: $0) } ?? defaultAccent.dark.argb
        clockFace = ClockFace(rawValue: d.string(forKey: "clock_face") ?? "") ?? .digital
        clockSeconds = d.bool(forKey: "clock_seconds")
        autoPauseMin = d.object(forKey: "auto_pause_min") as? Int ?? AutoTripConfig.defaultPauseMin
        autoEndMin = d.object(forKey: "auto_end_min") as? Int ?? AutoTripConfig.defaultEndMin
        let hold = Int64(d.object(forKey: "drag_hold_ms") as? Int ?? Int(PaneDrag.defaultHoldMs))
        dragHoldMs = PaneDrag.holdChoicesMs.contains(hold) ? hold : PaneDrag.defaultHoldMs
        orientationMode = OrientationMode(rawValue: d.string(forKey: "orientation_mode") ?? "") ?? .landscape
        showBattery = Self.flag(d, "show_battery", default: true)
        useFakeGps = d.bool(forKey: "use_fake_gps")
        hiddenStats = (d.array(forKey: "hidden_stats") as? [String]).map { TelemetryStat.fromKeys(Set($0)) } ?? TelemetryStat.defaultHidden
        voiceGuidance = Self.flag(d, "voice_guidance", default: true)
        autoLayout = Self.flag(d, "auto_layout", default: true)
        simulateNavigation = d.bool(forKey: "simulate_navigation")
        totalPeriod = TotalPeriod(rawValue: d.string(forKey: "total_period") ?? "") ?? .day
        garageValue = GarageValue(rawValue: d.string(forKey: "garage_value") ?? "") ?? .range
        tankIcon = TankIcon(rawValue: d.string(forKey: "tank_icon") ?? "") ?? .gauge
        recentPlaces = d.data(forKey: "recent_places").flatMap { try? JSONDecoder().decode([Place].self, from: $0) } ?? []
    }

    var accent: RGB { RGB(hex: accentArgb) }

    /// A stored switch, or `default` when never set. `bool(forKey:)` also reads launch arguments ("-show_battery 0"
    /// arrives as text, which `as? Bool` misses).
    private static func flag(_ d: UserDefaults, _ key: String, default value: Bool) -> Bool {
        d.object(forKey: key) == nil ? value : d.bool(forKey: key)
    }

    /// The rider's own arrangement (never an auto-layout override), sanitized on the way in.
    var savedLayout: SavedLayout {
        get {
            SavedLayout(bigOnLeft: Self.flag(d, "layout_big_on_left", default: true),
                        split: d.object(forKey: "layout_split") as? Double ?? 0.5,
                        preset: d.string(forKey: "layout_preset") ?? "",
                        slots: d.string(forKey: "layout_slots") ?? "",
                        portraitPreset: d.string(forKey: "layout_preset_portrait") ?? "").sanitized()
        }
        set {
            d.set(newValue.bigOnLeft, forKey: "layout_big_on_left")
            d.set(newValue.split, forKey: "layout_split")
            d.set(newValue.preset, forKey: "layout_preset")
            d.set(newValue.slots, forKey: "layout_slots")
            d.set(newValue.portraitPreset, forKey: "layout_preset_portrait")
        }
    }
}
