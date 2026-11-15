import Foundation

let minSplit = 0.3
let maxSplit = 0.7

/// Port of Android `DashboardLayoutState`: layout *intent* only (preset, panes per preset, zoom, Duo's side and
/// width). Each pane's place (`rectOf`) and mode (`modeOf`) are derived. A value type: SwiftUI animates when it
/// changes.
///
/// Auto layout (`applyAuto` / `clearAuto`) shows a temporary view on top of the rider's own. Zooming works on what's
/// shown and keeps it; arranging by hand makes the shown layout the rider's own first.
struct DashboardLayoutState {
    private(set) var preset: LayoutPreset
    private(set) var assignments: [LayoutPreset: [Pane]]
    private var baseFullscreen: Pane?
    private var baseMaximized: Pane?
    private(set) var bigOnLeft: Bool
    private(set) var split: Double
    private(set) var portrait: Bool
    private(set) var lastLandscapePreset: LayoutPreset?
    private(set) var lastPortraitPreset: LayoutPreset?
    private(set) var override: LayoutView?
    private(set) var lastArrangedAtMs: Int64
    private(set) var callActive = false
    var clock: () -> Int64

    init(preset: LayoutPreset = .duo, assignments: [LayoutPreset: [Pane]] = defaultAssignments, fullscreen: Pane? = nil,
         maximized: Pane? = nil, bigOnLeft: Bool = true, split: Double = 0.5, override: LayoutView? = nil,
         lastArrangedAtMs: Int64 = Int64.min / 2, portrait: Bool = false, lastLandscapePreset: LayoutPreset? = nil,
         lastPortraitPreset: LayoutPreset? = nil, clock: @escaping () -> Int64 = monotonicMs) {
        self.preset = preset
        self.assignments = defaultAssignments.merging(assignments) { _, new in new }
        self.baseFullscreen = fullscreen
        self.baseMaximized = maximized
        self.bigOnLeft = bigOnLeft
        self.split = split
        self.override = override
        self.lastArrangedAtMs = lastArrangedAtMs
        self.portrait = portrait
        self.lastLandscapePreset = lastLandscapePreset
        self.lastPortraitPreset = lastPortraitPreset
        self.clock = clock
    }

    // MARK: - What's on screen

    private var baseView: LayoutView {
        override ?? LayoutView(preset: preset, panes: assignments[preset]!, fullscreen: baseFullscreen, maximized: baseMaximized)
    }

    var view: LayoutView {
        let b = baseView
        return callActive ? b.withCall(callTakeoverPane(b, bigOnLeft: bigOnLeft, split: split)) : b
    }

    var fullscreen: Pane? { view.fullscreen }
    var maximized: Pane? { view.maximized }

    var rects: [Pane: FRect] { computeRects(view, bigOnLeft: bigOnLeft, split: split) }

    func rectOf(_ pane: Pane) -> FRect { rects[pane]! }

    func isOnScreen(_ pane: Pane) -> Bool { let r = rectOf(pane); return r.width > 0 && r.height > 0 }

    /// The pane in the screen's top-right corner: its header carries the status corner.
    var statusPane: Pane {
        let r = rects
        return Pane.allCases.first { p in
            let x = r[p]!; return x.width > 0 && x.height > 0 && x.left <= 0.999 && 0.999 < x.right && x.top <= 0.001 && 0.001 < x.bottom
        } ?? Pane.allCases.first { isOnScreen($0) }!
    }

    func modeOf(_ pane: Pane) -> CardMode {
        let v = view
        let slot = v.panes.firstIndex(of: pane)
        if v.fullscreen == pane, slot != nil { return .fullscreen }
        if v.fullscreen != nil || slot == nil { return .compact }   // hidden
        if v.maximized == pane { return .expanded }
        if v.maximized != nil && v.preset.portrait { return .mini }
        return naturalMode(v.preset, slot!)
    }

    func canMaximize(_ pane: Pane) -> Bool {
        let v = view
        if v.preset.portrait {
            return v.panes.count >= 3 && v.panes.contains(pane) && !(v.preset == .bigTwo && v.panes.first == pane)
        }
        return (columnOf(pane)?.slots.count ?? 0) > 1
    }

    func swapsVertically(_ pane: Pane) -> Bool { view.preset.portrait || canMaximize(pane) }

    /// The header's swap button: Side by side, Rows, Duo's tall pane and two stacked portrait panes.
    func canSwap(_ pane: Pane) -> Bool {
        let v = view
        guard v.fullscreen == nil, let slot = v.panes.firstIndex(of: pane) else { return false }
        switch v.preset {
        case .sideBySide, .rows, .stack2: return true
        case .duo: return slot == 0
        case .quad, .stack1, .stack3, .bigTwo, .stack4: return false
        }
    }

    private func columnOf(_ pane: Pane) -> LayoutColumn? {
        guard let slot = view.panes.firstIndex(of: pane) else { return nil }
        return columnsOf(view.preset, bigOnLeft: bigOnLeft, split: split).first { $0.slots.contains(slot) }
    }

    // MARK: - Zoom: works on what's shown, keeps an override

    mutating func toggleFullscreen(_ pane: Pane) {
        guard view.panes.contains(pane) else { return }
        zoom(fullscreen: view.fullscreen == pane ? nil : pane, maximized: view.maximized)
    }

    mutating func toggleMaximized(_ pane: Pane) {
        guard canMaximize(pane) else { return }
        zoom(fullscreen: view.fullscreen, maximized: view.maximized == pane ? nil : pane)
    }

    /// Unwinds fullscreen, then maximized. Returns false when there was nothing to unwind.
    @discardableResult
    mutating func back() -> Bool {
        if view.fullscreen != nil { zoom(fullscreen: nil, maximized: view.maximized); return true }
        if view.maximized != nil { zoom(fullscreen: nil, maximized: nil); return true }
        return false
    }

    mutating func setCallActive(_ active: Bool) { callActive = active }

    private mutating func zoom(fullscreen f: Pane?, maximized m: Pane?) {
        let full = real(f), max = real(m)
        if var o = override {
            o.fullscreen = full; o.maximized = max
            override = o
        } else {
            baseFullscreen = full
            baseMaximized = max
        }
    }

    private func real(_ pane: Pane?) -> Pane? {
        pane == .call ? (callTakeoverPane(baseView, bigOnLeft: bigOnLeft, split: split) ?? pane) : pane
    }

    // MARK: - Arrangement: the rider's own, ends an override

    /// A layout of the other orientation is ignored. Starts unzoomed.
    mutating func setPreset(_ preset: LayoutPreset) {
        guard preset.portrait == portrait else { return }
        arrangeByHand()
        self.preset = preset
        baseFullscreen = nil
        baseMaximized = nil
    }

    /// The pane picker / a drop: show `pane` in `slotPane`'s slot; if `pane` is already shown the two trade places.
    mutating func assign(_ slotPane: Pane, _ pane: Pane) {
        guard slotPane != pane else { return }
        arrangeByHand()
        var list = assignments[preset]!
        guard let i = list.firstIndex(of: slotPane) else { return }
        if let j = list.firstIndex(of: pane) { list[j] = slotPane }
        list[i] = pane
        assignments[preset] = list
        if !list.contains(slotPane) {
            if baseFullscreen == slotPane { baseFullscreen = nil }
            if baseMaximized == slotPane { baseMaximized = nil }
        }
    }

    /// The header's swap button: with the pane above / below, or (alone in its column) across.
    mutating func swap(_ pane: Pane) {
        arrangeByHand()
        var list = assignments[preset]!
        guard let slot = list.firstIndex(of: pane) else { return }
        let column = columnsOf(preset, bigOnLeft: bigOnLeft, split: split).first { $0.slots.contains(slot) }!
        if column.slots.count > 1 {
            let other = column.slots.first { $0 != slot }!
            list.swapAt(slot, other)
            assignments[preset] = list
        } else if preset == .duo {
            bigOnLeft.toggle()
        } else if preset == .sideBySide {
            list.swapAt(0, 1)
            assignments[preset] = list
        }
    }

    mutating func setSplit(_ fraction: Double) {
        arrangeByHand()
        split = Swift.min(maxSplit, Swift.max(minSplit, fraction))
    }

    private mutating func arrangeByHand() {
        if let o = override {
            preset = o.preset
            assignments[o.preset] = o.panes
            baseFullscreen = o.fullscreen
            baseMaximized = o.maximized
            override = nil
        }
        lastArrangedAtMs = clock()
    }

    // MARK: - Orientation

    /// The phone turned: same panes, same order, the counterpart preset. Not an arrangement by hand.
    mutating func setPortrait(_ portrait: Bool) {
        guard portrait != self.portrait else { return }
        if self.portrait { lastPortraitPreset = preset } else { lastLandscapePreset = preset }
        let turned = rotatedView(userView, toPortrait: portrait, lastPortrait: lastPortraitPreset, lastLandscape: lastLandscapePreset)
        self.portrait = portrait
        preset = turned.preset
        assignments[turned.preset] = turned.panes
        baseFullscreen = turned.fullscreen
        baseMaximized = nil
        override = override.map { rotatedView($0, toPortrait: portrait, lastPortrait: lastPortraitPreset, lastLandscape: lastLandscapePreset) }
    }

    // MARK: - Auto layout

    mutating func applyAuto(_ view: LayoutView) { override = view }
    mutating func clearAuto() { override = nil }

    /// The rider's own layout, whatever an override shows on top of it.
    var userView: LayoutView {
        LayoutView(preset: preset, panes: assignments[preset]!, fullscreen: baseFullscreen, maximized: baseMaximized)
    }

    func nowMs() -> Int64 { clock() }
}

/// Monotonic milliseconds.
func monotonicMs() -> Int64 { Int64(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1_000_000) }

/// What is saved between launches: the rider's own arrangement, never an override. Port of `SavedLayout`.
struct SavedLayout: Equatable {
    var bigOnLeft: Bool
    var split: Double
    var preset: String = ""
    var slots: String = ""
    var portraitPreset: String = ""

    /// A damaged or hand-edited store must not produce an unusable layout.
    func sanitized() -> SavedLayout {
        var s = self
        s.split = split.isFinite ? Swift.min(maxSplit, Swift.max(minSplit, split)) : 0.5
        let p = LayoutCodec.preset(preset)
        s.preset = p.portrait ? LayoutPreset.duo.rawValue : p.rawValue
        s.portraitPreset = LayoutCodec.portraitPreset(portraitPreset)?.rawValue ?? ""
        s.slots = LayoutCodec.encode(LayoutCodec.decode(slots))
        return s
    }

    /// A fresh layout state for the current orientation. A portrait start with nothing saved for portrait derives it
    /// from the landscape layout.
    func toLayoutState(portrait: Bool = false, clock: @escaping () -> Int64 = monotonicMs) -> DashboardLayoutState {
        let landscape = LayoutCodec.preset(preset)
        let portraitSaved = LayoutCodec.portraitPreset(portraitPreset)
        let assignments = LayoutCodec.decode(slots)
        if !portrait {
            return DashboardLayoutState(preset: landscape, assignments: assignments, bigOnLeft: bigOnLeft, split: split,
                                        lastPortraitPreset: portraitSaved, clock: clock)
        }
        let turned = rotatedView(LayoutView(preset: landscape, panes: assignments[landscape]!), toPortrait: true, lastPortrait: portraitSaved)
        var withDerived = assignments
        if portraitSaved == nil { withDerived[turned.preset] = turned.panes }
        return DashboardLayoutState(preset: portraitSaved ?? turned.preset, assignments: withDerived, bigOnLeft: bigOnLeft,
                                    split: split, portrait: true, lastLandscapePreset: landscape,
                                    lastPortraitPreset: portraitSaved, clock: clock)
    }
}

extension DashboardLayoutState {
    func toSaved() -> SavedLayout {
        SavedLayout(bigOnLeft: bigOnLeft, split: split,
                    preset: (portrait ? lastLandscapePreset ?? .duo : preset).rawValue,
                    slots: LayoutCodec.encode(assignments),
                    portraitPreset: (portrait ? preset : lastPortraitPreset)?.rawValue ?? "")
    }
}

/// Event-driven layout switching for navigation (15c wires it to the navigator). Port of `AutoLayout.kt`.
enum AutoLayout {
    /// No switch within this long of the rider arranging the layout by hand.
    static let holdMs: Int64 = 2 * 60_000

    static func onNavigationStarted(_ layout: inout DashboardLayoutState, enabled: Bool) {
        guard enabled, layout.nowMs() - layout.lastArrangedAtMs >= holdMs else { return }
        if let v = navigationView(layout.userView, layout.assignments) { layout.applyAuto(v) }
    }

    static func onNavigationEnded(_ layout: inout DashboardLayoutState) { layout.clearAuto() }

    /// The layout to show while navigating, or nil when the rider's already shows the map.
    static func navigationView(_ user: LayoutView, _ assignments: [LayoutPreset: [Pane]]) -> LayoutView? {
        if user.preset == .quad || user.preset == .stack4 {
            let v = LayoutView(preset: user.preset, panes: navQuad)
            return v == user ? nil : v
        }
        let mapShown = user.panes.contains(.map) && (user.fullscreen == nil || user.fullscreen == .map) &&
            (user.maximized == nil || user.maximized == .map || !sharesColumn(user, .map, user.maximized!))
        if mapShown { return nil }
        let target: LayoutPreset = user.preset.portrait ? .bigTwo : .duo
        let duo = assignments[target]!
        let panes = duo.contains(.map) ? duo : [.map] + duo.dropFirst()
        return LayoutView(preset: target, panes: panes)
    }

    private static func sharesColumn(_ view: LayoutView, _ a: Pane, _ b: Pane) -> Bool {
        guard let sa = view.panes.firstIndex(of: a), let sb = view.panes.firstIndex(of: b) else { return false }
        return columnsOf(view.preset, bigOnLeft: true, split: 0.5).contains { $0.slots.contains(sa) && $0.slots.contains(sb) }
    }
}

/// Drag to swap (12a / 13a): which card a dragged one would swap with. All rectangles in screen fractions.
///  1. The card the lifted one covers most, if that is at least `overlapThreshold` of the smaller of the two.
///  2. Otherwise the card under the finger.
///  3. Otherwise the nearest card by centre, once the lifted card travelled at least half the way to it.
enum PaneDrag {
    static let ringDelayMs: Int64 = 150
    /// Held still this long after the lift, the picker is armed.
    static let armMs: Int64 = 700
    static let overlapThreshold = 0.2
    static let holdChoicesMs: [Int64] = [1_000, 2_000, 3_000]
    static let defaultHoldMs: Int64 = 1_000
}

func pickDropTarget(lifted: FRect, home: FRect, fingerX: Double, fingerY: Double, others: [(Pane, FRect)]) -> Pane? {
    guard !others.isEmpty else { return nil }
    let liftedArea = lifted.areaOrZero
    var best: (Pane, Double)?
    for (pane, r) in others {
        let overlap = lifted.intersect(r).areaOrZero
        let share = overlap / max(min(liftedArea, r.areaOrZero), 1e-6)
        if share >= PaneDrag.overlapThreshold, overlap > (best?.1 ?? -1) { best = (pane, overlap) }
    }
    if let best { return best.0 }
    if let under = others.first(where: { $0.1.containsInclusive(fingerX, fingerY) }) { return under.0 }
    func dist(_ ax: Double, _ ay: Double, _ bx: Double, _ by: Double) -> Double { ((ax - bx) * (ax - bx) + (ay - by) * (ay - by)).squareRoot() }
    let nearest = others.min { dist($0.1.centerX, $0.1.centerY, lifted.centerX, lifted.centerY) < dist($1.1.centerX, $1.1.centerY, lifted.centerX, lifted.centerY) }!
    let travelled = dist(lifted.centerX, lifted.centerY, home.centerX, home.centerY)
    let toNearest = dist(nearest.1.centerX, nearest.1.centerY, home.centerX, home.centerY)
    return travelled >= toNearest / 2 ? nearest.0 : nil
}

extension DashboardLayoutState {
    /// The on-screen pane whose rectangle holds the point (screen fractions).
    func paneAt(x: Double, y: Double) -> Pane? {
        let r = rects
        return Pane.allCases.first { p in let q = r[p]!; return q.width > 0 && q.height > 0 && q.containsInclusive(x, y) }
    }

    /// The drop target for `pane` dragged by (dx, dy) screen fractions with the finger at (fx, fy).
    func dropTarget(for pane: Pane, dx: Double, dy: Double, fx: Double, fy: Double) -> Pane? {
        let home = rectOf(pane)
        let others = Pane.allCases.filter { $0 != pane && isOnScreen($0) }.map { ($0, rectOf($0)) }
        return pickDropTarget(lifted: home.translated(dx, dy), home: home, fingerX: fx, fingerY: fy, others: others)
    }
}
