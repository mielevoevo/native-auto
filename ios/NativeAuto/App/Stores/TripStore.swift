import Foundation
import SwiftUI

/// The trip as the screen sees it: owns the `TripTracker`, the GPS source, auto-pause / auto-end and the
/// once-a-second refresh, and turns SI stats into display units (`toUiState`). Counterpart of Android's
/// `TripTracker` + `SpeedometerViewModel` + `TripLogViewModel` (start / pause / resume / end / dismiss).
@MainActor
final class TripStore: ObservableObject {
    @Published private(set) var ui: SpeedometerUiState = .empty
    @Published private(set) var state: TripState = .idle
    @Published private(set) var locationGranted = true
    /// End needs two taps within `armMs` (the button turns red: "Tap again to end").
    @Published private(set) var endArmed = false
    /// Reset (discard) needs two taps too.
    @Published private(set) var resetArmed = false
    /// The latest fix from whichever reader holds the GPS: the trip while it runs, the live read otherwise. The map
    /// and the navigator follow it (Android `LiveLocation`: one GPS subscription for everything).
    @Published private(set) var fix: SpeedSample?

    static let armMs: Int64 = 3_000

    let clock = SystemMonotonicClock()
    private let scheduler = MainQueueScheduler()
    private let settings: SettingsStore
    private let source: SwitchableSource
    let tracker: TripTracker
    private var autoTrip: AutoTrip!
    private var live: Cancellable?
    private var liveFix: SpeedSample?
    private var ticker: Timer?
    private var endTimer: Cancellable?
    private var resetTimer: Cancellable?
    /// Others watching every tracker change (15d: the trip recorder, the Garage's live odometer).
    private var observers: [() -> Void] = []

    func addTrackerObserver(_ f: @escaping () -> Void) { observers.append(f) }

    init(settings: SettingsStore) {
        self.settings = settings
        let real = CoreLocationSource(clock: clock)
        source = SwitchableSource(real: real, fake: FakeSpeedSampleSource(clock: clock, scheduler: scheduler))
        source.useFake = settings.useFakeGps
        tracker = TripTracker(source: source, clock: clock, scheduler: scheduler)
        autoTrip = AutoTrip(tracker: tracker, clock: clock, scheduler: scheduler) { [weak settings] in
            MainActor.assumeIsolated { (settings?.autoPauseMin ?? AutoTripConfig.defaultPauseMin, settings?.autoEndMin ?? AutoTripConfig.defaultEndMin) }
        }
        tracker.onChange = { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                for o in self.observers { o() }
                self.autoTrip.update()
                if self.state != self.tracker.state {
                    self.state = self.tracker.state
                    self.updateLive()
                }
                if self.tracker.state == .running, let f = self.tracker.lastFix, f != self.fix { self.fix = f }
                self.refresh()
            }
        }
        real.onAuthorization = { [weak self] ok in
            MainActor.assumeIsolated {
                self?.locationGranted = ok || !(self?.source.real.denied ?? false)
                self?.updateLive()
                self?.refresh()
            }
        }
        locationGranted = !real.denied
    }

    // MARK: - App life

    /// The app came to the front: the screen stays on, the live speed and the 1 s refresh run.
    func appActive() {
        UIApplication.shared.isIdleTimerDisabled = true
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
        // After the ticker exists: the live read runs only while the app is in front (`ticker != nil`). Called the
        // other way round it never started unless a permission callback came later (15b race, found in 15c).
        updateLive()
        refresh()
        #if DEBUG
        // Screenshots and checks in the simulator: `-debug_start_trip 1` starts a trip at launch.
        if UserDefaults.standard.bool(forKey: "debug_start_trip"), state == .idle {
            start()
            // `-debug_end_trip_after_s N` ends it again (the history then holds a saved trip for screenshots).
            let after = UserDefaults.standard.double(forKey: "debug_end_trip_after_s")
            if after > 0 {
                Task { @MainActor [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(after * 1e9))
                    self?.tracker.end()
                }
            }
        }
        #endif
    }

    /// The app left the front: iOS stops the GPS for an app without background location, so the hero stops too.
    func appInactive() {
        ticker?.invalidate(); ticker = nil
        live?.cancel(); live = nil
    }

    /// The app is closing (swiped away): the trip ends and is saved (the recorder writes it synchronously), like
    /// Android's `onTaskRemoved`.
    func appTerminating() {
        tracker.end()
    }

    // MARK: - Trip controls

    func start() { tracker.start() }
    func pause() { tracker.pause() }
    func resume() { tracker.resume() }

    func tapEnd() {
        if endArmed {
            disarmEnd()
            tracker.end()
            Haptics.confirm()
        } else {
            endArmed = true
            endTimer = scheduler.schedule(afterMs: Self.armMs) { [weak self] in
                MainActor.assumeIsolated { self?.endArmed = false }
            }
        }
    }

    private func disarmEnd() { endArmed = false; endTimer?.cancel(); endTimer = nil }

    /// The summary's dismiss: the ended trip is cleared (it is already saved).
    func dismissSummary() { tracker.discard() }

    /// Reset = discard (9a), two taps within 3 s.
    func tapReset() {
        if resetArmed {
            resetArmed = false; resetTimer?.cancel()
            tracker.discard()
            Haptics.confirm()
        } else {
            resetArmed = true
            resetTimer = scheduler.schedule(afterMs: Self.armMs) { [weak self] in
                MainActor.assumeIsolated { self?.resetArmed = false }
            }
        }
    }

    /// Debug: switch between the real GPS and the scripted drive. Like Android, it also resets the trip.
    func setFakeGps(_ on: Bool) {
        settings.useFakeGps = on
        tracker.discard()
        source.useFake = on
        live?.cancel(); live = nil
        liveFix = nil
        updateLive()
        refresh()
    }

    func requestLocation() { source.real.requestPermission() }

    /// Debug "Simulate navigation": every reader drives along `route` until it is set back to nil.
    func setSimulatedRoute(_ route: [GeoPoint]?) {
        source.simulated = route.map { SimulatedDriveSource(route: $0, clock: clock, scheduler: scheduler) }
    }

    // MARK: - Live speed without a trip

    /// With no trip running the hero still shows the speed, from its own read of the same source.
    private func updateLive() {
        let want = state != .running && ticker != nil
        if want, live == nil {
            live = source.subscribe { [weak self] event in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    switch event {
                    case .fix(let f): self.liveFix = f; self.fix = f; self.refresh()
                    case .failure: self.live = nil; self.liveFix = nil; self.refresh()
                    }
                }
            }
        } else if !want {
            live?.cancel(); live = nil
            liveFix = nil
        }
    }

    private func refresh() {
        let next = tracker.stats.toUiState(nowNanos: clock.nowNanos(), unit: settings.speedUnit, tripState: tracker.state,
                                           locationGranted: locationGranted, liveFix: tracker.state == .running ? nil : liveFix)
        if next != ui { ui = next }
    }

    /// The unit changed in settings.
    func unitChanged() { refresh() }
}
