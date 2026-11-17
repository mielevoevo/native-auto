import AVFoundation
import Combine
import CoreLocation
import FerrostarCore
import FerrostarCoreFFI
import Foundation

/// Turn-by-turn for the map pane: pick a place → preview the route → navigate → arrive. Port of Android
/// `FerrostarNavigator`: Ferrostar with Valhalla's **motorcycle** costing on the public FOSSGIS server (it honours
/// OpenStreetMap's `motorcycle=no`, which keeps Indonesian toll roads out). Narration is English: Valhalla has no
/// Indonesian. Location comes from `TripStore.fix`, so guidance, the map dot and the trip share one GPS read.
/// Guidance runs while the app is open.
@MainActor
final class Navigator: ObservableObject {
    @Published private(set) var state: NavState = .idle
    /// Shared or pasted links being opened (the dashboard's status pill).
    @Published private(set) var linkStatus: LinkStatus?

    enum LinkStatus: Equatable { case resolving, failed(String) }

    static let valhallaUrl = "https://valhalla1.openstreetmap.de/route"
    static let locationWaitS: Double = 10
    static let arrivalRadiusM = 40.0

    let search = PlaceSearch()
    private let settings: SettingsStore
    private let trip: TripStore
    private let location = FixLocationProvider()
    private let voice = EnglishSpeech()
    private lazy var speaker = SpokenInstructionObserver(synthesizer: voice, isMuted: !settings.voiceGuidance)
    private var core: FerrostarCore?
    private var coreUnit: SpeedUnit?
    private var previewRoute: Route?
    private var task: Task<Void, Never>?
    private var linkTask: Task<Void, Never>?
    private var guidance: AnyCancellable?
    private var bag: Set<AnyCancellable> = []

    init(settings: SettingsStore, trip: TripStore) {
        self.settings = settings
        self.trip = trip
        trip.$fix.compactMap { $0 }.sink { [location] in location.push($0) }.store(in: &bag)
        // The mute button can flip the setting mid-route. Ferrostar's observer checks its mute flag before it asks
        // for audio focus, so a muted route never ducks the music (Android needed a workaround for that, 13a).
        settings.$voiceGuidance.removeDuplicates().sink { [weak self] on in
            guard let self else { return }
            if self.speaker.isMuted == on { self.speaker.toggleMute() }
        }.store(in: &bag)
    }

    var lastPoint: GeoPoint? { trip.fix.map { GeoPoint($0.latitude, $0.longitude) } }

    // MARK: - Actions

    /// Plans a route from the current position. Replaces any preview or navigation.
    func routeTo(_ place: Place) {
        endGuidance()
        state = .routing(place)
        task = Task { [weak self] in await self?.plan(place) }
    }

    /// Plans again to the same place after a failure.
    func retry() {
        if case .failed(let place, _) = state { routeTo(place) }
    }

    /// Starts guidance on the previewed route. No-op unless previewing.
    func start() {
        guard case .preview(let destination, _) = state, let route = previewRoute, let core else { return }
        task?.cancel()
        settings.recentPlaces = rememberPlace(destination, in: settings.recentPlaces)
        #if DEBUG
        if settings.simulateNavigation {
            trip.setSimulatedRoute(route.geometry.map { GeoPoint($0.lat, $0.lng) })
        }
        #endif
        do {
            try core.startNavigation(route: route, userLocation: location.lastLocation)
        } catch {
            state = .failed(destination, .noRoute)
            return
        }
        guidance = core.$state.receive(on: DispatchQueue.main).sink { [weak self] nav in
            guard let self, let nav else { return }
            let dest = self.state.destination.flatMap { $0.point == destination.point ? $0 : nil } ?? destination
            guard let mapped = nav.toNavState(destination: dest, fix: self.location.lastFix) else { return }
            self.state = mapped
            // Until arrival; then guidance stops and "You've arrived" stays up until dismissed.
            if case .arrived = mapped { self.endGuidance(keepState: true) }
        }
    }

    /// Back to idle from any state: cancels planning, the preview or the guidance.
    func stop() {
        endGuidance()
        state = .idle
    }

    /// A better name for the current destination (a dropped pin's street). Ignored if the destination changed.
    func rename(_ place: Place) { state = state.renamed(place) }

    /// A pin dropped on the map: route to it at once, and name it once the street is known.
    func routeToPin(_ point: GeoPoint) {
        let pin = Place(name: "Dropped pin", detail: nil, point: point)
        routeTo(pin)
        Task { [weak self] in
            guard let named = await self?.search.reverse(point) else { return }
            self?.rename(named)
        }
    }

    /// Links shared or pasted into the app (10b) → a route preview. The rider still presses Start.
    func openLink(_ text: String) {
        linkTask?.cancel()
        linkStatus = .resolving
        let resolver = MapsLinkResolver(search: search)
        let near = lastPoint
        linkTask = Task { [weak self] in
            do {
                let place = try await resolver.resolve(text, near: near)
                guard !Task.isCancelled else { return }
                self?.linkStatus = nil
                self?.routeTo(place)
            } catch is CancellationError {
            } catch {
                self?.linkStatus = .failed((error as? LinkError)?.message ?? "Couldn't open that link")
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if case .failed = self?.linkStatus { self?.linkStatus = nil }
            }
        }
    }

    // MARK: - Planning

    private func plan(_ place: Place) async {
        var origin = location.lastLocation
        let deadline = Date().addingTimeInterval(Self.locationWaitS)
        while origin == nil, Date() < deadline, !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 250_000_000)
            origin = location.lastLocation
        }
        guard !Task.isCancelled else { return }
        guard let origin else { state = .failed(place, .noLocation); return }
        let core = core(for: settings.speedUnit)
        let routes: [Route]
        do {
            routes = try await core.getRoutes(initialLocation: origin, waypoints: [
                Waypoint(coordinate: GeographicCoordinate(lat: place.point.latitude, lng: place.point.longitude), kind: .break, properties: nil),
            ])
        } catch {
            guard !Task.isCancelled else { return }
            // Valhalla answers "no route" (e.g. across the sea) as an error the adapter rethrows.
            state = .failed(place, isOffline(error) ? .offline : .noRoute)
            return
        }
        guard !Task.isCancelled, case .routing = state else { return }
        guard let route = routes.first else { state = .failed(place, .noRoute); return }
        previewRoute = route
        let dest = state.destination ?? place
        state = .preview(dest, RoutePlan(geometry: route.geometry.map { GeoPoint($0.lat, $0.lng) }, distanceM: route.distance,
                                         durationS: route.steps.reduce(0) { $0 + $1.duration }))
    }

    private func isOffline(_ error: Error) -> Bool {
        if error is URLError { return true }
        let ns = error as NSError
        return ns.domain == NSURLErrorDomain || "\(error)".contains("NSURLErrorDomain")
    }

    /// One core per unit: the unit only changes the narration ("in 300 metres").
    private func core(for unit: SpeedUnit) -> FerrostarCore {
        if let core, coreUnit == unit { return core }
        let options = #"{"units":"\#(unit == .mph ? "miles" : "kilometers")","language":"en-US"}"#
        let config = SwiftNavigationControllerConfig(
            waypointAdvance: .waypointWithinRange(Self.arrivalRadiusM),
            stepAdvanceCondition: stepAdvanceDistanceEntryAndExit(distanceToEndOfStep: 30, distanceAfterEndOfStep: 5, minimumHorizontalAccuracy: 32),
            arrivalStepAdvanceCondition: stepAdvanceDistanceToEndOfStep(distance: 10, minimumHorizontalAccuracy: 32),
            // 25 m off the line (with a fix better than 20 m) counts as a wrong turn.
            routeDeviationTracking: .staticThreshold(minimumHorizontalAccuracy: 20, maxAcceptableDeviation: 25),
            snappedLocationCourseFiltering: .snapToRoute
        )
        // Building the core only fails for an invalid provider; this one is fixed, so it can't.
        let made = try! FerrostarCore(
            wellKnownRouteProvider: .valhalla(endpointUrl: Self.valhallaUrl, profile: "motorcycle", optionsJson: options),
            locationProvider: location,
            navigationControllerConfig: config,
            networkSession: Http.session,
            spokenInstructionObserver: speaker
        )
        // FOSSGIS allows ~1 request/s per user; keep reroutes well apart.
        made.minimumTimeBeforeRecalculation = 8
        core = made
        coreUnit = unit
        return made
    }

    private func endGuidance(keepState: Bool = false) {
        if !keepState { task?.cancel(); task = nil }
        guidance = nil
        core?.stopNavigation()
        speaker.stopAndClearQueue()
        trip.setSimulatedRoute(nil)
        previewRoute = nil
    }
}

/// Ferrostar reads the shared fixes; it never opens its own GPS subscription.
final class FixLocationProvider: LocationProviding {
    weak var delegate: LocationManagingDelegate?
    var authorizationStatus: CLAuthorizationStatus { .authorizedWhenInUse }
    private(set) var lastLocation: UserLocation?
    var lastHeading: Heading? { nil }
    /// The fix Ferrostar is working on, to pair with the state it produces.
    private(set) var lastFix: SpeedSample?
    private var updating = false

    func startUpdating() { updating = true }
    func stopUpdating() { updating = false }

    func push(_ fix: SpeedSample) {
        let loc = fix.userLocation
        lastFix = fix
        lastLocation = loc
        if updating { delegate?.locationManager(self, didUpdateLocations: [loc]) }
    }
}

extension SpeedSample {
    var userLocation: UserLocation {
        UserLocation(
            coordinates: GeographicCoordinate(lat: latitude, lng: longitude),
            horizontalAccuracy: horizontalAccuracyM.isFinite ? Double(horizontalAccuracyM) : 1_000,
            courseOverGround: bearingDeg.map { CourseOverGround(degrees: UInt16((Double($0).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)), accuracy: nil) },
            timestamp: Date(),
            speed: hasSpeed ? Speed(value: Double(speedMps), accuracy: speedAccuracyMps.map(Double.init)) : nil
        )
    }
}

/// Narration in US English whatever the phone's language: the router's sentences are English, and an Indonesian voice
/// reading them is hard to follow.
final class EnglishSpeech: SpeechSynthesizer {
    private let synth = AVSpeechSynthesizer()
    private let voice = AVSpeechSynthesisVoice(language: "en-US")
    var isSpeaking: Bool { synth.isSpeaking }
    func speak(_ utterance: AVSpeechUtterance) {
        utterance.voice = voice
        synth.speak(utterance)
    }
    @discardableResult func stopSpeaking(at boundary: AVSpeechBoundary) -> Bool { synth.stopSpeaking(at: boundary) }
}

// MARK: - Ferrostar → ours (the only mapping, like Android's `toNavState` / `toInstruction`)

extension NavigationState {
    /// Nil while Ferrostar is idle (e.g. between stop and start).
    func toNavState(destination: Place, fix: SpeedSample?) -> NavState? {
        switch tripState {
        case .idle: return nil
        case .complete: return .arrived(destination)
        case let .navigating(_, _, snapped, remainingSteps, _, progress, _, deviation, visual, _, _):
            var position = fix
            if var p = position {
                p.latitude = snapped.coordinates.lat
                p.longitude = snapped.coordinates.lng
                p.bearingDeg = snapped.courseOverGround.map { Float($0.degrees) } ?? p.bearingDeg
                position = p
            }
            let offRoute: Bool = if case .noDeviation = deviation { false } else { true }
            return .navigating(Guidance(
                destination: destination,
                geometry: routeGeometry.map { GeoPoint($0.lat, $0.lng) },
                instruction: visual?.primaryContent.toInstruction(),
                nextInstruction: remainingSteps.dropFirst().first?.visualInstructions.first?.primaryContent.toInstruction(),
                distanceToManeuverM: progress.distanceToNextManeuver,
                distanceRemainingM: progress.distanceRemaining,
                durationRemainingS: progress.durationRemaining,
                position: position,
                offRoute: offRoute,
                rerouting: isCalculatingNewRoute
            ))
        }
    }
}

extension VisualInstructionContent {
    func toInstruction() -> Instruction {
        let kind: ManeuverKind = switch maneuverType {
        case .depart: .depart
        case .arrive: .arrive
        case .merge: .merge
        case .fork: .fork
        case .onRamp, .offRamp: .ramp
        case .roundabout, .rotary, .roundaboutTurn, .exitRoundabout, .exitRotary: .roundabout
        case .continue, .newName, .notification: .continue
        case .turn, .endOfRoad, nil: .turn
        }
        let direction: TurnDirection? = switch maneuverModifier {
        case .uTurn: .uTurn
        case .sharpRight: .sharpRight
        case .right: .right
        case .slightRight: .slightRight
        case .straight: .straight
        case .slightLeft: .slightLeft
        case .left: .left
        case .sharpLeft: .sharpLeft
        case nil: nil
        }
        return Instruction(text: text, kind: kind, direction: direction, roundaboutExitDegrees: roundaboutExitDegrees.map(Int.init))
    }
}
