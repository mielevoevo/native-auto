import XCTest
@testable import NativeAuto

private func assertRect(_ expected: FRect, _ actual: FRect, _ msg: String = "", file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertEqual(actual.left, expected.left, accuracy: 1e-6, "\(msg) left", file: file, line: line)
    XCTAssertEqual(actual.top, expected.top, accuracy: 1e-6, "\(msg) top", file: file, line: line)
    XCTAssertEqual(actual.right, expected.right, accuracy: 1e-6, "\(msg) right", file: file, line: line)
    XCTAssertEqual(actual.bottom, expected.bottom, accuracy: 1e-6, "\(msg) bottom", file: file, line: line)
}

/// Port of Android `LayoutModelTest`.
final class LayoutModelTests: XCTestCase {
    func testEveryLayoutTilesTheScreen() {
        for preset in LayoutPreset.allCases { for bigOnLeft in [true, false] { for split in [0.3, 0.5, 0.7] {
            let panes = defaultAssignments[preset]!
            let rects = computeRects(LayoutView(preset: preset, panes: panes), bigOnLeft: bigOnLeft, split: split)
            let shown = panes.map { rects[$0]! }
            let label = "\(preset) left=\(bigOnLeft) split=\(split)"
            XCTAssertEqual(shown.map { $0.width * $0.height }.reduce(0, +), 1, accuracy: 1e-5, label)
            for r in shown { XCTAssertTrue(r.left >= 0 && r.top >= 0 && r.right <= 1 + 1e-6 && r.bottom <= 1 + 1e-6, label) }
            for i in shown.indices { for j in (i + 1)..<shown.count {
                XCTAssertEqual(shown[i].intersect(shown[j]).areaOrZero, 0, accuracy: 1e-6, "\(label): overlap")
            } }
            for p in Pane.allCases where !panes.contains(p) { XCTAssertEqual(rects[p], parkedRect, label) }
        } } }
    }

    func testEveryLayoutHasAFullscreen() {
        for preset in LayoutPreset.allCases {
            let panes = defaultAssignments[preset]!
            for p in panes {
                let full = computeRects(LayoutView(preset: preset, panes: panes, fullscreen: p), bigOnLeft: true, split: 0.5)
                XCTAssertEqual(full[p], FRect(0, 0, 1, 1), "\(preset) \(p)")
                for o in panes where o != p { XCTAssertEqual(full[o]!.width * full[o]!.height, 0) }
            }
        }
    }

    func testNaturalModes() {
        XCTAssertEqual(naturalMode(.duo, 0), .expanded)
        XCTAssertEqual(naturalMode(.duo, 1), .compact)
        XCTAssertEqual(naturalMode(.sideBySide, 1), .expanded)
        XCTAssertEqual(naturalMode(.rows, 0), .wide)
        XCTAssertEqual(naturalMode(.quad, 3), .compact)
    }

    func testDefaultsHoldDistinctPanesOnePerSlot() {
        for preset in LayoutPreset.allCases {
            let panes = defaultAssignments[preset]!
            XCTAssertEqual(panes.count, preset.slots)
            XCTAssertEqual(Set(panes).count, panes.count)
        }
        XCTAssertEqual(Set(navQuad).count, 4)
    }

    func testCodecRoundTripsAndMatchesAndroidText() {
        var custom = defaultAssignments
        custom[.quad] = [.nav, .map, .clock, .speed]
        XCTAssertEqual(LayoutCodec.decode(LayoutCodec.encode(custom)), custom)
        XCTAssertEqual(LayoutCodec.encode(defaultAssignments),
                       "DUO=MAP,MEDIA,SPEED;SIDE_BY_SIDE=SPEED,MEDIA;ROWS=SPEED,MEDIA;QUAD=MAP,SPEED,MEDIA,CLOCK;" +
                       "STACK1=SPEED;STACK2=SPEED,MAP;STACK3=MAP,SPEED,MEDIA;BIG_TWO=MAP,SPEED,MEDIA;STACK4=MAP,SPEED,MEDIA,CLOCK")
    }

    func testBlankOrPartialTextFillsInTheDefaults() {
        XCTAssertEqual(LayoutCodec.decode(""), defaultAssignments)
        let partial = LayoutCodec.decode("DUO=MAP,SPEED,MEDIA")
        XCTAssertEqual(partial[.duo], [.map, .speed, .media])
        XCTAssertEqual(partial[.quad], defaultAssignments[.quad])
    }

    func testDamagedEntriesFallBackToThatLayoutsDefault() {
        let d = LayoutCodec.decode("DUO=MAP,MAP,SPEED;ROWS=SPEED;QUAD=MAP,SPEED,RADIO,CLOCK;SIDE_BY_SIDE=CLOCK,NAV;TRIPLE=MAP,SPEED,MEDIA;garbage")
        XCTAssertEqual(d[.duo], defaultAssignments[.duo])
        XCTAssertEqual(d[.rows], defaultAssignments[.rows])
        XCTAssertEqual(d[.quad], defaultAssignments[.quad])
        XCTAssertEqual(d[.sideBySide], [.clock, .nav])
    }

    func testUnknownPresetNamesMeanDuo() {
        XCTAssertEqual(LayoutCodec.preset("QUAD"), .quad)
        XCTAssertEqual(LayoutCodec.preset(""), .duo)
        XCTAssertEqual(LayoutCodec.preset("TRIPLE"), .duo)
    }
}

/// Port of Android `DashboardLayoutStateTest` (the Compose `Saver` tests aren't ported: iOS keeps the state in memory
/// across rotations, and what is saved between launches is `SavedLayout`, tested below).
final class DashboardLayoutStateTests: XCTestCase {
    private var now: Int64 = 1_000_000
    private func state(_ preset: LayoutPreset = .duo, _ assignments: [LayoutPreset: [Pane]] = defaultAssignments,
                       bigOnLeft: Bool = true, split: Double = 0.5) -> DashboardLayoutState {
        DashboardLayoutState(preset: preset, assignments: assignments, bigOnLeft: bigOnLeft, split: split, clock: { [unowned self] in self.now })
    }
    private func assertHidden(_ s: DashboardLayoutState, _ panes: Pane...) { for p in panes { XCTAssertFalse(s.isOnScreen(p), "\(p) hidden") } }

    func testDuoDefaultSplitsTheScreenInHalf() {
        let s = state()
        assertRect(FRect(0, 0, 0.5, 1), s.rectOf(.map))
        assertRect(FRect(0.5, 0, 1, 0.5), s.rectOf(.media))
        assertRect(FRect(0.5, 0.5, 1, 1), s.rectOf(.speed))
        assertHidden(s, .nav, .clock)
        XCTAssertEqual(s.modeOf(.map), .expanded)
        XCTAssertEqual(s.modeOf(.media), .compact)
        XCTAssertEqual(s.modeOf(.speed), .compact)
    }

    func testDuoHonoursTheSplit() {
        let s = state(split: 0.4)
        assertRect(FRect(0, 0, 0.4, 1), s.rectOf(.map))
        assertRect(FRect(0.4, 0, 1, 0.5), s.rectOf(.media))
    }

    func testDuoMaximizedCardFillsItsColumn() {
        var s = state(split: 0.6); s.toggleMaximized(.media)
        assertRect(FRect(0, 0, 0.6, 1), s.rectOf(.map))
        assertRect(FRect(0.6, 0, 1, 1), s.rectOf(.media))
        XCTAssertEqual(s.rectOf(.speed).height, 0)
        XCTAssertEqual(s.modeOf(.media), .expanded)
        XCTAssertEqual(s.modeOf(.speed), .compact)
    }

    func testMapFullscreenCollapsesTheCardColumnAndKeepsAMaximizedCard() {
        var s = state(); s.toggleMaximized(.speed); s.toggleFullscreen(.map)
        assertRect(FRect(0, 0, 1, 1), s.rectOf(.map))
        assertHidden(s, .media, .speed)
        XCTAssertEqual(s.modeOf(.map), .fullscreen)
        XCTAssertEqual(s.modeOf(.speed), .compact)
        s.back()
        XCTAssertEqual(s.modeOf(.speed), .expanded)
    }

    func testCardFullscreenTakesTheScreen() {
        for pane in [Pane.media, .speed] {
            var s = state(); s.toggleFullscreen(pane)
            assertRect(FRect(0, 0, 1, 1), s.rectOf(pane))
            for o in [Pane.map, .media, .speed] where o != pane { XCTAssertFalse(s.isOnScreen(o)) }
            XCTAssertEqual(s.modeOf(pane), .fullscreen)
        }
    }

    func testFullscreenOnAnotherPaneSwitchesDirectly() {
        var s = state(); s.toggleFullscreen(.media); s.toggleFullscreen(.speed)
        XCTAssertEqual(s.fullscreen, .speed)
    }

    func testTheTallPaneCantBeMaximized() {
        var s = state()
        XCTAssertFalse(s.canMaximize(.map))
        s.toggleMaximized(.map)
        XCTAssertNil(s.maximized)
        XCTAssertTrue(s.canMaximize(.media))
    }

    func testDuoSwapsMoveTheTallPaneAcrossAndTradeTheStackedOnes() {
        var s = state()
        XCTAssertFalse(s.swapsVertically(.map))
        s.swap(.map)
        XCTAssertFalse(s.bigOnLeft)
        assertRect(FRect(0.5, 0, 1, 1), s.rectOf(.map))
        assertRect(FRect(0, 0, 0.5, 0.5), s.rectOf(.media))
        XCTAssertTrue(s.swapsVertically(.media))
        s.swap(.media)
        XCTAssertEqual(s.assignments[.duo], [.map, .speed, .media])
        assertRect(FRect(0, 0, 0.5, 0.5), s.rectOf(.speed))
        assertRect(FRect(0, 0.5, 0.5, 1), s.rectOf(.media))
    }

    func testSplitIsClamped() {
        var s = state()
        s.setSplit(0.1); XCTAssertEqual(s.split, 0.3)
        s.setSplit(0.95); XCTAssertEqual(s.split, 0.7)
    }

    func testSideBySideShowsTwoTallExpandedPanes() {
        var s = state(.sideBySide)
        assertRect(FRect(0, 0, 0.5, 1), s.rectOf(.speed))
        assertRect(FRect(0.5, 0, 1, 1), s.rectOf(.media))
        assertHidden(s, .map, .nav, .clock)
        XCTAssertEqual(s.modeOf(.speed), .expanded)
        XCTAssertFalse(s.canMaximize(.speed))
        s.swap(.speed)
        XCTAssertEqual(s.assignments[.sideBySide], [.media, .speed])
    }

    func testRowsAreFullWidthAndWideAndMaximizeToTheWholeHeight() {
        var s = state(.rows)
        assertRect(FRect(0, 0, 1, 0.5), s.rectOf(.speed))
        assertRect(FRect(0, 0.5, 1, 1), s.rectOf(.media))
        XCTAssertEqual(s.modeOf(.speed), .wide)
        s.toggleMaximized(.speed)
        assertRect(FRect(0, 0, 1, 1), s.rectOf(.speed))
        XCTAssertEqual(s.modeOf(.speed), .expanded)
        s.swap(.speed)
        XCTAssertEqual(s.assignments[.rows], [.media, .speed])
    }

    func testQuadIsTwoByTwoReadLikeAPage() {
        var s = state(.quad)
        assertRect(FRect(0, 0, 0.5, 0.5), s.rectOf(.map))
        assertRect(FRect(0.5, 0, 1, 0.5), s.rectOf(.speed))
        assertRect(FRect(0, 0.5, 0.5, 1), s.rectOf(.media))
        assertRect(FRect(0.5, 0.5, 1, 1), s.rectOf(.clock))
        assertHidden(s, .nav)
        for p in [Pane.map, .speed, .media, .clock] { XCTAssertEqual(s.modeOf(p), .compact) }
        XCTAssertTrue(s.canMaximize(.map))
        s.toggleMaximized(.map)
        assertRect(FRect(0, 0, 0.5, 1), s.rectOf(.map))
        XCTAssertFalse(s.isOnScreen(.media))
        XCTAssertEqual(s.modeOf(.map), .expanded)
    }

    func testQuadSwapTradesWithThePaneBelow() {
        var s = state(.quad); s.swap(.speed)
        XCTAssertEqual(s.assignments[.quad], [.map, .clock, .media, .speed])
    }

    func testSwitchingLayoutStartsUnzoomedAndParksWhatItDoesntShow() {
        var s = state(); s.toggleMaximized(.speed); s.toggleFullscreen(.media)
        s.setPreset(.sideBySide)
        XCTAssertNil(s.fullscreen); XCTAssertNil(s.maximized)
        XCTAssertEqual(s.rectOf(.map), parkedRect)
        s.toggleFullscreen(.map)
        XCTAssertNil(s.fullscreen)
    }

    func testEachLayoutRemembersItsOwnPanes() {
        var s = state()
        s.setPreset(.quad); s.assign(.clock, .nav)
        s.setPreset(.duo)
        XCTAssertEqual(s.view.panes, defaultAssignments[.duo])
        s.setPreset(.quad)
        XCTAssertEqual(s.view.panes, [.map, .speed, .media, .nav])
    }

    func testPickingAPaneAlreadyShownTradesPlaces() {
        var s = state(); s.assign(.map, .speed)
        XCTAssertEqual(s.view.panes, [.speed, .media, .map])
    }

    func testPickingAPaneNotShownReplacesTheSlotsPane() {
        var s = state(); s.assign(.media, .clock)
        XCTAssertEqual(s.view.panes, [.map, .clock, .speed])
        XCTAssertFalse(s.isOnScreen(.media))
    }

    func testAReplacedPaneTakesItsZoomWithIt() {
        var s = state(); s.toggleMaximized(.media); s.assign(.media, .nav)
        XCTAssertNil(s.maximized)
        var t = state(); t.toggleFullscreen(.speed); t.assign(.speed, .clock)
        XCTAssertNil(t.fullscreen)
    }

    func testPickingTheSamePaneChangesNothing() {
        var s = state()
        let before = s.lastArrangedAtMs
        s.assign(.map, .map)
        XCTAssertEqual(s.view.panes, defaultAssignments[.duo])
        XCTAssertEqual(s.lastArrangedAtMs, before)
    }

    func testStatusCornerSitsInTheTopRightPane() {
        XCTAssertEqual(state().statusPane, .media)
        XCTAssertEqual(state(bigOnLeft: false).statusPane, .map)
        var a = state(); a.swap(.media); XCTAssertEqual(a.statusPane, .speed)
        var b = state(); b.toggleMaximized(.speed); XCTAssertEqual(b.statusPane, .speed)
        var c = state(); c.toggleFullscreen(.map); XCTAssertEqual(c.statusPane, .map)
        XCTAssertEqual(state(.sideBySide).statusPane, .media)
        XCTAssertEqual(state(.rows).statusPane, .speed)
        XCTAssertEqual(state(.quad).statusPane, .speed)
        var d = state(.quad); d.toggleMaximized(.clock); XCTAssertEqual(d.statusPane, .clock)
    }

    func testBackUnwindsFullscreenThenMaximizedThenFallsThrough() {
        var s = state(); s.toggleMaximized(.media); s.toggleFullscreen(.map)
        XCTAssertTrue(s.back()); XCTAssertNil(s.fullscreen); XCTAssertEqual(s.maximized, .media)
        XCTAssertTrue(s.back()); XCTAssertNil(s.maximized)
        XCTAssertFalse(s.back())
    }

    private let navDuo = LayoutView(preset: .duo, panes: [.map, .speed, .media])

    func testAnOverrideShowsOnTopAndGoesAwayCleanly() {
        var s = state(.sideBySide)
        s.applyAuto(navDuo)
        XCTAssertEqual(s.view, navDuo)
        XCTAssertTrue(s.isOnScreen(.map))
        XCTAssertEqual(s.userView.preset, .sideBySide)
        s.clearAuto()
        XCTAssertEqual(s.view.preset, .sideBySide)
        XCTAssertFalse(s.isOnScreen(.map))
    }

    func testZoomingDuringAnOverrideKeepsIt() {
        var s = state(.sideBySide)
        let before = s.lastArrangedAtMs
        s.applyAuto(navDuo)
        s.toggleMaximized(.speed)
        XCTAssertEqual(s.maximized, .speed)
        var expected = navDuo; expected.maximized = .speed
        XCTAssertEqual(s.override, expected)
        XCTAssertEqual(s.lastArrangedAtMs, before)
        s.clearAuto()
        XCTAssertNil(s.maximized)
    }

    func testArrangingByHandDuringAnOverrideMakesItTheRidersOwn() {
        var s = state(.sideBySide)
        s.applyAuto(navDuo)
        now += 5_000
        s.swap(.speed)
        XCTAssertNil(s.override)
        XCTAssertEqual(s.preset, .duo)
        XCTAssertEqual(s.assignments[.duo], [.map, .media, .speed])
        XCTAssertEqual(s.lastArrangedAtMs, now)
        s.clearAuto()
        XCTAssertEqual(s.view.preset, .duo)
    }

    // Can swap (13a)
    func testDuoOnlyTheTallPaneSwaps() {
        let s = DashboardLayoutState()
        XCTAssertTrue(s.canSwap(.map)); XCTAssertFalse(s.canSwap(.media)); XCTAssertFalse(s.canSwap(.speed))
    }

    func testSideBySideAndRowsBothPanesSwap() {
        for p in [LayoutPreset.sideBySide, .rows] {
            let s = DashboardLayoutState(preset: p)
            for pane in s.view.panes { XCTAssertTrue(s.canSwap(pane)) }
        }
    }

    func testQuadNobodySwaps() {
        let s = DashboardLayoutState(preset: .quad)
        for pane in s.view.panes { XCTAssertFalse(s.canSwap(pane)) }
    }

    func testAHiddenOrFullscreenPaneHasNoSwap() {
        var s = DashboardLayoutState()
        XCTAssertFalse(s.canSwap(.clock))
        s.toggleFullscreen(.map)
        XCTAssertFalse(s.canSwap(.map))
    }

    func testWithACardMaximizedTheTallPaneStillSwaps() {
        var s = DashboardLayoutState(); s.toggleMaximized(.speed)
        XCTAssertTrue(s.canSwap(.map))
    }
}

/// Port of Android `PaneDragTest` / `DropTargetTest`.
final class PaneDragTests: XCTestCase {
    // A 2400 × 1080 screen, as on Android, in fractions.
    private func at(_ x: Double, _ y: Double) -> (Double, Double) { (x / 2400, y / 1080) }

    func testDuoTheTallSlotOnTheLeftTwoStackedOnTheRight() {
        let l = DashboardLayoutState()
        var p = at(300, 900); XCTAssertEqual(l.paneAt(x: p.0, y: p.1), .map)
        p = at(2000, 200); XCTAssertEqual(l.paneAt(x: p.0, y: p.1), .media)
        p = at(2000, 900); XCTAssertEqual(l.paneAt(x: p.0, y: p.1), .speed)
    }

    func testQuadEachCorner() {
        let l = DashboardLayoutState(preset: .quad)
        let found = [at(300, 200), at(2100, 200), at(300, 900), at(2100, 900)].compactMap { l.paneAt(x: $0.0, y: $0.1) }
        XCTAssertEqual(Set(found), Set(l.view.panes))
    }

    func testAHiddenPaneIsNeverATarget() {
        var l = DashboardLayoutState(); l.toggleFullscreen(.speed)
        let p = at(300, 900)
        XCTAssertEqual(l.paneAt(x: p.0, y: p.1), .speed)
    }

    private let home = FRect(0, 0, 0.5, 1)
    private let others: [(Pane, FRect)] = [(.media, FRect(0.5, 0, 1, 0.5)), (.speed, FRect(0.5, 0.5, 1, 1))]

    func testTheCardCoveredMostWinsEvenWithTheFingerElsewhere() {
        XCTAssertEqual(pickDropTarget(lifted: home.translated(0.45, 0.4), home: home, fingerX: 0.6, fingerY: 0.2, others: others), .speed)
    }

    func testACardDroppedStraightOnItsNeighbourSwapsWithIt() {
        XCTAssertNotNil(pickDropTarget(lifted: home.translated(0.5, 0), home: home, fingerX: 0.75, fingerY: 0.25, others: others))
    }

    func testASmallOverlapFallsBackToTheFinger() {
        XCTAssertEqual(pickDropTarget(lifted: home.translated(0.05, 0), home: home, fingerX: 0.7, fingerY: 0.8, others: others), .speed)
    }

    func testNearestCentreOnceItTravelledHalfway() {
        let gap: [(Pane, FRect)] = [(.media, FRect(0.7, 0, 1, 1))]
        let farLeft = FRect(0, 0, 0.3, 1)
        XCTAssertNil(pickDropTarget(lifted: farLeft.translated(0.02, 0), home: farLeft, fingerX: 0.17, fingerY: 0.5, others: gap))
        XCTAssertEqual(pickDropTarget(lifted: farLeft.translated(0.4, 0), home: farLeft, fingerX: 0.55, fingerY: 0.5, others: gap), .media)
    }

    func testNoOtherCardsNoTarget() {
        XCTAssertNil(pickDropTarget(lifted: home, home: home, fingerX: 0.1, fingerY: 0.1, others: []))
    }
}
