import SwiftUI
import UIKit

enum CardStyle {
    static let radius: CGFloat = 20
    /// Gloves: the main buttons are at least this tall.
    static let bigButton: CGFloat = 56
    static let headerButton: CGFloat = 44
}

/// A dashboard card: black fill with a 1 pt outline, a header (title, the pane's own buttons, and the status corner
/// when it is the top-right card) and the content. Port of Android `CardChrome` / `HeaderRow`.
struct DashboardCard<Content: View>: View {
    let pane: Pane
    let mode: CardMode
    var title: String? = nil
    @ViewBuilder var content: () -> Content
    @EnvironmentObject private var model: DashboardModel
    @Environment(\.appColors) private var colors

    var body: some View {
        VStack(spacing: 0) {
            header
            content().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(colors.card)
        .clipShape(RoundedRectangle(cornerRadius: CardStyle.radius))
        .overlay(RoundedRectangle(cornerRadius: CardStyle.radius).strokeBorder(colors.outline, lineWidth: 1))
    }

    private var portrait: Bool { model.layout.portrait }
    private var slim: Bool { mode == .mini || mode == .narrow }

    private var header: some View {
        HStack(spacing: 4) {
            Text(title ?? pane.title)
                .font(.geist(15, .medium))
                .foregroundStyle(colors.textDim)
                .lineLimit(1)
                .fixedSize()
                .padding(.leading, 16)
                .accessibilityAddTraits(.isHeader)
                .accessibilityAction(named: "Choose what this pane shows") { model.pickerFor = pane }
            Spacer(minLength: 8)
            if !slim { paneButtons }
            if model.layout.statusPane == pane { StatusCorner() }
        }
        .padding(.trailing, 6)
        .padding(.top, portrait && mode != .mini ? 6 : 2)
        .frame(minHeight: mode == .mini ? 28 : (portrait ? 48 : 44))
    }

    @ViewBuilder private var paneButtons: some View {
        let l = model.layout
        if l.canSwap(pane) {
            HeaderButton(icon: l.swapsVertically(pane) ? "arrow.up.arrow.down" : "arrow.left.arrow.right", label: "Swap") {
                Haptics.tap(); model.change { $0.swap(pane) }
            }
        }
        if mode != .fullscreen, l.canMaximize(pane) {
            let on = l.maximized == pane
            HeaderButton(icon: on ? "rectangle.compress.vertical" : "rectangle.expand.vertical", label: on ? "Restore" : "Maximize", selected: on) {
                Haptics.toggle(); model.change { $0.toggleMaximized(pane) }
            }
        }
        if pane != .call {
            let on = l.fullscreen == pane
            HeaderButton(icon: on ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right",
                         label: on ? "Exit fullscreen" : "Fullscreen", selected: on) {
                Haptics.toggle(); model.change { $0.toggleFullscreen(pane) }
            }
        }
    }
}

struct HeaderButton: View {
    let icon: String
    let label: String
    var selected = false
    let action: () -> Void
    @Environment(\.appColors) private var colors

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(selected ? colors.onNavy : colors.textDim)
                .frame(width: CardStyle.headerButton, height: CardStyle.headerButton)
                .background(selected ? colors.navy : .clear, in: Circle())
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// The status corner (7 / 8c): the time when no clock pane is on screen, the battery, the layouts button and the
/// settings gear. Pieces are left out when the header has no room (the title is never cut).
struct StatusCorner: View {
    @EnvironmentObject private var model: DashboardModel
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var battery: BatteryMonitor
    @Environment(\.appColors) private var colors

    var body: some View {
        let showClock = !model.layout.isOnScreen(.clock)
        ViewThatFits(in: .horizontal) {
            row(clock: showClock, battery: true)
            row(clock: false, battery: true)
            row(clock: false, battery: false)
        }
    }

    private func row(clock: Bool, battery showBattery: Bool) -> some View {
        HStack(spacing: 2) {
            if clock { CornerClock().padding(.horizontal, 6) }
            if showBattery, settings.showBattery, let level = battery.level {
                HStack(spacing: 3) {
                    Image(systemName: battery.onPower ? "battery.100.bolt" : "battery.75")
                        .font(.system(size: 13))
                    Text("\(level)%").font(.geist(14, .medium)).monospacedDigit()
                }
                .foregroundStyle(level <= 15 && !battery.onPower ? colors.red : colors.textDim)
                .padding(.horizontal, 6)
                .fixedSize()
            }
            HeaderButton(icon: "square.grid.2x2", label: "Layouts") { Haptics.tap(); model.showLayouts = true }
            HeaderButton(icon: "gearshape", label: "Settings") { Haptics.tap(); model.showSettings = true }
        }
        .fixedSize()
    }
}

private struct CornerClock: View {
    @Environment(\.appColors) private var colors
    var body: some View {
        TimelineView(.everyMinute) { ctx in
            let t = formatClockTime(ctx.date, zone: .current, is24: phoneUses24Hour())
            Text(t.suffix.map { "\(t.digits) \($0)" } ?? t.digits)
                .font(.geist(15, .medium)).monospacedDigit()
                .foregroundStyle(colors.text)
                .fixedSize()
        }
    }
}

/// The battery reading for the status corner. Nil level when the device doesn't report one (the simulator).
@MainActor
final class BatteryMonitor: ObservableObject {
    @Published private(set) var level: Int?
    @Published private(set) var onPower = false
    private var observers: [NSObjectProtocol] = []

    init() {
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        read()
        for name in [UIDevice.batteryLevelDidChangeNotification, UIDevice.batteryStateDidChangeNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.read() }
            })
        }
    }

    private func read() {
        let d = UIDevice.current
        level = d.batteryLevel < 0 ? nil : Int((d.batteryLevel * 100).rounded())
        // "On power" includes plugged in but full (weak bike USB ports).
        onPower = d.batteryState == .charging || d.batteryState == .full
    }
}

/// Map, media, navigation and Garage arrive in later builds; until then their panes say so.
struct PlaceholderCard: View {
    let pane: Pane
    let mode: CardMode
    let icon: String
    let note: String
    @Environment(\.appColors) private var colors

    var body: some View {
        DashboardCard(pane: pane, mode: mode) {
            VStack(spacing: 10) {
                Image(systemName: icon).font(.system(size: mode == .mini ? 22 : 34)).foregroundStyle(colors.textDim)
                if mode != .mini {
                    Text(note).font(.geist(15)).foregroundStyle(colors.textDim).multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                }
            }
        }
    }
}
