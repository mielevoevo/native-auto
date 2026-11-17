import Foundation

// Port of Android `nav/NavModels.kt` and `RecentPlaces.placeKey`. Plain types, so nothing outside the navigator sees
// Ferrostar's or MapLibre's.

struct GeoPoint: Equatable, Hashable, Codable {
    var latitude: Double
    var longitude: Double

    init(latitude: Double, longitude: Double) { self.latitude = latitude; self.longitude = longitude }
    init(_ latitude: Double, _ longitude: Double) { self.init(latitude: latitude, longitude: longitude) }
}

/// A destination: a search result, a recent place, or a pin dropped on the map.
struct Place: Equatable, Hashable, Codable {
    var name: String
    var detail: String?
    var point: GeoPoint
}

enum ManeuverKind: Equatable { case depart, turn, `continue`, merge, fork, ramp, roundabout, arrive }

enum TurnDirection: Equatable { case straight, slightLeft, left, sharpLeft, slightRight, right, sharpRight, uTurn }

struct Instruction: Equatable {
    var text: String
    var kind: ManeuverKind
    var direction: TurnDirection?
    /// Roundabouts: where the exit is, degrees counter-clockwise from the entry (Valhalla's convention).
    var roundaboutExitDegrees: Int? = nil
}

struct RoutePlan: Equatable {
    var geometry: [GeoPoint]
    var distanceM: Double
    var durationS: Double
}

enum NavFailure: Equatable { case noLocation, offline, noRoute }

struct Guidance: Equatable {
    var destination: Place
    var geometry: [GeoPoint]
    var instruction: Instruction?
    /// The maneuver after `instruction`, for a "then" hint when they come close together.
    var nextInstruction: Instruction?
    var distanceToManeuverM: Double
    var distanceRemainingM: Double
    var durationRemainingS: Double
    /// The latest fix, snapped to the route. Drives the dot and the camera while navigating.
    var position: SpeedSample?
    var offRoute: Bool
    var rerouting: Bool
}

/// What the map pane shows. The navigator moves through these; the UI only draws them.
enum NavState: Equatable {
    case idle
    case routing(Place)
    case preview(Place, RoutePlan)
    case navigating(Guidance)
    case arrived(Place)
    case failed(Place, NavFailure)

    /// The route line, whichever state carries one.
    var routeGeometry: [GeoPoint]? {
        switch self {
        case .preview(_, let plan): plan.geometry
        case .navigating(let g): g.geometry
        default: nil
        }
    }

    var destination: Place? {
        switch self {
        case .idle: nil
        case .routing(let p), .preview(let p, _), .arrived(let p), .failed(let p, _): p
        case .navigating(let g): g.destination
        }
    }

    var isNavigating: Bool { if case .navigating = self { true } else { false } }

    var guidance: Guidance? { if case .navigating(let g) = self { g } else { nil } }

    /// The same state with the destination renamed (a dropped pin's street arrives later).
    func renamed(_ place: Place) -> NavState {
        guard destination?.point == place.point else { return self }
        switch self {
        case .idle: return self
        case .routing: return .routing(place)
        case .preview(_, let plan): return .preview(place, plan)
        case .navigating(var g): g.destination = place; return .navigating(g)
        case .arrived: return .arrived(place)
        case .failed(_, let r): return .failed(place, r)
        }
    }
}

/// ~10 m grid: picking the same place again moves it to the top instead of adding a row.
func placeKey(_ p: GeoPoint) -> String { String(format: "%.4f,%.4f", locale: Locale(identifier: "en_US_POSIX"), p.latitude, p.longitude) }

/// Newest first, at most `keep`, one per `placeKey`. Port of the Room upsert + trim in `RoomRecentPlaces`.
func rememberPlace(_ place: Place, in recent: [Place], keep: Int = 8) -> [Place] {
    let key = placeKey(place.point)
    return Array(([place] + recent.filter { placeKey($0.point) != key }).prefix(keep))
}
