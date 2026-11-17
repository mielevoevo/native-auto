import CoreLocation
import Foundation

/// CoreLocation → `SpeedSample`, for any number of subscribers (the trip and the live hero share one manager, like
/// Android's single GPS subscription). Updates run only while someone subscribes, and only while the app is open.
final class CoreLocationSource: NSObject, SpeedSampleSource, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let clock: MonotonicClock
    private var sinks: [Int: (SourceEvent) -> Void] = [:]
    private var nextId = 0
    private var servicesEnabled = true

    /// Called on the main queue when the permission answer changes.
    var onAuthorization: ((Bool) -> Void)?

    /// Older than this when it arrives = a cached fix CoreLocation hands out first; skipped.
    static let maxFixAgeS: TimeInterval = 10

    init(clock: MonotonicClock) {
        self.clock = clock
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.activityType = .automotiveNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        refreshServicesEnabled()
    }

    var isLocationEnabled: Bool { servicesEnabled }

    var authorized: Bool {
        manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways
    }

    var denied: Bool { manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted }

    private final class Sub: Cancellable {
        let block: () -> Void
        init(_ b: @escaping () -> Void) { block = b }
        func cancel() { block() }
    }

    func subscribe(_ onEvent: @escaping (SourceEvent) -> Void) -> Cancellable {
        if denied { onEvent(.failure(.permissionDenied)); return Sub {} }
        if !servicesEnabled { onEvent(.failure(.disabled)); refreshServicesEnabled(); return Sub {} }
        nextId += 1
        let id = nextId
        sinks[id] = onEvent
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        manager.startUpdatingLocation()
        return Sub { [weak self] in
            guard let self else { return }
            self.sinks[id] = nil
            if self.sinks.isEmpty { self.manager.stopUpdatingLocation() }
        }
    }

    func requestPermission() { manager.requestWhenInUseAuthorization() }

    private func failAll(_ f: SourceFailure) {
        let all = sinks
        sinks = [:]
        manager.stopUpdatingLocation()
        for s in all.values { s(.failure(f)) }
    }

    /// `locationServicesEnabled` can block, so it is read off the main thread and cached.
    private func refreshServicesEnabled() {
        DispatchQueue.global(qos: .utility).async {
            let on = CLLocationManager.locationServicesEnabled()
            DispatchQueue.main.async { self.servicesEnabled = on }
        }
    }

    func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        refreshServicesEnabled()
        let ok = authorized
        DispatchQueue.main.async {
            self.onAuthorization?(ok)
            if self.denied { self.failAll(.permissionDenied) } else if ok, !self.sinks.isEmpty { self.manager.startUpdatingLocation() }
        }
    }

    func locationManager(_ m: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let now = Date()
        let nowNanos = clock.nowNanos()
        let samples = locations.compactMap { l -> SpeedSample? in
            let age = now.timeIntervalSince(l.timestamp)
            guard age <= Self.maxFixAgeS else { return nil }
            return SpeedSample(location: l, timestampNanos: nowNanos - Int64(max(0, age) * 1e9))
        }
        DispatchQueue.main.async {
            for s in samples { for sink in self.sinks.values { sink(.fix(s)) } }
        }
    }

    func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        guard let e = error as? CLError else { return }
        DispatchQueue.main.async {
            if e.code == .denied { self.failAll(self.servicesEnabled ? .permissionDenied : .disabled) }
            // .locationUnknown: CoreLocation keeps trying by itself.
        }
    }
}

extension SpeedSample {
    /// CoreLocation marks missing values with negatives. The timestamp is in the monotonic clock's base.
    init(location l: CLLocation, timestampNanos: Int64) {
        self.init(
            timestampNanos: timestampNanos,
            speedMps: Float(max(0, l.speed)),
            speedAccuracyMps: l.speedAccuracy >= 0 ? Float(l.speedAccuracy) : nil,
            horizontalAccuracyM: l.horizontalAccuracy >= 0 ? Float(l.horizontalAccuracy) : .infinity,
            latitude: l.coordinate.latitude,
            longitude: l.coordinate.longitude,
            bearingDeg: l.course >= 0 ? Float(l.course) : nil,
            hasSpeed: l.speed >= 0
        )
    }
}

/// Real GPS, the scripted drive (debug "Fake GPS") or the planned route (debug "Simulate navigation", which beats
/// Fake GPS). A change of choice moves every open subscription over at once, like Android's
/// `SwitchableSpeedSampleSource` (`flatMapLatest`).
final class SwitchableSource: SpeedSampleSource {
    let real: CoreLocationSource
    let fake: FakeSpeedSampleSource
    var useFake = false { didSet { if oldValue != useFake { reselect() } } }
    /// Set while a simulated drive runs; nil otherwise.
    var simulated: SpeedSampleSource? { didSet { if oldValue !== simulated { reselect() } } }

    init(real: CoreLocationSource, fake: FakeSpeedSampleSource) { self.real = real; self.fake = fake }

    var isLocationEnabled: Bool { simulated != nil || useFake || real.isLocationEnabled }

    private var current: SpeedSampleSource { simulated ?? (useFake ? fake : real) }

    private final class Forward: Cancellable {
        let onEvent: (SourceEvent) -> Void
        var inner: Cancellable?
        var cancelled = false
        let done: (Forward) -> Void
        init(_ onEvent: @escaping (SourceEvent) -> Void, done: @escaping (Forward) -> Void) { self.onEvent = onEvent; self.done = done }
        func cancel() { cancelled = true; inner?.cancel(); inner = nil; done(self) }
    }

    private var open: [Forward] = []

    func subscribe(_ onEvent: @escaping (SourceEvent) -> Void) -> Cancellable {
        let f = Forward(onEvent) { [weak self] f in self?.open.removeAll { $0 === f } }
        open.append(f)
        attach(f)
        return f
    }

    private func attach(_ f: Forward) {
        f.inner = current.subscribe { [weak self, weak f] event in
            guard let self, let f, !f.cancelled else { return }
            // A failure ends the subscription (the reader retries with a new one), so a later switch skips it.
            if case .failure = event { f.inner = nil; f.cancelled = true; self.open.removeAll { $0 === f } }
            f.onEvent(event)
        }
    }

    private func reselect() {
        for f in open where !f.cancelled {
            f.inner?.cancel()
            attach(f)
        }
    }
}
