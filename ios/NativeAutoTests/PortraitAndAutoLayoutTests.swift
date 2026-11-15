import XCTest
@testable import NativeAuto

/// Port of Android `PortraitLayoutTest`.
final class PortraitLayoutTests: XCTestCase {
    func testPortraitPresetsAreTheStacksAndBigPlusTwo() {
        let portrait = LayoutPreset.allCases.filter(\.portrait)
        XCTAssertEqual(portrait, [.stack1, .stack2, .stack3, .bigTwo, .stack4])
        XCTAssertEqual(portrait.map(\.slots), [1, 2, 3, 3, 4])
    }

    func testStacksSplitTheHeightEvenly() {
        for n in 1...4 {
            let preset = LayoutPreset.allCases.first { $0.portrait && $0.slots == n && $0 != .bigTwo }!
            let panes = defaultAssignments[preset]!
            let rects = computeRects(LayoutView(preset: preset, panes: panes), bigOnLeft: true, split: 0.5)
            for (i, p) in panes.enumerated() { XCTAssertEqual(rects[p], FRect(0, Double(i) / Double(n), 1, Double(i + 1) / Double(n))) }
        }
    }

    func testBigPlusTwoIsTheBigPaneOverTwoEqualHalves() {
        let panes = defaultAssignments[.bigTwo]!
        let r = computeRects(LayoutView(preset: .bigTwo, panes: panes), bigOnLeft: true, split: 0.5)
        XCTAssertEqual(r[panes[0]], FRect(0, 0, 1, bigTwoTop))
        XCTAssertEqual(r[panes[1]], FRect(0, bigTwoTop, 0.5, 1))
        XCTAssertEqual(r[panes[2]], FRect(0.5, bigTwoTop, 1, 1))
    }

    func testAFullscreenPortraitPaneTakesEverythingAndTheRestFoldIntoAnEdge() {
        for preset in LayoutPreset.allCases where preset.portrait {
            let panes = defaultAssignments[preset]!
            for p in panes {
                let r = computeRects(LayoutView(preset: preset, panes: panes, fullscreen: p), bigOnLeft: true, split: 0.5)
                XCTAssertEqual(r[p], FRect(0, 0, 1, 1))
                for o in panes where o != p {
                    XCTAssertEqual(r[o]!.height, 0)
                    XCTAssertTrue(r[o]!.top == 0 || r[o]!.top == 1)
                }
            }
        }
    }

    func testNaturalModesInPortrait() {
        XCTAssertEqual(naturalMode(.stack1, 0), .expanded)
        XCTAssertEqual(naturalMode(.stack2, 1), .expanded)
        XCTAssertEqual(naturalMode(.stack3, 2), .wide)
        XCTAssertEqual(naturalMode(.bigTwo, 0), .expanded)
        XCTAssertEqual(naturalMode(.bigTwo, 1), .narrow)
        XCTAssertEqual(naturalMode(.stack4, 3), .mini)
    }

    func testLandscapeToPortraitKeepsThePanesAndTheirOrder() {
        XCTAssertEqual(rotatedView(LayoutView(preset: .duo, panes: [.map, .speed, .media]), toPortrait: true),
                       LayoutView(preset: .stack3, panes: [.map, .speed, .media]))
        XCTAssertEqual(rotatedView(LayoutView(preset: .sideBySide, panes: [.speed, .media]), toPortrait: true),
                       LayoutView(preset: .stack2, panes: [.speed, .media]))
        XCTAssertEqual(rotatedView(LayoutView(preset: .rows, panes: [.map, .nav]), toPortrait: true).preset, .stack2)
        XCTAssertEqual(rotatedView(LayoutView(preset: .quad, panes: [.map, .nav, .speed, .media]), toPortrait: true),
                       LayoutView(preset: .stack4, panes: [.map, .nav, .speed, .media]))
    }

    func testThreePanesUseTheShapeLastUsed() {
        let v = LayoutView(preset: .duo, panes: [.map, .speed, .media])
        XCTAssertEqual(rotatedView(v, toPortrait: true).preset, .stack3)
        XCTAssertEqual(rotatedView(v, toPortrait: true, lastPortrait: .bigTwo).preset, .bigTwo)
        XCTAssertEqual(rotatedView(v, toPortrait: true, lastPortrait: .stack4).preset, .stack3)
    }

    func testPortraitToLandscapeOnePaneGetsANeighbour() {
        XCTAssertEqual(rotatedView(LayoutView(preset: .stack3, panes: [.map, .speed, .media]), toPortrait: false).preset, .duo)
        XCTAssertEqual(rotatedView(LayoutView(preset: .bigTwo, panes: [.map, .speed, .media]), toPortrait: false).preset, .duo)
        XCTAssertEqual(rotatedView(LayoutView(preset: .stack4, panes: [.map, .speed, .media, .clock]), toPortrait: false).preset, .quad)
        let one = rotatedView(LayoutView(preset: .stack1, panes: [.speed]), toPortrait: false)
        XCTAssertEqual(one.preset, .sideBySide)
        XCTAssertEqual(one.panes.first, .speed)
        XCTAssertEqual(Set(one.panes).count, 2)
        XCTAssertEqual(rotatedView(LayoutView(preset: .stack2, panes: [.speed, .map]), toPortrait: false, lastLandscape: .rows).preset, .rows)
    }

    func testAFullscreenPaneStaysAMaximizedOneIsDropped() {
        XCTAssertEqual(rotatedView(LayoutView(preset: .duo, panes: [.map, .speed, .media], fullscreen: .map), toPortrait: true).fullscreen, .map)
        XCTAssertNil(rotatedView(LayoutView(preset: .duo, panes: [.map, .speed, .media], maximized: .media), toPortrait: true).maximized)
    }

    func testTheLayoutStateTurnsWithThePhoneAndBack() {
        var s = DashboardLayoutState(preset: .duo, assignments: [.duo: [.speed, .map, .media]])
        s.setPortrait(true)
        XCTAssertTrue(s.portrait)
        XCTAssertEqual(s.preset, .stack3)
        XCTAssertEqual(s.view.panes, [.speed, .map, .media])
        s.setPortrait(false)
        XCTAssertEqual(s.preset, .duo)
        XCTAssertEqual(s.view.panes, [.speed, .map, .media])
    }

    func testEachOrientationRemembersItsOwnPreset() {
        var s = DashboardLayoutState(preset: .rows)
        s.setPortrait(true)
        XCTAssertEqual(s.preset, .stack2)
        s.setPreset(.bigTwo)
        s.setPortrait(false)
        XCTAssertEqual(s.preset, .duo)
        XCTAssertEqual(s.lastPortraitPreset, .bigTwo)
        XCTAssertEqual(s.lastLandscapePreset, .rows)
        s.setPortrait(true)
        XCTAssertEqual(s.preset, .bigTwo)
    }

    func testRotatingIsNotAnArrangementByHand() {
        var s = DashboardLayoutState()
        let before = s.lastArrangedAtMs
        s.setPortrait(true)
        XCTAssertEqual(s.lastArrangedAtMs, before)
    }

    func testAPresetOfTheOtherOrientationIsIgnored() {
        var s = DashboardLayoutState()
        s.setPreset(.stack3)
        XCTAssertEqual(s.preset, .duo)
        s.setPortrait(true)
        s.setPreset(.quad)
        XCTAssertEqual(s.preset, .stack3)
    }

    func testPortraitMaximizeTakesTwoCardsTheRestShareSlimRows() {
        var s = DashboardLayoutState(); s.setPortrait(true)
        for p in s.view.panes { XCTAssertTrue(s.canMaximize(p)) }
        let p = s.view.panes[1]
        s.toggleMaximized(p)
        XCTAssertEqual(s.modeOf(p), .expanded)
        let others = s.view.panes.filter { $0 != p }
        for o in others { XCTAssertEqual(s.modeOf(o), .mini) }
        let rects = computeRects(s.view, bigOnLeft: true, split: 0.5)
        XCTAssertEqual(rects[p]!.height, 2.0 / 3.0, accuracy: 0.001)
        for o in others { XCTAssertEqual(rects[o]!.height, 1.0 / 6.0, accuracy: 0.001) }
        s.toggleMaximized(p)
        XCTAssertEqual(s.modeOf(p), .wide)
    }

    func testFourStackMaximizeTakesHalfTheHeight() {
        var s = DashboardLayoutState(); s.setPortrait(true); s.setPreset(.stack4)
        let p = s.view.panes[3]
        s.toggleMaximized(p)
        let rects = computeRects(s.view, bigOnLeft: true, split: 0.5)
        XCTAssertEqual(rects[p]!.height, 0.5, accuracy: 0.001)
        for o in s.view.panes where o != p { XCTAssertEqual(rects[o]!.height, 1.0 / 6.0, accuracy: 0.001) }
    }

    func testTwoPanesHaveNoMaximizeNorDoesTheBigPaneOfBigPlusTwo() {
        var s = DashboardLayoutState(); s.setPortrait(true); s.setPreset(.stack2)
        for p in s.view.panes { XCTAssertFalse(s.canMaximize(p)) }
        s.setPreset(.bigTwo)
        let panes = s.view.panes
        XCTAssertFalse(s.canMaximize(panes[0])); XCTAssertTrue(s.canMaximize(panes[1])); XCTAssertTrue(s.canMaximize(panes[2]))
    }

    func testPortraitSwapOnlyBetweenTwoStackedPanes() {
        var s = DashboardLayoutState(); s.setPortrait(true)
        for p in s.view.panes { XCTAssertFalse(s.canSwap(p)) }
        s.setPreset(.stack2)
        for p in s.view.panes { XCTAssertTrue(s.canSwap(p)) }
        let top = s.view.panes[0]
        s.swap(top)
        XCTAssertEqual(s.view.panes[1], top)
    }

    func testAnAutoLayoutOverrideTurnsWithThePhone() {
        var s = DashboardLayoutState(preset: .sideBySide)
        s.applyAuto(LayoutView(preset: .duo, panes: [.map, .media, .speed]))
        s.setPortrait(true)
        XCTAssertEqual(s.view.preset, .stack3)
        XCTAssertEqual(s.view.panes, [.map, .media, .speed])
        XCTAssertEqual(s.userView.preset, .stack2)
    }

    func testAPortraitStartWithNothingSavedDerivesFromLandscape() {
        var a = defaultAssignments; a[.duo] = [.speed, .map, .media]
        let s = SavedLayout(bigOnLeft: true, split: 0.5, preset: "DUO", slots: LayoutCodec.encode(a)).toLayoutState(portrait: true)
        XCTAssertTrue(s.portrait)
        XCTAssertEqual(s.preset, .stack3)
        XCTAssertEqual(s.view.panes, [.speed, .map, .media])
        XCTAssertEqual(s.lastLandscapePreset, .duo)
    }

    func testAPortraitStartUsesThePresetSavedForPortrait() {
        var a = defaultAssignments; a[.bigTwo] = [.nav, .speed, .map]
        let s = SavedLayout(bigOnLeft: true, split: 0.5, preset: "DUO", slots: LayoutCodec.encode(a), portraitPreset: "BIG_TWO").toLayoutState(portrait: true)
        XCTAssertEqual(s.preset, .bigTwo)
        XCTAssertEqual(s.view.panes, [.nav, .speed, .map])
    }

    func testSavingKeepsTheLandscapePresetWhereOlderVersionsReadIt() {
        var s = DashboardLayoutState(preset: .quad); s.setPortrait(true); s.setPreset(.stack4)
        XCTAssertEqual(s.toSaved().preset, "QUAD")
        XCTAssertEqual(s.toSaved().portraitPreset, "STACK4")
        s.setPortrait(false)
        XCTAssertEqual(s.toSaved().preset, "QUAD")
        XCTAssertEqual(s.toSaved().portraitPreset, "STACK4")
    }

    func testADamagedSavedPortraitPresetIsIgnored() {
        XCTAssertNil(LayoutCodec.portraitPreset("DUO"))
        XCTAssertNil(LayoutCodec.portraitPreset("nonsense"))
        XCTAssertNil(LayoutCodec.portraitPreset(""))
        XCTAssertEqual(LayoutCodec.portraitPreset("STACK2"), .stack2)
        XCTAssertEqual(SavedLayout(bigOnLeft: true, split: 0.5, preset: "STACK3").sanitized().preset, "DUO")
    }

    func testSanitizedClampsTheSplit() {
        XCTAssertEqual(SavedLayout(bigOnLeft: true, split: .nan).sanitized().split, 0.5)
        XCTAssertEqual(SavedLayout(bigOnLeft: true, split: 0.95).sanitized().split, 0.7)
    }

    func testNavigationInThePortraitFourStackTakesTheNavigationSet() {
        var s = DashboardLayoutState(preset: .stack4, portrait: true)
        AutoLayout.onNavigationStarted(&s, enabled: true)
        XCTAssertEqual(s.view, LayoutView(preset: .stack4, panes: navQuad))
        AutoLayout.onNavigationEnded(&s)
        XCTAssertEqual(s.view.preset, .stack4)
    }

    func testNavigationWithNoMapInPortraitShowsBigPlusTwoWithTheMap() {
        var s = DashboardLayoutState(); s.setPortrait(true); s.setPreset(.stack2); s.assign(.map, .media)
        XCTAssertFalse(s.view.panes.contains(.map))
        let v = AutoLayout.navigationView(s.userView, s.assignments)
        XCTAssertEqual(v?.preset, .bigTwo)
        XCTAssertEqual(v?.panes.first, .map)
    }

    func testACallTakesMediasSlotInAPortraitStackAndGivesItBack() {
        var s = DashboardLayoutState(); s.setPortrait(true)
        let before = s.view.panes
        s.setCallActive(true)
        XCTAssertEqual(s.view.panes, before.map { $0 == .media ? .call : $0 })
        s.setCallActive(false)
        XCTAssertEqual(s.view.panes, before)
    }

    func testACallNeverTakesAFullscreenPortraitPane() {
        var s = DashboardLayoutState(); s.setPortrait(true); s.toggleFullscreen(.media)
        s.setCallActive(true)
        XCTAssertFalse(s.view.panes.contains(.call))
    }
}

/// Port of Android `CallTakeoverTest`.
final class CallTakeoverTests: XCTestCase {
    func testInDuoTheCallReplacesMedia() {
        var s = DashboardLayoutState()
        s.setCallActive(true)
        XCTAssertEqual(s.view.panes, [.map, .call, .speed])
        XCTAssertTrue(s.isOnScreen(.call)); XCTAssertFalse(s.isOnScreen(.media))
        XCTAssertEqual(s.modeOf(.call), .compact)
        s.setCallActive(false)
        XCTAssertEqual(s.view.panes, [.map, .media, .speed])
    }

    func testTakeoverOrder() {
        let cases: [([Pane], [Pane])] = [
            ([.map, .clock, .speed], [.map, .call, .speed]),
            ([.map, .garage, .speed], [.map, .call, .speed]),
            ([.map, .nav, .speed], [.map, .nav, .call]),
            ([.map, .nav, .clock], [.map, .nav, .call]),
            ([.map, .nav, .garage], [.map, .nav, .call]),
        ]
        for (panes, expected) in cases {
            var s = DashboardLayoutState(assignments: [.duo: panes])
            s.setCallActive(true)
            XCTAssertEqual(s.view.panes, expected, "\(panes)")
        }
        var m = DashboardLayoutState(preset: .sideBySide, assignments: [.sideBySide: [.map, .nav]])
        m.setCallActive(true)
        XCTAssertEqual(m.view.panes, [.map, .call])
    }

    func testAFullscreenPaneIsNeverTakenOver() {
        var s = DashboardLayoutState()
        s.toggleFullscreen(.speed)
        s.setCallActive(true)
        XCTAssertFalse(s.view.panes.contains(.call))
        XCTAssertEqual(s.modeOf(.speed), .fullscreen)
        s.back()
        XCTAssertTrue(s.view.panes.contains(.call))
    }

    func testOnlyPanesWithRoomCount() {
        var s = DashboardLayoutState()
        s.toggleMaximized(.speed)
        s.setCallActive(true)
        XCTAssertEqual(s.view.panes, [.map, .media, .call])
        XCTAssertEqual(s.view.maximized, .call)
        XCTAssertEqual(s.modeOf(.call), .expanded)
    }

    func testAZoomOnTheCallCardIsAZoomOnThePaneUnderIt() {
        var s = DashboardLayoutState()
        s.setCallActive(true)
        s.toggleMaximized(.call)
        XCTAssertEqual(s.modeOf(.call), .expanded)
        s.setCallActive(false)
        XCTAssertEqual(s.view.maximized, .media)
    }

    func testTheCallCardHasNoSwapAndTheArrangementIsUntouched() {
        var s = DashboardLayoutState()
        s.setCallActive(true)
        XCTAssertFalse(s.canSwap(.call))
        XCTAssertEqual(s.assignments[.duo], defaultAssignments[.duo])
    }

    func testQuadGivesUpTheLowestPriorityOfFour() {
        var s = DashboardLayoutState(preset: .quad)
        s.setCallActive(true)
        XCTAssertEqual(s.view.panes, [.map, .speed, .call, .clock])
    }
}

/// Port of Android `AutoLayoutTest`.
final class AutoLayoutTests: XCTestCase {
    private var now: Int64 = 10_000_000
    private func state(_ preset: LayoutPreset = .duo, _ a: [LayoutPreset: [Pane]] = defaultAssignments) -> DashboardLayoutState {
        DashboardLayoutState(preset: preset, assignments: a, clock: { [unowned self] in self.now })
    }

    func testQuadSwitchesToItsNavigationSetAndBack() {
        var s = state(.quad)
        AutoLayout.onNavigationStarted(&s, enabled: true)
        XCTAssertEqual(s.view, LayoutView(preset: .quad, panes: navQuad))
        XCTAssertTrue(s.isOnScreen(.nav)); XCTAssertFalse(s.isOnScreen(.clock))
        AutoLayout.onNavigationEnded(&s)
        XCTAssertEqual(s.view.panes, defaultAssignments[.quad])
    }

    func testALayoutWithoutTheMapSwitchesToDuo() {
        var s = state(.sideBySide)
        AutoLayout.onNavigationStarted(&s, enabled: true)
        XCTAssertEqual(s.view, LayoutView(preset: .duo, panes: defaultAssignments[.duo]!))
        AutoLayout.onNavigationEnded(&s)
        XCTAssertEqual(s.view.preset, .sideBySide)
    }

    func testADuoWithoutTheMapGetsItInTheTallSlot() {
        var s = state(.rows, [.duo: [.speed, .media, .clock]])
        AutoLayout.onNavigationStarted(&s, enabled: true)
        XCTAssertEqual(s.view.panes, [.map, .media, .clock])
    }

    func testADuoWithTheMapInASmallSlotKeepsIt() {
        var s = state(.duo, [.duo: [.speed, .map, .media]])
        AutoLayout.onNavigationStarted(&s, enabled: true)
        XCTAssertNil(s.override)
    }

    func testAVisibleMapIsLeftAlone() {
        var s = state()
        AutoLayout.onNavigationStarted(&s, enabled: true)
        XCTAssertNil(s.override)
        s.toggleMaximized(.speed)
        XCTAssertNil(AutoLayout.navigationView(s.userView, s.assignments))
    }

    func testAMapHiddenByZoomIsBroughtBackUnzoomed() {
        var f = state(); f.toggleFullscreen(.speed)
        AutoLayout.onNavigationStarted(&f, enabled: true)
        XCTAssertEqual(f.view, LayoutView(preset: .duo, panes: defaultAssignments[.duo]!))
        AutoLayout.onNavigationEnded(&f)
        XCTAssertEqual(f.fullscreen, .speed)
        var m = state(.duo, [.duo: [.speed, .map, .media]]); m.toggleMaximized(.media)
        AutoLayout.onNavigationStarted(&m, enabled: true)
        XCTAssertNil(m.maximized)
        XCTAssertTrue(m.isOnScreen(.map))
    }

    func testMapFullscreenCountsAsShown() {
        var s = state(); s.toggleFullscreen(.map)
        AutoLayout.onNavigationStarted(&s, enabled: true)
        XCTAssertNil(s.override)
    }

    func testTurnedOffItDoesNothing() {
        var s = state(.sideBySide)
        AutoLayout.onNavigationStarted(&s, enabled: false)
        XCTAssertNil(s.override)
    }

    func testItHoldsOffAfterTheRiderArrangesTheLayout() {
        var s = state()
        s.setPreset(.sideBySide)
        now += AutoLayout.holdMs - 1
        AutoLayout.onNavigationStarted(&s, enabled: true)
        XCTAssertNil(s.override)
        now += 1
        AutoLayout.onNavigationStarted(&s, enabled: true)
        XCTAssertEqual(s.view.preset, .duo)
    }

    func testAChangeByHandDuringNavigationIsKept() {
        var s = state(.quad)
        AutoLayout.onNavigationStarted(&s, enabled: true)
        s.assign(.media, .clock)
        AutoLayout.onNavigationEnded(&s)
        XCTAssertEqual(s.view.panes, [.map, .nav, .speed, .clock])
        XCTAssertEqual(s.assignments[.quad], s.view.panes)
    }

    func testZoomingDuringNavigationDoesntStopTheLayoutComingBack() {
        var s = state(.quad)
        AutoLayout.onNavigationStarted(&s, enabled: true)
        s.toggleFullscreen(.nav)
        AutoLayout.onNavigationEnded(&s)
        XCTAssertEqual(s.view.panes, defaultAssignments[.quad])
        XCTAssertNil(s.fullscreen)
    }

    func testEndingWithoutAnOverrideChangesNothing() {
        var s = state(.rows); s.toggleMaximized(.speed)
        AutoLayout.onNavigationEnded(&s)
        XCTAssertEqual(s.view.preset, .rows)
        XCTAssertEqual(s.maximized, .speed)
    }

    func testAQuadAlreadyInItsNavigationSetIsntOverridden() {
        var s = state(.quad, [.quad: navQuad])
        AutoLayout.onNavigationStarted(&s, enabled: true)
        XCTAssertNil(s.override)
    }
}
