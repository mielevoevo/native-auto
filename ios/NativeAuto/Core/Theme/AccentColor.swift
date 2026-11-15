import Foundation

/// An sRGB colour, channels 0...1. Pure so the accent maths is unit-tested (Android `ui/theme/AccentColor.kt`).
struct RGB: Equatable, Hashable {
    var r: Double, g: Double, b: Double

    init(r: Double, g: Double, b: Double) { self.r = r; self.g = g; self.b = b }

    /// From 0xRRGGBB (an alpha byte above is ignored, so Android's ARGB ints work too).
    init(hex: UInt32) {
        r = Double((hex >> 16) & 0xFF) / 255
        g = Double((hex >> 8) & 0xFF) / 255
        b = Double(hex & 0xFF) / 255
    }

    static let black = RGB(hex: 0x000000)
    static let white = RGB(hex: 0xFFFFFF)

    private static func byte(_ v: Double) -> UInt32 { UInt32((v * 255).rounded()) & 0xFF }

    /// 0xFFRRGGBB, as Android stores it.
    var argb: UInt32 { 0xFF00_0000 | (Self.byte(r) << 16) | (Self.byte(g) << 8) | Self.byte(b) }

    /// `#RRGGBB`, upper case.
    var hexString: String { String(format: "#%02X%02X%02X", Self.byte(r), Self.byte(g), Self.byte(b)) }

    private static func channel(_ c: Double) -> Double { c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }

    /// WCAG relative luminance.
    var luminance: Double { 0.2126 * Self.channel(r) + 0.7152 * Self.channel(g) + 0.0722 * Self.channel(b) }
}

struct AccentPreset: Equatable {
    var key: String, label: String, dark: RGB, light: RGB
}

let accentPresets: [AccentPreset] = [
    AccentPreset(key: "green", label: "Green", dark: RGB(hex: 0x3DDC84), light: RGB(hex: 0x0B7A3E)),
    AccentPreset(key: "cyan", label: "Cyan", dark: RGB(hex: 0x3FC8E0), light: RGB(hex: 0x0A86A0)),
    AccentPreset(key: "ice", label: "Ice blue", dark: RGB(hex: 0x8CC8FF), light: RGB(hex: 0x1F6FB2)),
    AccentPreset(key: "violet", label: "Violet", dark: RGB(hex: 0xB794F6), light: RGB(hex: 0x6B46C1)),
    AccentPreset(key: "magenta", label: "Magenta", dark: RGB(hex: 0xFF6BCB), light: RGB(hex: 0xB0187E)),
]

/// Every install starts cyan (4.1.1).
let defaultAccent = accentPresets.first { $0.key == "cyan" }!

/// `#RRGGBB`, `RRGGBB` or `#RGB`, any case, spaces around ignored. Nil when it isn't a colour.
func parseHexColor(_ text: String) -> RGB? {
    var t = text.trimmingCharacters(in: .whitespaces)
    if t.hasPrefix("#") { t.removeFirst() }
    let full: String
    switch t.count {
    case 3: full = t.map { "\($0)\($0)" }.joined()
    case 6: full = t
    default: return nil
    }
    guard full.allSatisfy(\.isHexDigit), let v = UInt32(full, radix: 16) else { return nil }
    return RGB(hex: v)
}

/// WCAG contrast ratio, 1...21.
func contrastRatio(_ a: RGB, _ b: RGB) -> Double {
    let la = a.luminance, lb = b.luminance
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/// Below this the accent is hard to see (WCAG's 3:1 for UI parts).
let minAccentContrast = 3.0
/// What the accent needs on white to read as text.
let lightTargetContrast = 4.5

/// The accent for the light theme: blended towards black in small steps until it reads on white.
func lightVariant(_ dark: RGB, white: RGB = .white) -> RGB {
    var c = dark, step = 0
    while contrastRatio(c, white) < lightTargetContrast && step < 40 {
        step += 1
        let t = Double(Float(step) * 0.025)
        c = RGB(r: dark.r * (1 - t), g: dark.g * (1 - t), b: dark.b * (1 - t))
    }
    return c
}

/// The accent in the dark theme: a colour too dark for black is lightened towards white until it is visible.
func darkVariant(_ chosen: RGB, black: RGB = .black) -> RGB {
    var c = chosen, step = 0
    while contrastRatio(c, black) < minAccentContrast && step < 40 {
        step += 1
        let t = Double(Float(step) * 0.025)
        c = RGB(r: chosen.r + (1 - chosen.r) * t, g: chosen.g + (1 - chosen.g) * t, b: chosen.b + (1 - chosen.b) * t)
    }
    return c
}

/// Warnings for the hex field (empty when the colour reads well on black and on white).
func accentWarnings(_ color: RGB) -> [String] {
    var w: [String] = []
    if contrastRatio(color, .black) < minAccentContrast { w.append("Hard to see on black: the dark theme will lighten it.") }
    if contrastRatio(color, .white) < minAccentContrast { w.append("Hard to see on white: the light theme will darken it.") }
    return w
}

/// The colour a stored accent draws with in each theme. A preset keeps its hand-picked light variant.
func accentFor(_ chosen: RGB, isDark: Bool) -> RGB {
    if isDark { return darkVariant(chosen) }
    return accentPresets.first { $0.dark.argb == chosen.argb }?.light ?? lightVariant(chosen)
}
