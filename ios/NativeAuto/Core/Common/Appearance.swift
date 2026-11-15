import Foundation

/// Theme setting (8c). OLED dark is the default: a dashboard is mostly used at night.
enum ThemeMode: String, CaseIterable {
    case dark = "DARK", light = "LIGHT", system = "SYSTEM"
    var label: String { switch self { case .dark: "Dark"; case .light: "Light"; case .system: "System" } }
}

/// The clock pane's face (8c), chosen in the pane itself.
enum ClockFace: String, CaseIterable {
    case digital = "DIGITAL", analog = "ANALOG"
    var label: String { self == .digital ? "Digital" : "Analog" }
}

/// Which way the dashboard faces (14b). Landscape and Portrait are fixed; Auto follows the phone (and so the
/// system's rotation lock, which iOS applies by itself).
enum OrientationMode: String, CaseIterable {
    case landscape = "LANDSCAPE", portrait = "PORTRAIT", auto = "AUTO"
    var label: String { switch self { case .landscape: "Landscape"; case .portrait: "Portrait"; case .auto: "Auto" } }
}
