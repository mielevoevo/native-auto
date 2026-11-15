import SwiftUI
import UIKit

/// The app's colour tokens (Rider-Feedback §11, Android `ui/theme/Color.kt`). Monochrome base, navy for "selected",
/// one accent per card, amber and red for status only.
struct AppColors: Equatable {
    var isDark: Bool
    var bg: Color, surface: Color, card: Color, outline: Color
    var text: Color, textDim: Color
    var button: Color, onButton: Color, buttonQuiet: Color, onButtonQuiet: Color
    var navy: Color, onNavy: Color
    var accent: Color, amber: Color, red: Color

    static let oledDark = AppColors(
        isDark: true,
        bg: Color(hex: 0x000000), surface: Color(hex: 0x0A0C10), card: Color(hex: 0x000000), outline: Color(hex: 0x262A33),
        text: Color(hex: 0xE8EAED), textDim: Color(hex: 0x8A9099),
        button: Color(hex: 0xE8EAED), onButton: Color(hex: 0x000000), buttonQuiet: Color(hex: 0x1A1D23), onButtonQuiet: Color(hex: 0xE8EAED),
        navy: Color(hex: 0x0F2A4A), onNavy: Color(hex: 0xE8EAED),
        accent: Color(hex: 0x3FC8E0), amber: Color(hex: 0xF2A93B), red: Color(hex: 0xE5484D))

    static let light = AppColors(
        isDark: false,
        bg: Color(hex: 0xF1F2F4), surface: Color(hex: 0xFFFFFF), card: Color(hex: 0xFFFFFF), outline: Color(hex: 0xD3D7DE),
        text: Color(hex: 0x14171C), textDim: Color(hex: 0x5E6570),
        button: Color(hex: 0x14171C), onButton: Color(hex: 0xFFFFFF), buttonQuiet: Color(hex: 0xE6E8EC), onButtonQuiet: Color(hex: 0x14171C),
        navy: Color(hex: 0xD5E3F5), onNavy: Color(hex: 0x0F2A4A),
        accent: Color(hex: 0x0A86A0), amber: Color(hex: 0xB86E00), red: Color(hex: 0xC62828))

    /// These colours with the rider's accent: a preset keeps its light variant, anything else is adjusted.
    func withAccent(_ chosen: RGB) -> AppColors {
        var c = self
        c.accent = Color(accentFor(chosen, isDark: isDark))
        return c
    }

    static func palette(_ mode: ThemeMode, systemDark: Bool) -> AppColors {
        switch mode {
        case .dark: .oledDark
        case .light: .light
        case .system: systemDark ? .oledDark : .light
        }
    }
}

extension Color {
    init(hex: UInt32) { self.init(RGB(hex: hex)) }
    init(_ rgb: RGB) { self.init(.sRGB, red: rgb.r, green: rgb.g, blue: rgb.b, opacity: 1) }
}

private struct AppColorsKey: EnvironmentKey { static let defaultValue = AppColors.oledDark }

extension EnvironmentValues {
    var appColors: AppColors {
        get { self[AppColorsKey.self] }
        set { self[AppColorsKey.self] = newValue }
    }
}

/// Geist (Vercel, OFL), bundled like on Android. Fixed sizes: a card's content follows its slot, not Dynamic Type.
extension Font {
    static func geist(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        let name = switch weight {
        case .bold, .heavy, .black: "Geist-Bold"
        case .semibold: "Geist-SemiBold"
        case .medium: "Geist-Medium"
        default: "Geist-Regular"
        }
        return .custom(name, fixedSize: size)
    }
}

/// Haptics (Android `Haptics.kt`): a tick for toggles and choices, a thump for a lift, a double for armed.
enum Haptics {
    static func toggle() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func tap() { UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 0.6) }
    static func tick() { UISelectionFeedbackGenerator().selectionChanged() }
    static func lift() { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
    static func armed() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func confirm() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
}
