import Foundation

// Port of Android `ui/dashboard/LayoutModel.kt`: panes, presets, card modes and each pane's place as fractions of
// the screen (derived from intent, never stored).

/// A rectangle in screen fractions (0...1), y down.
struct FRect: Equatable {
    var left: Double, top: Double, right: Double, bottom: Double
    init(_ left: Double, _ top: Double, _ right: Double, _ bottom: Double) {
        self.left = left; self.top = top; self.right = right; self.bottom = bottom
    }
    var width: Double { right - left }
    var height: Double { bottom - top }
    var centerX: Double { (left + right) / 2 }
    var centerY: Double { (top + bottom) / 2 }
    var areaOrZero: Double { width <= 0 || height <= 0 ? 0 : width * height }
    func translated(_ dx: Double, _ dy: Double) -> FRect { FRect(left + dx, top + dy, right + dx, bottom + dy) }
    func containsInclusive(_ x: Double, _ y: Double) -> Bool { x >= left && x <= right && y >= top && y <= bottom }
    func intersect(_ o: FRect) -> FRect {
        FRect(Swift.max(left, o.left), Swift.max(top, o.top), Swift.min(right, o.right), Swift.min(bottom, o.bottom))
    }
}

/// Everything that can fill a slot. Each pane exists once; a layout shows some of them.
enum Pane: String, CaseIterable, Codable {
    case map = "MAP", media = "MEDIA", speed = "SPEED", nav = "NAV", clock = "CLOCK", garage = "GARAGE"
    /// The call card: never chosen by the rider; it takes over a slot while a call is on.
    case call = "CALL"

    var title: String {
        switch self {
        case .map: "Map"; case .media: "Media"; case .speed: "Speed"; case .nav: "Navigation"
        case .clock: "Clock"; case .garage: "Garage"; case .call: "Call"
        }
    }

    /// What the pane picker offers.
    static let pickable: [Pane] = [.map, .media, .speed, .nav, .clock, .garage]
}

/// How a card draws itself, chosen from the slot it's in, never from its measured size.
enum CardMode: Equatable {
    case compact, wide, expanded, fullscreen
    /// Portrait 4-stack slot: one line of essentials.
    case mini
    /// Portrait half-width slot under the big pane.
    case narrow
}

enum LayoutPreset: String, CaseIterable, Codable {
    case duo = "DUO", sideBySide = "SIDE_BY_SIDE", rows = "ROWS", quad = "QUAD"
    case stack1 = "STACK1", stack2 = "STACK2", stack3 = "STACK3", bigTwo = "BIG_TWO", stack4 = "STACK4"

    var slots: Int {
        switch self {
        case .duo: 3; case .sideBySide: 2; case .rows: 2; case .quad: 4
        case .stack1: 1; case .stack2: 2; case .stack3: 3; case .bigTwo: 3; case .stack4: 4
        }
    }

    var portrait: Bool {
        switch self {
        case .stack1, .stack2, .stack3, .bigTwo, .stack4: true
        default: false
        }
    }

    var label: String {
        switch self {
        case .duo: "Duo"; case .sideBySide: "Side by side"; case .rows: "Rows"; case .quad: "Quad"
        case .stack1: "One"; case .stack2: "Two stacked"; case .stack3: "Three stacked"; case .bigTwo: "Big + two"
        case .stack4: "Four stacked"
        }
    }
}

/// One column of the layout: its share of the width, and its slots from top to bottom.
struct LayoutColumn: Equatable {
    var weight: Double
    var slots: [Int]
}

/// Duo's tall column takes `split` of the width; every other layout splits evenly.
func columnsOf(_ preset: LayoutPreset, bigOnLeft: Bool, split: Double) -> [LayoutColumn] {
    switch preset {
    case .duo:
        let big = LayoutColumn(weight: split, slots: [0])
        let stack = LayoutColumn(weight: 1 - split, slots: [1, 2])
        return bigOnLeft ? [big, stack] : [stack, big]
    case .sideBySide: return [LayoutColumn(weight: 0.5, slots: [0]), LayoutColumn(weight: 0.5, slots: [1])]
    case .rows: return [LayoutColumn(weight: 1, slots: [0, 1])]
    case .quad: return [LayoutColumn(weight: 0.5, slots: [0, 2]), LayoutColumn(weight: 0.5, slots: [1, 3])]
    case .stack1, .stack2, .stack3, .bigTwo, .stack4: return [LayoutColumn(weight: 1, slots: Array(0..<preset.slots))]
    }
}

/// Portrait big + two: the big pane's share of the height.
let bigTwoTop = 0.55

func naturalMode(_ preset: LayoutPreset, _ slot: Int) -> CardMode {
    switch preset {
    case .duo: slot == 0 ? .expanded : .compact
    case .sideBySide: .expanded
    case .rows: .wide
    case .quad: .compact
    case .stack1, .stack2: .expanded
    case .stack3: .wide
    case .bigTwo: slot == 0 ? .expanded : .narrow
    case .stack4: .mini
    }
}

/// What's on screen: the layout, which pane is in each slot, and the zoom modes.
struct LayoutView: Equatable {
    var preset: LayoutPreset
    var panes: [Pane]
    var fullscreen: Pane? = nil
    var maximized: Pane? = nil
}

/// Off screen: zero width at the left edge.
let parkedRect = FRect(0, 0, 0, 1)

func computeRects(_ view: LayoutView, bigOnLeft: Bool, split: Double) -> [Pane: FRect] {
    var out: [Pane: FRect] = [:]
    for p in Pane.allCases { out[p] = parkedRect }
    if view.preset.portrait { return portraitRects(view, out) }
    let columns = columnsOf(view.preset, bigOnLeft: bigOnLeft, split: split)
    var slotOf: [Pane: Int] = [:]
    for (i, p) in view.panes.enumerated() { slotOf[p] = i }
    let full = view.fullscreen.flatMap { slotOf[$0] }
    let max = view.maximized.flatMap { slotOf[$0] }
    let colWeights = full != nil ? columns.map { $0.slots.contains(full!) ? 1.0 : 0.0 } : columns.map(\.weight)
    let sum = colWeights.reduce(0, +)
    let total = sum > 0 ? sum : 1
    var x = 0.0
    for (c, column) in columns.enumerated() {
        let width = colWeights[c] / total
        let focus = full.flatMap { column.slots.contains($0) ? $0 : nil } ?? max.flatMap { column.slots.contains($0) ? $0 : nil }
        var y = 0.0
        for slot in column.slots {
            let h = focus == nil ? 1.0 / Double(column.slots.count) : (slot == focus ? 1.0 : 0.0)
            if slot < view.panes.count { out[view.panes[slot]] = FRect(x, y, x + width, y + h) }
            y += h
        }
        x += width
    }
    return out
}

private func portraitRects(_ view: LayoutView, _ base: [Pane: FRect]) -> [Pane: FRect] {
    var out = base
    let panes = view.panes
    let n = panes.count
    let full = view.fullscreen.flatMap { panes.firstIndex(of: $0) }
    let max = full == nil && n > 1 ? view.maximized.flatMap { panes.firstIndex(of: $0) } : nil
    if let max {
        // A maximized portrait pane takes the height of two cards (2/n); the others share the rest as slim rows.
        let big = 2.0 / Double(n), small = (1 - big) / Double(n - 1)
        var y = 0.0
        for (slot, pane) in panes.enumerated() {
            let h = slot == max ? big : small
            out[pane] = FRect(0, y, 1, y + h)
            y += h
        }
        return out
    }
    func slotRect(_ slot: Int) -> FRect {
        if view.preset == .bigTwo {
            switch slot {
            case 0: return FRect(0, 0, 1, bigTwoTop)
            case 1: return FRect(0, bigTwoTop, 0.5, 1)
            default: return FRect(0.5, bigTwoTop, 1, 1)
            }
        }
        return FRect(0, Double(slot) / Double(n), 1, Double(slot + 1) / Double(n))
    }
    for (slot, pane) in panes.enumerated() {
        let r = slotRect(slot)
        if let full {
            if slot == full { out[pane] = FRect(0, 0, 1, 1) }
            else if (slot < full && view.preset != .bigTwo) || (view.preset == .bigTwo && slot == 0) {
                out[pane] = FRect(r.left, 0, r.right, 0)
            } else {
                out[pane] = FRect(r.left, 1, r.right, 1)
            }
        } else {
            out[pane] = r
        }
    }
    return out
}

let defaultAssignments: [LayoutPreset: [Pane]] = [
    .duo: [.map, .media, .speed],
    .sideBySide: [.speed, .media],
    .rows: [.speed, .media],
    .quad: [.map, .speed, .media, .clock],
    .stack1: [.speed],
    .stack2: [.speed, .map],
    .stack3: [.map, .speed, .media],
    .bigTwo: [.map, .speed, .media],
    .stack4: [.map, .speed, .media, .clock],
]

/// Quad while navigating: map, navigation, speed, media.
let navQuad: [Pane] = [.map, .nav, .speed, .media]

/// Slot assignments as text: `DUO=MAP,MEDIA,SPEED;QUAD=…`. Decoding never fails: a damaged entry falls back to that
/// layout's default. Same text as Android writes.
enum LayoutCodec {
    static func encode(_ assignments: [LayoutPreset: [Pane]]) -> String {
        LayoutPreset.allCases.compactMap { p in
            assignments[p].map { "\(p.rawValue)=\($0.map(\.rawValue).joined(separator: ","))" }
        }.joined(separator: ";")
    }

    static func decode(_ text: String) -> [LayoutPreset: [Pane]] {
        var parsed: [LayoutPreset: [Pane]] = [:]
        for entry in text.split(separator: ";", omittingEmptySubsequences: false) {
            let parts = entry.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2, let preset = LayoutPreset(rawValue: parts[0].trimmingCharacters(in: .whitespaces)) else { continue }
            let names = parts[1].split(separator: ",", omittingEmptySubsequences: false)
            let panes = names.compactMap { Pane(rawValue: $0.trimmingCharacters(in: .whitespaces)) }
            guard panes.count == names.count, panes.count == preset.slots, Set(panes).count == panes.count else { continue }
            parsed[preset] = panes
        }
        var out: [LayoutPreset: [Pane]] = [:]
        for p in LayoutPreset.allCases { out[p] = parsed[p] ?? defaultAssignments[p]! }
        return out
    }

    static func preset(_ name: String) -> LayoutPreset { LayoutPreset(rawValue: name) ?? .duo }

    /// A saved portrait preset, or nil (blank, unknown, or a landscape name).
    static func portraitPreset(_ name: String) -> LayoutPreset? { LayoutPreset(rawValue: name).flatMap { $0.portrait ? $0 : nil } }
}

/// The order in which a call takes a pane's slot, first to go first.
let callTakeoverOrder: [Pane] = [.media, .clock, .garage, .speed, .nav, .map]

/// The pane the call card replaces, or nil: never a fullscreen pane, only panes with room on screen.
func callTakeoverPane(_ view: LayoutView, bigOnLeft: Bool, split: Double) -> Pane? {
    if view.fullscreen != nil { return nil }
    let rects = computeRects(view, bigOnLeft: bigOnLeft, split: split)
    let onScreen = view.panes.filter { rects[$0]!.width > 0 && rects[$0]!.height > 0 }
    return callTakeoverOrder.first { onScreen.contains($0) } ?? onScreen.first
}

extension LayoutView {
    /// This view with the call card in `replaced`'s slot (a zoom on that pane moves to the call card).
    func withCall(_ replaced: Pane?) -> LayoutView {
        guard let replaced, panes.contains(replaced) else { return self }
        return LayoutView(preset: preset,
                          panes: panes.map { $0 == replaced ? .call : $0 },
                          fullscreen: fullscreen == replaced ? .call : fullscreen,
                          maximized: maximized == replaced ? .call : maximized)
    }
}

/// What a rotation shows: same panes, same order; only the geometry changes. A maximized pane is dropped.
func rotatedView(_ view: LayoutView, toPortrait: Bool, lastPortrait: LayoutPreset? = nil, lastLandscape: LayoutPreset? = nil,
                 fillFrom: [Pane] = [.media, .speed, .map]) -> LayoutView {
    if view.preset.portrait == toPortrait { return view }
    var panes = view.panes
    let preset: LayoutPreset
    if toPortrait {
        switch panes.count {
        case 1: preset = .stack1
        case 2: preset = .stack2
        case 3: preset = lastPortrait.flatMap { $0.portrait && $0.slots == 3 ? $0 : nil } ?? .stack3
        default: preset = .stack4
        }
    } else {
        switch panes.count {
        case 1:
            panes.append(fillFrom.first { !panes.contains($0) }!)
            preset = .sideBySide
        case 2: preset = lastLandscape.flatMap { !$0.portrait && $0.slots == 2 ? $0 : nil } ?? .sideBySide
        case 3: preset = .duo
        default: preset = .quad
        }
    }
    return LayoutView(preset: preset, panes: panes, fullscreen: view.fullscreen.flatMap { panes.contains($0) ? $0 : nil }, maximized: nil)
}
