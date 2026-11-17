import MapLibre
import SwiftUI
import UIKit

/// The map's state that outlives its view: follow mode, the camera (kept while the map sleeps) and sleep itself.
/// Port of Android `MapViewModel` / `MapCameraMemory` / `MapSleep`.
@MainActor
final class MapStore: ObservableObject {
    /// The camera glides after the rider's position. A pan, pinch or rotate by hand breaks it; recentre restores it.
    @Published var following = true
    /// Off screen for `sleepAfterS` (not in the layout, folded away by a maximized neighbour): the map view is
    /// released and a plain background drawn instead. Camera and follow state are kept.
    @Published private(set) var asleep = false
    /// Bumped by the recentre button, so the view snaps back even when already following.
    @Published private(set) var recenterTick = 0

    static var sleepAfterS: Double = 30
    var savedCamera: MLNMapCamera?
    private var sleepTask: Task<Void, Never>?

    func recenter() { following = true; recenterTick += 1 }

    func setOnScreen(_ on: Bool) {
        sleepTask?.cancel()
        if on {
            if asleep { asleep = false }
        } else if !asleep {
            sleepTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(Self.sleepAfterS * 1e9))
                guard !Task.isCancelled else { return }
                self?.asleep = true
            }
        }
    }
}

/// MapLibre's map view inside a card. One instance while awake (rule #3: nothing recreated by layout changes).
struct DashboardMapView: UIViewRepresentable {
    let look: MapLook
    let fix: SpeedSample?
    let route: [GeoPoint]?
    let destination: GeoPoint?
    /// Frame the whole route (preview) instead of following.
    let frameRoute: Bool
    /// Where to keep the route clear of cards at the bottom while framing it.
    let bottomClearance: CGFloat
    /// Long-press drops a destination pin (fullscreen only, 13a).
    let pinEnabled: Bool
    let onPin: (GeoPoint) -> Void
    @ObservedObject var store: MapStore

    func makeCoordinator() -> Coordinator { Coordinator(store: store) }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: look.styleURL)
        map.delegate = context.coordinator
        map.backgroundColor = look.background
        map.logoView.isHidden = true
        // Keep the "i" attribution: OpenFreeMap / OpenStreetMap terms require the credit. Bottom right, left of the
        // 56 pt button column (10 pt inset + 56 + a gap), so the recentre button never covers it.
        map.attributionButtonPosition = .bottomRight
        map.attributionButtonMargins = CGPoint(x: 10 + 56 + 10, y: 14)
        map.compassViewPosition = .topRight
        map.compassView.compassVisibility = .hidden // heading-up while following; recentre restores it
        map.showsUserLocation = false
        map.allowsTilting = true
        map.accessibilityIdentifier = "map_view"
        if let cam = store.savedCamera {
            map.setCamera(cam, animated: false) // waking from sleep
        } else if let fix {
            map.setCenter(CLLocationCoordinate2D(latitude: fix.latitude, longitude: fix.longitude),
                          zoomLevel: Double(FollowCamera.zoom) - MapLook.zoomOffset, animated: false)
        } else {
            map.setCenter(CLLocationCoordinate2D(latitude: -7.7956, longitude: 110.3695), zoomLevel: 11, animated: false)
        }
        let press = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.longPress(_:)))
        press.minimumPressDuration = 0.6
        press.isEnabled = pinEnabled
        map.addGestureRecognizer(press)
        context.coordinator.press = press
        context.coordinator.map = map
        context.coordinator.look = look
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let c = context.coordinator
        c.parent = self
        // Elsewhere the card is held like the others (13a), so the map's own long-press is off.
        c.press?.isEnabled = pinEnabled
        if c.look != look {
            let styleChanged = c.look?.styleURL != look.styleURL
            c.look = look
            map.backgroundColor = look.background
            if styleChanged { c.styleLoaded = false; map.styleURL = look.styleURL } else if c.styleLoaded, let s = map.style { c.restyle(s) }
        }
        c.draw()
        c.moveCamera()
    }

    static func dismantleUIView(_ map: MLNMapView, coordinator: Coordinator) {
        // Released (map sleep): remember where the camera was.
        coordinator.store.savedCamera = map.camera
    }

    @MainActor
    final class Coordinator: NSObject, MLNMapViewDelegate {
        let store: MapStore
        weak var map: MLNMapView?
        weak var press: UILongPressGestureRecognizer?
        var parent: DashboardMapView?
        var look: MapLook?
        var styleLoaded = false
        private var lastFix: SpeedSample?
        private var lastRecenter = -1
        private var framed: [GeoPoint]?
        private var drawnRoute: [GeoPoint]?? = .none
        private var drawnDestination: GeoPoint?? = .none
        private var cameraByCode = false

        static let routeSource = "nav-route", destSource = "nav-dest", vehicleSource = "vehicle"

        init(store: MapStore) { self.store = store }

        // MARK: Style

        nonisolated func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            MainActor.assumeIsolated {
                styleLoaded = true
                drawnRoute = .none; drawnDestination = .none
                addLayers(style)
                if let look { look.apply(to: style) }
                draw()
            }
        }

        func restyle(_ style: MLNStyle) {
            guard let look else { return }
            look.apply(to: style)
            (style.layer(withIdentifier: "\(Self.routeSource)-line") as? MLNLineStyleLayer)?.lineColor = NSExpression(forConstantValue: look.route)
            (style.layer(withIdentifier: "\(Self.routeSource)-casing") as? MLNLineStyleLayer)?.lineColor = NSExpression(forConstantValue: look.routeCasing)
            (style.layer(withIdentifier: Self.destSource) as? MLNCircleStyleLayer)?.circleColor = NSExpression(forConstantValue: look.destination)
        }

        /// Route (dark casing under a bright line) and destination pin above the roads and buildings but below the
        /// labels that follow them; the vehicle dot on top of everything.
        private func addLayers(_ style: MLNStyle) {
            guard let look else { return }
            let route = MLNShapeSource(identifier: Self.routeSource, shape: nil)
            let dest = MLNShapeSource(identifier: Self.destSource, shape: nil)
            let vehicle = MLNShapeSource(identifier: Self.vehicleSource, shape: nil)
            [route, dest, vehicle].forEach(style.addSource)

            let casing = MLNLineStyleLayer(identifier: "\(Self.routeSource)-casing", source: route)
            casing.lineColor = NSExpression(forConstantValue: look.routeCasing)
            casing.lineWidth = NSExpression(forConstantValue: 11)
            let line = MLNLineStyleLayer(identifier: "\(Self.routeSource)-line", source: route)
            line.lineColor = NSExpression(forConstantValue: look.route)
            line.lineWidth = NSExpression(forConstantValue: 7)
            for l in [casing, line] {
                l.lineJoin = NSExpression(forConstantValue: "round")
                l.lineCap = NSExpression(forConstantValue: "round")
            }
            let pin = MLNCircleStyleLayer(identifier: Self.destSource, source: dest)
            pin.circleRadius = NSExpression(forConstantValue: 10)
            pin.circleColor = NSExpression(forConstantValue: look.destination)
            pin.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            pin.circleStrokeWidth = NSExpression(forConstantValue: 3)
            // Above the last non-label layer: the first label layer comes early (under the roads), so "below the
            // first label" would hide the route.
            let lastShape = style.layers.lastIndex { !($0 is MLNSymbolStyleLayer) }
            if let lastShape {
                var below = style.layers[lastShape]
                for l in [casing, line, pin] as [MLNStyleLayer] { style.insertLayer(l, above: below); below = l }
            } else {
                [casing, line, pin].forEach(style.addLayer)
            }

            let rim = MLNCircleStyleLayer(identifier: "vehicle-rim", source: vehicle)
            rim.circleRadius = NSExpression(forConstantValue: 13)
            rim.circleColor = NSExpression(forConstantValue: UIColor.white)
            rim.circleStrokeWidth = NSExpression(forConstantValue: 1)
            rim.circleStrokeColor = NSExpression(forConstantValue: UIColor.black.withAlphaComponent(0.4))
            rim.circlePitchAlignment = NSExpression(forConstantValue: "map")
            let core = MLNCircleStyleLayer(identifier: "vehicle-core", source: vehicle)
            core.circleRadius = NSExpression(forConstantValue: 10)
            core.circleColor = NSExpression(forConstantValue: MapLook.vehicle)
            core.circlePitchAlignment = NSExpression(forConstantValue: "map")
            style.setImage(Self.chevron, forName: "vehicle-chevron")
            let heading = MLNSymbolStyleLayer(identifier: "vehicle-heading", source: vehicle)
            heading.iconImageName = NSExpression(forConstantValue: "vehicle-chevron")
            heading.iconRotation = NSExpression(forKeyPath: "bearing")
            heading.iconRotationAlignment = NSExpression(forConstantValue: "map")
            heading.iconPitchAlignment = NSExpression(forConstantValue: "map")
            heading.iconAllowsOverlap = NSExpression(forConstantValue: true)
            heading.iconIgnoresPlacement = NSExpression(forConstantValue: true)
            heading.predicate = NSPredicate(format: "bearing != nil")
            [rim, core, heading].forEach(style.addLayer)
        }

        /// A white chevron pointing up, drawn inside the dot.
        static let chevron: UIImage = UIGraphicsImageRenderer(size: CGSize(width: 14, height: 14)).image { _ in
            let p = UIBezierPath()
            p.move(to: CGPoint(x: 7, y: 1)); p.addLine(to: CGPoint(x: 13, y: 12)); p.addLine(to: CGPoint(x: 7, y: 9))
            p.addLine(to: CGPoint(x: 1, y: 12)); p.close()
            UIColor.white.setFill(); p.fill()
        }

        // MARK: Data

        func draw() {
            guard styleLoaded, let style = map?.style, let parent else { return }
            if let fix = parent.fix, let src = style.source(withIdentifier: Self.vehicleSource) as? MLNShapeSource {
                let f = MLNPointFeature()
                f.coordinate = CLLocationCoordinate2D(latitude: fix.latitude, longitude: fix.longitude)
                if let b = fix.bearingDeg, fix.speedMps >= FollowCamera.minHeadingSpeedMps { f.attributes = ["bearing": b] }
                src.shape = f
            }
            if drawnRoute != .some(parent.route), let src = style.source(withIdentifier: Self.routeSource) as? MLNShapeSource {
                drawnRoute = .some(parent.route)
                if let r = parent.route, r.count >= 2 {
                    var coords = r.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
                    src.shape = MLNPolylineFeature(coordinates: &coords, count: UInt(coords.count))
                } else {
                    src.shape = nil
                }
            }
            if drawnDestination != .some(parent.destination), let src = style.source(withIdentifier: Self.destSource) as? MLNShapeSource {
                drawnDestination = .some(parent.destination)
                if let d = parent.destination {
                    let f = MLNPointFeature()
                    f.coordinate = CLLocationCoordinate2D(latitude: d.latitude, longitude: d.longitude)
                    src.shape = f
                } else {
                    src.shape = nil
                }
            }
        }

        // MARK: Camera

        func moveCamera() {
            guard let map, let parent, map.bounds.width > 1, map.bounds.height > 1 else { return }
            if parent.frameRoute, let r = parent.route, r.count >= 2 {
                guard framed != r else { return }
                framed = r
                var coords = r.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
                let pad = UIEdgeInsets(top: 40, left: 40, bottom: 40 + parent.bottomClearance, right: 40)
                cameraByCode = true
                map.setVisibleCoordinates(&coords, count: UInt(coords.count), edgePadding: pad, direction: 0, duration: 0.8,
                                          animationTimingFunction: nil) { [weak self] in self?.cameraByCode = false }
                return
            }
            framed = nil
            guard store.following, let fix = parent.fix else { return }
            let recentred = store.recenterTick != lastRecenter
            guard recentred || fix != lastFix else { return }
            lastRecenter = store.recenterTick
            lastFix = fix
            let zoom = map.zoomLevel + MapLook.zoomOffset
            let t = FollowCamera.target(latitude: fix.latitude, longitude: fix.longitude, speedMps: fix.speedMps,
                                        courseDeg: fix.bearingDeg, currentZoom: Float(zoom), currentBearing: Float(map.direction))
            let center = CLLocationCoordinate2D(latitude: t.latitude, longitude: t.longitude)
            let altitude = MLNAltitudeForZoomLevel(Double(t.zoom) - MapLook.zoomOffset, CGFloat(t.tilt), t.latitude, map.bounds.size)
            let cam = MLNMapCamera(lookingAtCenter: center, altitude: altitude, pitch: CGFloat(t.tilt), heading: CLLocationDirection(t.bearing))
            cameraByCode = true
            // A 1 s glide matches the 1 Hz fixes; a big jump (recentre, zoomed far out) snaps instead.
            if recentred || zoom < Double(FollowCamera.minFollowZoom) {
                map.setCamera(cam, withDuration: 0.5, animationTimingFunction: CAMediaTimingFunction(name: .easeOut))
            } else {
                map.setCamera(cam, withDuration: 1.0, animationTimingFunction: CAMediaTimingFunction(name: .linear))
            }
        }

        nonisolated func mapView(_ mapView: MLNMapView, regionWillChangeWith reason: MLNCameraChangeReason, animated: Bool) {
            let gesture: MLNCameraChangeReason = [.gesturePan, .gesturePinch, .gestureRotate, .gestureZoomIn, .gestureZoomOut,
                                                  .gestureOneFingerZoom, .gestureTilt]
            guard !reason.isDisjoint(with: gesture) else { return }
            // Follow breaks on a gesture by hand, not on movement: the follow glide itself moves the camera all the time.
            MainActor.assumeIsolated { if store.following { store.following = false } }
        }

        nonisolated func mapView(_ mapView: MLNMapView, regionDidChangeWith reason: MLNCameraChangeReason, animated: Bool) {
            MainActor.assumeIsolated { store.savedCamera = mapView.camera }
        }

        @objc func longPress(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let map, let parent, parent.pinEnabled else { return }
            let c = map.convert(g.location(in: map), toCoordinateFrom: map)
            Haptics.lift()
            parent.onPin(GeoPoint(c.latitude, c.longitude))
        }
    }
}
