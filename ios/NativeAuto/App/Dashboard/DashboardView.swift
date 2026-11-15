import SwiftUI

/// Every pane always exists, each placed in its rectangle (`DashboardLayoutState.rectOf`) as fractions of the screen;
/// changes slide them with one spring. Nothing is removed or recreated (Android rules #3 / #4).
struct DashboardView: View {
    @EnvironmentObject private var model: DashboardModel
    @EnvironmentObject private var settings: SettingsStore
    @Environment(\.appColors) private var colors

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            ZStack(alignment: .topLeading) {
                ForEach(Pane.allCases, id: \.self) { pane in
                    PaneSlot(pane: pane, size: size)
                }
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
            .coordinateSpace(name: "dash")
            .onAppear { model.setPortrait(size.height > size.width) }
            .onChange(of: size) { _, s in model.setPortrait(s.height > s.width) }
        }
    }
}

/// One pane in its slot: frame from the layout, the hold-to-lift gesture, the lifted card's offset and outline.
private struct PaneSlot: View {
    let pane: Pane
    let size: CGSize
    @EnvironmentObject private var model: DashboardModel
    @EnvironmentObject private var settings: SettingsStore
    @Environment(\.appColors) private var colors
    @GestureState private var holdStart: Date?
    @GestureState private var dragging = false

    /// The gap between neighbouring cards.
    private let gap: CGFloat = 4

    var body: some View {
        let r = model.layout.rectOf(pane)
        // Edges are rounded, not sizes, so neighbours share an edge exactly.
        let left = (r.left * size.width).rounded(), right = (r.right * size.width).rounded()
        let top = (r.top * size.height).rounded(), bottom = (r.bottom * size.height).rounded()
        let w = max(0, right - left), h = max(0, bottom - top)
        let visible = w > 1 && h > 1
        let isLifted = model.lifted == pane
        let mode = model.layout.modeOf(pane)

        PaneContent(pane: pane, mode: mode)
            .padding(visible ? gap : 0)
            .frame(width: w, height: h)
            .overlay { if visible { DragOverlay(pane: pane, holdStart: holdStart, holdMs: settings.dragHoldMs).padding(gap) } }
            .contentShape(Rectangle())
            .onTapGesture {
                // 4.1.1: in portrait a tap on a card maximizes it where it can.
                if model.layout.portrait, model.layout.canMaximize(pane) { model.change { $0.toggleMaximized(pane) } }
            }
            .simultaneousGesture(hold, including: pane == .call ? .subviews : .all)
            .onChange(of: dragging) { _, active in
                if !active, model.lifted == pane { model.release() }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("slot_\(pane.rawValue)")
            .scaleEffect(isLifted ? 1.03 : 1)
            .shadow(color: .black.opacity(isLifted ? 0.5 : 0), radius: 16)
            .opacity(visible ? (isLifted && model.armed ? 0.8 : 1) : 0)
            .allowsHitTesting(visible)
            .animation(.easeOut(duration: 0.15), value: isLifted)
            .offset(isLifted || model.settling == pane ? model.dragOffset : .zero)
            // Every modifier above stays inside the card. `position` fills the parent, so nothing that hit-tests may
            // come after it.
            .position(x: left + w / 2, y: top + h / 2)
            .zIndex(isLifted || model.settling == pane ? 1 : 0)
    }

    /// Hold to lift (the ring fills), then drag onto another card to swap, or keep still to arm the picker.
    private var hold: some Gesture {
        LongPressGesture(minimumDuration: Double(settings.dragHoldMs) / 1000, maximumDistance: 12)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named("dash")))
            .updating($holdStart) { value, state, _ in
                if case .first(true) = value, state == nil { state = Date() }
            }
            .updating($dragging) { value, state, _ in
                if case .second(true, _) = value { state = true }
            }
            .onChanged { value in
                guard case .second(true, let drag) = value else { return }
                model.lift(pane)
                if let drag { model.dragChanged(pane, translation: drag.translation, location: drag.location, size: size) }
            }
            .onEnded { value in
                if case .second(true, _) = value { model.dragEnded(pane) }
            }
    }
}

/// The hold ring, the lifted card's outline, the armed ring in the centre, and the drop target's accent outline.
private struct DragOverlay: View {
    let pane: Pane
    let holdStart: Date?
    let holdMs: Int64
    @EnvironmentObject private var model: DashboardModel
    @Environment(\.appColors) private var colors

    var body: some View {
        ZStack {
            if model.target == pane {
                RoundedRectangle(cornerRadius: CardStyle.radius).strokeBorder(colors.accent, lineWidth: 3)
            }
            if model.lifted == pane {
                RoundedRectangle(cornerRadius: CardStyle.radius).strokeBorder(colors.text.opacity(0.8), lineWidth: 2)
                if model.armed {
                    Circle().stroke(colors.accent, lineWidth: 5).frame(width: 64, height: 64)
                    Text("Let go to choose").font(.geist(15, .medium)).foregroundStyle(colors.text)
                        .padding(.top, 100)
                }
            } else if let holdStart {
                // The ring starts filling a moment in, so a quick tap doesn't flash it.
                TimelineView(.animation) { ctx in
                    let elapsed = ctx.date.timeIntervalSince(holdStart) * 1000 - Double(PaneDrag.ringDelayMs)
                    let p = max(0, min(1, elapsed / Double(holdMs - PaneDrag.ringDelayMs)))
                    if p > 0 {
                        Circle().trim(from: 0, to: p).stroke(colors.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                            .rotationEffect(.degrees(-90)).frame(width: 64, height: 64)
                    }
                }
            }
        }
        .allowsHitTesting(false)
    }
}

/// Which card fills a pane.
struct PaneContent: View {
    let pane: Pane
    let mode: CardMode

    var body: some View {
        switch pane {
        case .speed: SpeedCard(mode: mode)
        case .clock: ClockCard(mode: mode)
        case .map: PlaceholderCard(pane: .map, mode: mode, icon: "map", note: "The map comes in the next build (0.3).")
        case .nav: PlaceholderCard(pane: .nav, mode: mode, icon: "arrow.triangle.turn.up.right.diamond", note: "Navigation comes with the map (0.3).")
        case .media: PlaceholderCard(pane: .media, mode: mode, icon: "music.note", note: "Media comes in 0.5.")
        case .garage: PlaceholderCard(pane: .garage, mode: mode, icon: "fuelpump", note: "The Garage comes in 0.4.")
        case .call: PlaceholderCard(pane: .call, mode: mode, icon: "phone", note: "A call is on.")
        }
    }
}
