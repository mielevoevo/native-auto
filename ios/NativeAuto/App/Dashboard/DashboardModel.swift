import SwiftUI

/// The dashboard's state: the layout (intent only, `DashboardLayoutState`), which sheet is open, and the drag in
/// progress. Every arrangement change animates with the panes' spring and is saved.
@MainActor
final class DashboardModel: ObservableObject {
    @Published private(set) var layout: DashboardLayoutState
    /// The slot whose pane picker is open.
    @Published var pickerFor: Pane?
    @Published var showLayouts = false
    @Published var showSettings = false

    // Drag to swap (12a / 13a)
    @Published private(set) var lifted: Pane?
    @Published private(set) var settling: Pane?
    @Published private(set) var dragOffset: CGSize = .zero
    @Published private(set) var target: Pane?
    @Published private(set) var armed = false
    private var moved = false
    private var armTask: Task<Void, Never>?

    private let settings: SettingsStore
    static let spring = Animation.spring(response: 0.42, dampingFraction: 0.86)

    init(settings: SettingsStore) {
        self.settings = settings
        layout = settings.savedLayout.toLayoutState(portrait: false)
    }

    /// Changes the layout with the panes' spring, then saves the rider's own arrangement.
    func change(_ f: (inout DashboardLayoutState) -> Void) {
        var l = layout
        f(&l)
        withAnimation(Self.spring) { layout = l }
        let saved = l.toSaved()
        if saved != settings.savedLayout { settings.savedLayout = saved }
    }

    func setPortrait(_ portrait: Bool) {
        guard portrait != layout.portrait else { return }
        change { $0.setPortrait(portrait) }
    }

    // MARK: - Drag

    func lift(_ pane: Pane) {
        guard lifted != pane else { return }
        lifted = pane
        settling = nil
        moved = false
        armed = false
        target = nil
        dragOffset = .zero
        Haptics.lift()
        armTask?.cancel()
        armTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(PaneDrag.armMs) * 1_000_000)
            guard let self, !Task.isCancelled, self.lifted == pane, !self.moved else { return }
            self.armed = true
            Haptics.armed()
        }
    }

    /// `translation` and `location` in points; `size` is the dashboard's.
    func dragChanged(_ pane: Pane, translation: CGSize, location: CGPoint, size: CGSize) {
        guard lifted == pane, size.width > 0, size.height > 0 else { return }
        if !moved, hypot(translation.width, translation.height) > 10 {
            moved = true
            armed = false
            armTask?.cancel()
        }
        guard moved else { return }
        dragOffset = translation
        let t = layout.dropTarget(for: pane, dx: translation.width / size.width, dy: translation.height / size.height,
                                  fx: location.x / size.width, fy: location.y / size.height)
        if t != target { target = t; if t != nil { Haptics.tick() } }
    }

    func dragEnded(_ pane: Pane) {
        guard lifted == pane else { return }
        armTask?.cancel()
        if moved {
            let swapWith = target
            lifted = nil
            target = nil
            settling = pane
            var l = layout
            if let swapWith { l.assign(pane, swapWith) }
            withAnimation(Self.spring) {
                layout = l
                dragOffset = .zero
            }
            if swapWith != nil { settings.savedLayout = l.toSaved(); Haptics.tap() }
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 600_000_000)
                if self?.settling == pane { self?.settling = nil }
            }
        } else {
            let open = armed
            release()
            if open { pickerFor = pane }
        }
    }

    /// The touch was taken away (or lifted without moving).
    func release() {
        armTask?.cancel()
        lifted = nil
        armed = false
        target = nil
        withAnimation(Self.spring) { dragOffset = .zero }
    }
}
