import SwiftUI

/// The speed pane: two faces (speed, trip log) side by side, swiped like Android's pager; the summary replaces them
/// while a trip is ended. Each mode draws from the slot, never from its measured size.
struct SpeedCard: View {
    let mode: CardMode
    @EnvironmentObject private var trip: TripStore
    @EnvironmentObject private var model: DashboardModel
    @State private var page = 0

    var body: some View {
        DashboardCard(pane: .speed, mode: mode) {
            if trip.state == .ended {
                TripSummaryView(mode: mode)
                    .transition(.opacity)
            } else {
                FacePager(page: $page, enabled: model.lifted == nil) {
                    SpeedFace(mode: mode, portrait: model.layout.portrait)
                } second: {
                    LogFace(mode: mode)
                }
            }
        }
        .animation(.easeInOut(duration: 0.25), value: trip.state == .ended)
        // A trip that ends while the card is up lands on the summary; the next card opens on the speed face.
        .onChange(of: trip.state) { _, s in if s == .idle { page = 0 } }
    }
}

// MARK: - Speed face

struct SpeedFace: View {
    let mode: CardMode
    let portrait: Bool
    @EnvironmentObject private var trip: TripStore
    @Environment(\.appColors) private var colors

    var body: some View {
        let ui = trip.ui
        Group {
            switch mode {
            case .compact:
                VStack(spacing: 6) { Hero(ui: ui, size: 96); StateLine(ui: ui) }
            case .wide where portrait:
                HStack(spacing: 18) { Hero(ui: ui, size: 72); StatLines(stats: inline(ui, 4)) }
            case .wide:
                HStack(spacing: 28) { Hero(ui: ui, size: 96); StatLines(stats: inline(ui, 4)) }
            case .mini:
                HStack(spacing: 16) { Hero(ui: ui, size: 56, unitBeside: true); StatLines(stats: inline(ui, 2)) }
            case .narrow:
                VStack(spacing: 8) { Hero(ui: ui, size: 56); StatLines(stats: inline(ui, 2)) }
            case .expanded:
                VStack(spacing: 14) { Hero(ui: ui, size: portrait ? 120 : 112); StateLine(ui: ui); StatGrid(stats: ui.telemetry(), columns: 3, tileHeight: 62) }
                    .padding(.horizontal, 14)
            case .fullscreen:
                VStack(spacing: 18) { Hero(ui: ui, size: portrait ? 150 : 170); StateLine(ui: ui); StatGrid(stats: ui.telemetry(), columns: portrait ? 3 : 6, tileHeight: 76) }
                    .padding(.horizontal, 18)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.bottom, mode == .mini ? 2 : 14)
    }

    /// Max, average, distance, time: the figures a slim card shows beside the hero.
    private func inline(_ ui: SpeedometerUiState, _ n: Int) -> [Stat] {
        let t = ui.telemetry()
        return Array([t[0], t[1], t[4], t[3]].prefix(n))
    }
}

/// The big number and its unit (always visible). "--" while there is no live speed.
struct Hero: View {
    let ui: SpeedometerUiState
    let size: CGFloat
    var unitBeside = false
    @Environment(\.appColors) private var colors

    var body: some View {
        let number = Text(ui.speed.map(String.init) ?? "--")
            .font(.geist(size, .medium)).monospacedDigit()
            .foregroundStyle(colors.text)
            .lineLimit(1).minimumScaleFactor(0.4)
        let unit = Text(ui.unit.speedLabel).font(.geist(max(14, size * 0.18), .medium)).foregroundStyle(colors.textDim)
        if unitBeside {
            HStack(alignment: .lastTextBaseline, spacing: 6) { number; unit }
        } else {
            VStack(spacing: -size * 0.06) { number; unit }
        }
    }
}

/// Under the hero: why there is no speed, or that the trip is paused (with Resume).
private struct StateLine: View {
    let ui: SpeedometerUiState
    @EnvironmentObject private var trip: TripStore
    @Environment(\.appColors) private var colors

    var body: some View {
        switch ui.tripState {
        case .paused:
            HStack(spacing: 12) {
                Text(ui.autoPaused ? "Auto-paused: no movement" : "Paused").font(.geist(15, .medium)).foregroundStyle(colors.amber)
                BigButton(title: "Resume", icon: "play.fill", style: .main, compact: true) { Haptics.tap(); trip.resume() }
            }
        default:
            if ui.gps != .fix {
                Text(ui.gps == .permissionDenied ? "Location not allowed" : ui.statusLabel)
                    .font(.geist(15, .medium)).foregroundStyle(ui.gps == .stale || ui.gps == .permissionDenied ? colors.amber : colors.textDim)
            }
        }
    }
}

struct StatLines: View {
    let stats: [Stat]
    @Environment(\.appColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(stats) { s in
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(s.label).font(.geist(12, .medium)).foregroundStyle(colors.textDim).frame(minWidth: 64, alignment: .leading)
                    Text(s.value).font(.geist(20, .medium)).monospacedDigit().foregroundStyle(colors.text)
                    if let u = s.unit { Text(u).font(.geist(13)).foregroundStyle(colors.textDim) }
                }
                .lineLimit(1)
            }
        }
        .fixedSize()
    }
}

struct StatGrid: View {
    let stats: [Stat]
    let columns: Int
    let tileHeight: CGFloat

    var body: some View {
        let rows = stride(from: 0, to: stats.count, by: columns).map { Array(stats[$0..<min($0 + columns, stats.count)]) }
        VStack(spacing: 10) {
            ForEach(rows.indices, id: \.self) { i in
                HStack(spacing: 10) {
                    ForEach(rows[i]) { StatTile(stat: $0).frame(height: tileHeight) }
                    ForEach(0..<(columns - rows[i].count), id: \.self) { _ in Color.clear.frame(maxWidth: .infinity) }
                }
            }
        }
    }
}

struct StatTile: View {
    let stat: Stat
    @Environment(\.appColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(stat.label).font(.geist(12, .medium)).foregroundStyle(colors.textDim).lineLimit(1)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(stat.value).font(.geist(24, .medium)).monospacedDigit().foregroundStyle(colors.text)
                if let u = stat.unit { Text(u).font(.geist(13, .medium)).foregroundStyle(colors.textDim) }
            }
            .lineLimit(1).minimumScaleFactor(0.6)
        }
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(colors.buttonQuiet, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Log face

/// The trip's own face (9b): its state, the chosen figures, and Start / Pause / Resume / End (two taps).
struct LogFace: View {
    let mode: CardMode
    @EnvironmentObject private var trip: TripStore
    @EnvironmentObject private var settings: SettingsStore
    @Environment(\.appColors) private var colors

    var body: some View {
        let ui = trip.ui
        let visible = Set(TelemetryStat.allCases).subtracting(settings.hiddenStats)
        let small = mode == .compact || mode == .mini || mode == .narrow || mode == .wide
        VStack(spacing: small ? 8 : 14) {
            Text(title(ui)).font(.geist(small ? 15 : 18, .medium))
                .foregroundStyle(ui.tripState == .paused ? colors.amber : colors.textDim)
            if mode != .mini {
                if small {
                    StatLines(stats: Array(ui.tripStats(visible: visible, prefix: "log_").prefix(mode == .wide ? 3 : 2)))
                } else {
                    StatGrid(stats: ui.tripStats(visible: visible, prefix: "log_"), columns: 3, tileHeight: 62)
                }
            }
            Controls(compact: small)
        }
        .padding(.horizontal, 14)
        .padding(.bottom, 14)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("log_face")
    }

    private func title(_ ui: SpeedometerUiState) -> String {
        switch ui.tripState {
        case .idle: "No trip running"
        case .running: "Trip running"
        case .paused: ui.autoPaused ? "Auto-paused: no movement" : "Trip paused"
        case .ended: "Trip ended"
        }
    }
}

private struct Controls: View {
    let compact: Bool
    @EnvironmentObject private var trip: TripStore

    var body: some View {
        HStack(spacing: 12) {
            switch trip.state {
            case .idle, .ended:
                BigButton(title: "Start trip", icon: "play.fill", style: .main, compact: compact) { Haptics.tap(); trip.start() }
                    .accessibilityIdentifier("log_start")
            case .running:
                BigButton(title: "Pause", icon: "pause.fill", style: .quiet, compact: compact) { Haptics.tap(); trip.pause() }
                    .accessibilityIdentifier("log_pause")
                endButton
            case .paused:
                BigButton(title: "Resume", icon: "play.fill", style: .main, compact: compact) { Haptics.tap(); trip.resume() }
                    .accessibilityIdentifier("log_resume")
                endButton
            }
        }
    }

    private var endButton: some View {
        BigButton(title: trip.endArmed ? "Tap again to end" : "End", icon: "stop.fill", style: trip.endArmed ? .danger : .quiet,
                  compact: compact) { Haptics.tap(); trip.tapEnd() }
            .accessibilityIdentifier("log_end")
    }
}

/// A big button for gloves: 56 pt tall (48 when the card is small).
struct BigButton: View {
    enum Style { case main, quiet, danger }
    let title: String
    var icon: String? = nil
    var style: Style = .quiet
    var compact = false
    let action: () -> Void
    @Environment(\.appColors) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon { Image(systemName: icon).font(.system(size: 16, weight: .semibold)) }
                Text(title).font(.geist(17, .semibold)).lineLimit(1).minimumScaleFactor(0.7)
            }
            .padding(.horizontal, 20)
            .frame(minHeight: compact ? 48 : CardStyle.bigButton)
            .foregroundStyle(fg)
            .background(bg, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var bg: Color { switch style { case .main: colors.button; case .quiet: colors.buttonQuiet; case .danger: colors.red } }
    private var fg: Color { switch style { case .main: colors.onButton; case .quiet: colors.onButtonQuiet; case .danger: .white } }
}

// MARK: - Summary

/// Shown while the trip is ended (9b): the figures count up; a tap dismisses (the trip is cleared).
struct TripSummaryView: View {
    let mode: CardMode
    @EnvironmentObject private var trip: TripStore
    @EnvironmentObject private var settings: SettingsStore
    @Environment(\.appColors) private var colors
    @State private var progress = 0.0

    var body: some View {
        let visible = Set(TelemetryStat.allCases).subtracting(settings.hiddenStats)
        let small = mode == .compact || mode == .mini || mode == .narrow || mode == .wide
        let stats = trip.ui.tripStats(visible: visible, prefix: "sum_", progress: progress)
        VStack(spacing: small ? 8 : 14) {
            Text("Trip summary").font(.geist(small ? 16 : 20, .semibold)).foregroundStyle(colors.text)
            if mode != .mini {
                if small { StatLines(stats: Array(stats.prefix(3))) } else { StatGrid(stats: stats, columns: 3, tileHeight: 62) }
            }
            Text("Tap to dismiss").font(.geist(13)).foregroundStyle(colors.textDim)
        }
        .padding(.horizontal, 14).padding(.bottom, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .onTapGesture { Haptics.tap(); trip.dismissSummary() }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("trip_summary")
        .accessibilityAddTraits(.isButton)
        .gesture(DragGesture(minimumDistance: 30).onEnded { _ in trip.dismissSummary() })
        .onAppear {
            progress = 0
            withAnimation(.easeOut(duration: 0.9)) { progress = 1 }
        }
    }
}

// MARK: - Pager

/// Two faces side by side; a horizontal swipe turns the page. Off while a card is lifted, so a drag never pages.
struct FacePager<A: View, B: View>: View {
    @Binding var page: Int
    let enabled: Bool
    @ViewBuilder let first: () -> A
    @ViewBuilder let second: () -> B
    @GestureState private var dx: CGFloat = 0
    @Environment(\.appColors) private var colors

    var body: some View {
        GeometryReader { g in
            let w = g.size.width
            ZStack(alignment: .bottom) {
                HStack(spacing: 0) {
                    first().frame(width: w)
                    second().frame(width: w)
                }
                .offset(x: -CGFloat(page) * w + rubber(dx, w))
                .frame(width: w, alignment: .leading)
                .clipped()
                HStack(spacing: 6) {
                    ForEach(0..<2, id: \.self) { i in
                        Circle().fill(i == page ? colors.text : colors.outline).frame(width: 6, height: 6)
                    }
                }
                .padding(.bottom, 5)
                .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 20)
                    .updating($dx) { v, s, _ in if abs(v.translation.width) > abs(v.translation.height) { s = v.translation.width } }
                    .onEnded { v in
                        guard abs(v.translation.width) > abs(v.translation.height) else { return }
                        let next = v.predictedEndTranslation.width < -w / 3 ? 1 : (v.predictedEndTranslation.width > w / 3 ? 0 : page)
                        if next != page { Haptics.tick() }
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.9)) { page = next }
                    },
                including: enabled ? .all : .subviews
            )
            .animation(.spring(response: 0.35, dampingFraction: 0.9), value: dx == 0)
            .accessibilityScrollAction { edge in
                withAnimation { page = edge == .trailing ? 1 : 0 }
            }
        }
    }

    /// Past either end the face only gives a little.
    private func rubber(_ d: CGFloat, _ w: CGFloat) -> CGFloat {
        if (page == 0 && d > 0) || (page == 1 && d < 0) { return d / 4 }
        return d
    }
}
