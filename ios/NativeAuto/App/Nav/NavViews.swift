import SwiftUI

// Port of Android `ui/nav/NavigationOverlay.kt`, `ManeuverGlyph.kt`, `NextTurnCard.kt`, `LinkStatusPill.kt`.

/// The maneuver as an arrow (SF Symbols, turned for slight and sharp bends).
struct ManeuverGlyph: View {
    let instruction: Instruction
    var size: CGFloat = 48
    var color: Color

    var body: some View {
        let (name, angle) = symbol
        Image(systemName: name)
            .resizable().scaledToFit()
            .fontWeight(.bold)
            .foregroundStyle(color)
            .rotationEffect(.degrees(angle))
            .frame(width: size, height: size)
            .accessibilityLabel(instruction.maneuverWords)
    }

    private var symbol: (String, Double) {
        switch instruction.kind {
        case .arrive: return ("mappin.circle.fill", 0)
        case .depart: return ("location.north.fill", 0)
        case .roundabout: return ("arrow.triangle.2.circlepath", 0)
        case .merge: return ("arrow.triangle.merge", 0)
        case .fork:
            return ("arrow.triangle.branch", isLeft ? -30 : 30)
        default:
            switch instruction.direction {
            case .left: return ("arrow.turn.up.left", 0)
            case .right: return ("arrow.turn.up.right", 0)
            case .slightLeft: return ("arrow.up", -40)
            case .slightRight: return ("arrow.up", 40)
            case .sharpLeft: return ("arrow.up", -130)
            case .sharpRight: return ("arrow.up", 130)
            case .uTurn: return ("arrow.uturn.down", 0)
            case .straight, nil: return ("arrow.up", 0)
            }
        }
    }

    private var isLeft: Bool { [.left, .slightLeft, .sharpLeft].contains(instruction.direction) }
}

/// Arrival line: "14:32 · 8 min · 4.2 km".
func etaLine(_ g: Guidance, unit: SpeedUnit) -> String {
    "\(formatEta(nowMs: Int64(Date().timeIntervalSince1970 * 1000), remainingS: g.durationRemainingS)) · " +
        "\(formatNavDuration(g.durationRemainingS)) · \(formatNavDistance(g.distanceRemainingM, unit))"
}

/// The banner over the map (Expanded / Fullscreen): arrow, distance, road.
struct NavigationBanner: View {
    let g: Guidance
    let unit: SpeedUnit
    @Environment(\.appColors) private var colors

    var body: some View {
        HStack(spacing: 14) {
            if g.rerouting {
                ProgressView().tint(colors.onNavy).scaleEffect(1.4).frame(width: 48, height: 48)
                Text("Rerouting…").font(.geist(24, .semibold)).foregroundStyle(colors.onNavy)
            } else {
                if let i = g.instruction { ManeuverGlyph(instruction: i, size: 48, color: colors.onNavy) }
                VStack(alignment: .leading, spacing: 0) {
                    Text(formatNavDistance(g.distanceToManeuverM, unit)).font(.geist(32, .bold)).monospacedDigit()
                        .accessibilityIdentifier("nav_distance")
                    Text(g.instruction?.bannerText ?? "Follow the route").font(.geist(18, .medium)).lineLimit(2)
                        .accessibilityIdentifier("nav_instruction")
                }
                .foregroundStyle(colors.onNavy)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(colors.navy, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("nav_banner")
    }
}

/// The ETA card at the bottom left of a big map.
struct NavigationSummary: View {
    let g: Guidance
    let unit: SpeedUnit
    @Environment(\.appColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(formatEta(nowMs: Int64(Date().timeIntervalSince1970 * 1000), remainingS: g.durationRemainingS))
                .font(.geist(24, .bold)).foregroundStyle(colors.accent).monospacedDigit()
            Text("\(formatNavDuration(g.durationRemainingS)) · \(formatNavDistance(g.distanceRemainingM, unit))")
                .font(.geist(15, .medium)).foregroundStyle(colors.textDim)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(colors.surface.opacity(0.95), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(colors.outline, lineWidth: 1))
        .accessibilityIdentifier("nav_summary")
    }
}

/// 13b: the compact map's one chip while guiding (arrow + distance), top left.
struct NavigationChip: View {
    let g: Guidance
    let unit: SpeedUnit
    @Environment(\.appColors) private var colors

    var body: some View {
        HStack(spacing: 8) {
            if g.rerouting {
                ProgressView().tint(colors.onNavy)
            } else if let i = g.instruction {
                ManeuverGlyph(instruction: i, size: 26, color: colors.onNavy)
            }
            Text(g.rerouting ? "Rerouting…" : formatNavDistance(g.distanceToManeuverM, unit))
                .font(.geist(20, .bold)).monospacedDigit().foregroundStyle(colors.onNavy)
                .accessibilityIdentifier("nav_chip_distance")
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(colors.navy, in: Capsule())
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("nav_chip")
    }
}

/// The planned route: summary, Cancel and Start.
struct RoutePreviewCard: View {
    let destination: Place
    let plan: RoutePlan
    let unit: SpeedUnit
    let onStart: () -> Void
    let onCancel: () -> Void
    @Environment(\.appColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(destination.name).font(.geist(20, .semibold)).foregroundStyle(colors.text).lineLimit(1)
            if let d = destination.detail { Text(d).font(.geist(14)).foregroundStyle(colors.textDim).lineLimit(1) }
            Text("\(formatNavDuration(plan.durationS)) · \(formatNavDistance(plan.distanceM, unit))")
                .font(.geist(17, .medium)).foregroundStyle(colors.accent)
                .accessibilityIdentifier("nav_preview_summary")
            HStack {
                Spacer()
                Button { Haptics.tap(); onCancel() } label: {
                    Text("Cancel").font(.geist(17, .semibold)).foregroundStyle(colors.text).frame(minWidth: 80, minHeight: CardStyle.bigButton)
                }
                .buttonStyle(.plain).accessibilityIdentifier("nav_cancel")
                BigButton(title: "Start", icon: "location.north.fill", style: .main) { Haptics.tap(); onStart() }
                    .frame(minWidth: 112).accessibilityIdentifier("nav_start")
            }
        }
        .padding(16)
        .background(colors.surface, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(colors.outline, lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("nav_preview")
    }
}

/// Planning, a failure or the arrival: one line and its buttons.
struct NavStatusCard: View {
    let state: NavState
    let onRetry: () -> Void
    let onDismiss: () -> Void
    @Environment(\.appColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                if case .routing = state { ProgressView().tint(colors.accent) }
                Text(title).font(.geist(19, .semibold)).foregroundStyle(colors.text)
            }
            if let detail { Text(detail).font(.geist(15)).foregroundStyle(colors.textDim) }
            HStack {
                Spacer()
                if case .failed(_, let reason) = state, reason != .noRoute {
                    Button { Haptics.tap(); onRetry() } label: {
                        Text("Retry").font(.geist(17, .semibold)).foregroundStyle(colors.accent).frame(minWidth: 80, minHeight: CardStyle.bigButton)
                    }
                    .buttonStyle(.plain).accessibilityIdentifier("nav_retry")
                }
                Button { Haptics.tap(); onDismiss() } label: {
                    Text(dismissLabel).font(.geist(17, .semibold)).foregroundStyle(colors.text).frame(minWidth: 80, minHeight: CardStyle.bigButton)
                }
                .buttonStyle(.plain).accessibilityIdentifier("nav_dismiss")
            }
        }
        .padding(16)
        .background(colors.surface, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(colors.outline, lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("nav_status")
    }

    private var title: String {
        switch state {
        case .routing: "Finding a route…"
        case .arrived: "You've arrived"
        case .failed(_, .noLocation): "Waiting for GPS"
        case .failed(_, .offline): "Can't reach the route server"
        case .failed(_, .noRoute): "No route found"
        default: ""
        }
    }

    private var detail: String? {
        switch state {
        case .routing(let p), .arrived(let p): p.name
        case .failed(_, .noLocation): "No position yet to route from"
        case .failed(_, .offline): "Check the connection and retry"
        case .failed(let p, .noRoute): "Nothing rideable to \(p.name)"
        default: nil
        }
    }

    private var dismissLabel: String { if case .arrived = state { "Done" } else { "Cancel" } }
}

/// "Opening shared place…" / why a link failed, top centre over the dashboard.
struct LinkStatusPill: View {
    let status: Navigator.LinkStatus
    @Environment(\.appColors) private var colors

    var body: some View {
        HStack(spacing: 10) {
            switch status {
            case .resolving:
                ProgressView().tint(colors.accent)
                Text("Opening shared place…")
            case .failed(let m):
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(colors.amber)
                Text(m)
            }
        }
        .font(.geist(16, .medium)).foregroundStyle(colors.text)
        .padding(.horizontal, 18).frame(minHeight: 48)
        .background(colors.surface, in: Capsule())
        .overlay(Capsule().strokeBorder(colors.outline, lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("link_status")
    }
}

/// End navigation: two taps within 3 s (the button turns red: "Tap again to end navigation").
struct EndNavButton: View {
    enum Style { case header, fab, big }
    var style: Style = .big
    @EnvironmentObject private var navigator: Navigator
    @Environment(\.appColors) private var colors
    @State private var armed = false
    @State private var disarm: Task<Void, Never>?

    var body: some View {
        switch style {
        case .header:
            HeaderButton(icon: "xmark", label: armed ? "Tap again to end navigation" : "End navigation", selected: armed) { tap() }
                .background(armed ? colors.red : .clear, in: Circle())
                .accessibilityIdentifier("nav_end")
        case .fab:
            Button { tap() } label: {
                Image(systemName: "xmark").font(.system(size: 22, weight: .bold))
                    .foregroundStyle(armed ? .white : colors.text)
                    .frame(width: CardStyle.bigButton, height: CardStyle.bigButton)
                    .background(armed ? colors.red : colors.surface, in: Circle())
                    .overlay(Circle().strokeBorder(colors.outline, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(armed ? "Tap again to end navigation" : "End navigation")
            .accessibilityIdentifier("nav_end")
        case .big:
            BigButton(title: armed ? "Tap again to end" : "End navigation", icon: "xmark", style: armed ? .danger : .quiet) { tap() }
                .accessibilityIdentifier("next_turn_end")
        }
    }

    private func tap() {
        if armed {
            disarm?.cancel(); armed = false
            Haptics.confirm()
            navigator.stop()
        } else {
            Haptics.tap()
            armed = true
            disarm = Task {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                if !Task.isCancelled { armed = false }
            }
        }
    }
}

/// Voice guidance on/off, mid-route (a switch: navy while speaking).
struct VoiceButton: View {
    @EnvironmentObject private var settings: SettingsStore

    var body: some View {
        HeaderButton(icon: settings.voiceGuidance ? "speaker.wave.2.fill" : "speaker.slash.fill",
                     label: settings.voiceGuidance ? "Mute voice guidance" : "Unmute voice guidance",
                     selected: settings.voiceGuidance) {
            Haptics.toggle(); settings.voiceGuidance.toggle()
        }
        .accessibilityIdentifier("nav_voice")
    }
}

// MARK: - Navigation pane

/// The Navigation pane (8b; "Next turn" until 13a): navigation readable without the map. While guiding: a big arrow,
/// the distance to the turn, the road and the arrival time. Idle, a big "Where to?" (the map's search). Planning,
/// the preview and failures show the same cards as the map, so it works in a layout without one.
struct NextTurnCard: View {
    let mode: CardMode
    @EnvironmentObject private var navigator: Navigator
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var model: DashboardModel
    @Environment(\.appColors) private var colors
    @Environment(\.paneOnScreen) private var onScreen

    var body: some View {
        DashboardCard(pane: .nav, mode: mode) {
            Group {
                if !onScreen {
                    EmptyView()
                } else {
                switch navigator.state {
                case .idle:
                    BigButton(title: "Where to?", icon: "magnifyingglass", style: .main) { Haptics.tap(); model.showSearch = true }
                        .accessibilityIdentifier("nav_where_to")
                case .routing, .failed, .arrived:
                    NavStatusCard(state: navigator.state, onRetry: navigator.retry, onDismiss: navigator.stop).frame(maxWidth: 560)
                case .preview(let place, let plan):
                    RoutePreviewCard(destination: place, plan: plan, unit: settings.speedUnit, onStart: navigator.start, onCancel: navigator.stop)
                        .frame(maxWidth: 560)
                case .navigating(let g):
                    TurnFace(g: g, unit: settings.speedUnit, mode: mode, portrait: model.layout.portrait)
                }
                }
            }
            .padding(.horizontal, 12).padding(.bottom, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
        }
    }
}

struct TurnFace: View {
    let g: Guidance
    let unit: SpeedUnit
    let mode: CardMode
    let portrait: Bool
    @Environment(\.appColors) private var colors

    var body: some View {
        if g.rerouting || (g.offRoute && g.instruction == nil) {
            HStack(spacing: 16) {
                ProgressView().tint(colors.accent).scaleEffect(1.5)
                Text("Rerouting…").font(.geist(24, .semibold)).foregroundStyle(colors.text)
            }
        } else {
            TimelineView(.everyMinute) { _ in content }
        }
    }

    private var distance: String { formatNavDistance(g.distanceToManeuverM, unit) }
    private var road: String { g.instruction?.bannerText ?? "Follow the route" }
    private var eta: String { etaLine(g, unit: unit) }

    @ViewBuilder private var content: some View {
        switch mode {
        case .mini: centered(glyph: 48, distance: 30, road: 14, eta: 14)
        case .narrow: centered(glyph: 84, distance: 44, road: 18, eta: 15)
        case .wide where portrait: centered(glyph: 72, distance: 40, road: 18, eta: 17)
        case .compact, .wide:
            // 13a: the arrow is centred on the distance + road block, and the arrival line starts at that block's left
            // edge, so all the text shares one edge.
            let glyph: CGFloat = mode == .wide ? 84 : 72
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 14) {
                    if let i = g.instruction { ManeuverGlyph(instruction: i, size: glyph, color: colors.text).accessibilityIdentifier("next_turn_glyph") }
                    VStack(alignment: .leading, spacing: 0) {
                        distanceText(mode == .wide ? 48 : 40)
                        roadText(17, lines: mode == .wide ? 1 : 2, align: .leading)
                    }
                    if mode == .wide {
                        Spacer(minLength: 16)
                        etaText(22, align: .trailing)
                    }
                }
                if mode == .compact { etaText(17, align: .leading).padding(.leading, g.instruction != nil ? glyph + 14 : 0) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        default:
            let big = mode == .fullscreen
            VStack(spacing: 4) {
                if let i = g.instruction { ManeuverGlyph(instruction: i, size: big ? 180 : 132, color: colors.text).accessibilityIdentifier("next_turn_glyph") }
                distanceText(big ? 88 : 64)
                roadText(big ? 32 : 24, lines: 2, align: .center)
                if let next = g.nextInstruction {
                    Text("Then \(next.bannerText)").font(.geist(17, .medium)).foregroundStyle(colors.textDim).lineLimit(1)
                }
                etaText(big ? 28 : 22, align: .center).padding(.top, 8)
                EndNavButton().padding(.top, 12)
            }
        }
    }

    /// 14b: arrow, road, distance and arrival, all on one centre axis.
    private func centered(glyph: CGFloat, distance d: CGFloat, road r: CGFloat, eta e: CGFloat) -> some View {
        VStack(spacing: 2) {
            if let i = g.instruction { ManeuverGlyph(instruction: i, size: glyph, color: colors.text).accessibilityIdentifier("next_turn_glyph") }
            roadText(r, lines: 2, align: .center).padding(.top, 2)
            distanceText(d)
            etaText(e, align: .center, lines: 2)
        }
    }

    private func distanceText(_ size: CGFloat) -> some View {
        Text(distance).font(.geist(size, .bold)).monospacedDigit().foregroundStyle(colors.text).lineLimit(1).minimumScaleFactor(0.6)
            .accessibilityIdentifier("next_turn_distance")
    }

    private func roadText(_ size: CGFloat, lines: Int, align: TextAlignment) -> some View {
        Text(road).font(.geist(size, .medium)).foregroundStyle(colors.text).lineLimit(lines).multilineTextAlignment(align)
            .accessibilityIdentifier("next_turn_road")
    }

    private func etaText(_ size: CGFloat, align: TextAlignment, lines: Int = 1) -> some View {
        Text(eta).font(.geist(size, .semibold)).monospacedDigit().foregroundStyle(colors.accent).lineLimit(lines)
            .multilineTextAlignment(align)
            .accessibilityIdentifier("next_turn_eta")
    }
}
