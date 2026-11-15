import CoreLocation
import Foundation

/// 15a: CoreLocation → `SpeedSample` → `TripReducer`, while the app is open. A first end-to-end check
/// on the phone; the real trip life cycle (start / pause / end, recording) comes in 15b.
@MainActor
final class LiveTrip: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var stats = TripStats()
    @Published private(set) var authorized = false

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.activityType = .automotiveNavigation
        manager.distanceFilter = kCLDistanceFilterNone
    }

    func start() {
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()
    }

    func reset() { stats = TripStats() }

    nonisolated func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        let ok = m.authorizationStatus == .authorizedWhenInUse || m.authorizationStatus == .authorizedAlways
        Task { @MainActor in
            self.authorized = ok
            if ok { self.manager.startUpdatingLocation() }
        }
    }

    nonisolated func locationManager(_ m: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let samples = locations.map(SpeedSample.init(location:))
        Task { @MainActor in
            for s in samples { self.stats = TripReducer.reduce(self.stats, s) }
        }
    }
}

extension SpeedSample {
    /// CoreLocation marks missing values with negatives.
    init(location l: CLLocation) {
        self.init(
            timestampNanos: Int64(l.timestamp.timeIntervalSince1970 * 1e9),
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
