import SwiftUI

/// The clock pane (8b / 8c): digital, or the analog edge-tick face, chosen in the pane itself when it is large.
struct ClockCard: View {
    let mode: CardMode
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var model: DashboardModel
    @Environment(\.appColors) private var colors

    var body: some View {
        DashboardCard(pane: .clock, mode: mode) {
            VStack(spacing: 0) {
                Group {
                    if settings.clockFace == .analog && mode != .mini {
                        let seconds = settings.clockSeconds && model.layout.isOnScreen(.clock)
                        TimelineView(seconds ? .periodic(from: .now, by: 1) : .periodic(from: .now, by: 60)) { ctx in
                            AnalogFace(date: ctx.date, secondHand: seconds)
                        }
                        .padding(10)
                    } else {
                        TimelineView(.everyMinute) { ctx in DigitalFace(date: ctx.date, mode: mode, portrait: model.layout.portrait) }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                if mode == .expanded || mode == .fullscreen { FaceControls().padding(.bottom, 14) }
            }
        }
    }
}

private struct DigitalFace: View {
    let date: Date
    let mode: CardMode
    let portrait: Bool
    @Environment(\.appColors) private var colors

    var body: some View {
        let time = formatClockTime(date, zone: .current, is24: phoneUses24Hour())
        let day = formatClockDate(date, zone: .current, locale: .current)
        switch mode {
        case .mini: Time(time: time, size: 56)
        case .narrow: stacked(time, day, 48, 16)
        case .compact: stacked(time, day, 72, 18)
        case .wide where portrait: stacked(time, day, 88, 22)
        case .wide:
            HStack(alignment: .lastTextBaseline, spacing: 24) { Time(time: time, size: 96); Text(day).font(.geist(26, .medium)).foregroundStyle(colors.textDim) }
        case .expanded: stacked(time, day, 120, 26)
        case .fullscreen: stacked(time, day, 180, 34)
        }
    }

    private func stacked(_ t: ClockTime, _ d: String, _ size: CGFloat, _ dateSize: CGFloat) -> some View {
        VStack(spacing: 4) { Time(time: t, size: size); Text(d).font(.geist(dateSize, .medium)).foregroundStyle(colors.textDim) }
    }
}

private struct Time: View {
    let time: ClockTime
    let size: CGFloat
    @Environment(\.appColors) private var colors

    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: 6) {
            Text(time.digits).font(.geist(size, .medium)).monospacedDigit().foregroundStyle(colors.text)
            if let s = time.suffix { Text(s).font(.geist(size * 0.25, .medium)).foregroundStyle(colors.textDim) }
        }
        .lineLimit(1).minimumScaleFactor(0.4)
        .accessibilityLabel(time.suffix.map { "\(time.digits) \($0)" } ?? time.digits)
    }
}

/// The edge-tick face: 60 ticks along the card's own rounded edge (evenly spaced along it), hands pointing at their
/// own spot on the edge, an optional accent second hand.
private struct AnalogFace: View {
    let date: Date
    let secondHand: Bool
    @Environment(\.appColors) private var colors

    var body: some View {
        Canvas { ctx, size in
            let halfW = size.width / 2, halfH = size.height / 2
            let radius = min(CardStyle.radius - 6, min(halfW, halfH))
            let c = CGPoint(x: halfW, y: halfH)
            let shortTick: CGFloat = 8, longTick: CGFloat = 18
            for i in 0..<60 {
                let hit = edgeAt(Double(i) / 60, halfW: halfW, halfH: halfH, radius: radius)
                let isHour = i % 5 == 0
                let len = isHour ? longTick : shortTick
                var p = Path()
                p.move(to: CGPoint(x: c.x + hit.x, y: c.y + hit.y))
                p.addLine(to: CGPoint(x: c.x + hit.x - hit.nx * len, y: c.y + hit.y - hit.ny * len))
                ctx.stroke(p, with: .color(isHour ? colors.text : colors.textDim.opacity(0.7)), style: StrokeStyle(lineWidth: isHour ? 3 : 1.5, lineCap: .round))
            }
            let cal = Calendar.current
            let comps = cal.dateComponents([.hour, .minute, .second, .nanosecond], from: date)
            let sec = Double(comps.second ?? 0)
            let minute = Double(comps.minute ?? 0) + sec / 60
            let hour = Double(comps.hour ?? 0) + minute / 60
            let turns = handTurns(hour: hour, minute: minute, second: sec)

            func edge(_ turn: Double) -> CGPoint { let h = edgeAt(turn, halfW: halfW, halfH: halfH, radius: radius); return CGPoint(x: h.x, y: h.y) }
            func dir(_ turn: Double) -> CGPoint { let e = edge(turn); let l = hypot(e.x, e.y); return CGPoint(x: e.x / l, y: e.y / l) }
            func hand(_ turn: Double, _ length: CGFloat, _ color: Color, _ width: CGFloat, tail: CGFloat = 0) {
                let d = dir(turn)
                var p = Path()
                p.move(to: CGPoint(x: c.x - d.x * tail, y: c.y - d.y * tail))
                p.addLine(to: CGPoint(x: c.x + d.x * length, y: c.y + d.y * length))
                ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
            }
            func reach(_ turn: Double, _ inset: CGFloat) -> CGFloat { let e = edge(turn); return hypot(e.x, e.y) - inset }

            hand(turns.hour, min(halfW, halfH) * 0.5, colors.text, 6)
            hand(turns.minute, reach(turns.minute, longTick + 8), colors.text, 3.5)
            if secondHand { hand(turns.second, reach(turns.second, shortTick + 4), colors.accent, 1.5, tail: 18) }
            let hub = CGRect(x: c.x - 6, y: c.y - 6, width: 12, height: 12)
            ctx.fill(Path(ellipseIn: hub), with: .color(colors.card))
            ctx.stroke(Path(ellipseIn: hub), with: .color(colors.text), lineWidth: 2.5)
        }
        .accessibilityLabel(formatClockTime(date, zone: .current, is24: phoneUses24Hour()).digits)
    }
}

/// Digital / Analog, and the analog face's second hand. Big targets, shown when the pane is large.
private struct FaceControls: View {
    @EnvironmentObject private var settings: SettingsStore
    @Environment(\.appColors) private var colors

    var body: some View {
        HStack(spacing: 12) {
            Segments(options: ClockFace.allCases, selection: $settings.clockFace) { $0.label }
                .frame(maxWidth: 260)
            if settings.clockFace == .analog {
                Toggle("Second hand", isOn: $settings.clockSeconds)
                    .font(.geist(15, .medium)).foregroundStyle(colors.text)
                    .tint(colors.accent)
                    .fixedSize()
            }
        }
        .padding(.horizontal, 16)
    }
}

/// A segmented choice in the app's colours: navy fills the selected segment (Android `appSegmentedColors`).
struct Segments<T: Hashable>: View {
    let options: [T]
    @Binding var selection: T
    let label: (T) -> String
    @Environment(\.appColors) private var colors

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options, id: \.self) { o in
                let on = o == selection
                Button {
                    if !on { Haptics.tick(); selection = o }
                } label: {
                    Text(label(o)).font(.geist(15, .medium)).lineLimit(1).minimumScaleFactor(0.7)
                        .foregroundStyle(on ? colors.onNavy : colors.text)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(on ? colors.navy : .clear)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .clipShape(Capsule())
        .overlay(Capsule().strokeBorder(colors.outline, lineWidth: 1))
    }
}
