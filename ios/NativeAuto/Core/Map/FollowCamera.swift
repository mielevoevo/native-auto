import Foundation

/// Port of Android `ui/map/FollowCamera.kt`: where the follow camera should be for a fix. Centred on it, tilted, and
/// turned so the direction of travel points up.
/// - The heading follows the course only while moving: parked, GPS course is noise and the map would spin.
/// - The rider's zoom is kept, unless it is zoomed out too far to ride by.
/// Zoom levels are Google's (256 px tiles); MapLibre is one below (`MapLook.zoomOffset`).
struct CameraTarget: Equatable {
    var latitude: Double
    var longitude: Double
    var zoom: Float
    var tilt: Float
    var bearing: Float
}

enum FollowCamera {
    static let zoom: Float = 17
    static let minFollowZoom: Float = 13
    static let tilt: Float = 45
    /// ~11 km/h: below this, course readings aren't trustworthy enough to rotate the map.
    static let minHeadingSpeedMps: Float = 3

    static func target(latitude: Double, longitude: Double, speedMps: Float, courseDeg: Float?,
                       currentZoom: Float, currentBearing: Float) -> CameraTarget {
        CameraTarget(
            latitude: latitude, longitude: longitude,
            zoom: currentZoom < minFollowZoom ? zoom : currentZoom,
            tilt: tilt,
            bearing: courseDeg.flatMap { speedMps >= minHeadingSpeedMps ? normalize($0) : nil } ?? currentBearing
        )
    }

    private static func normalize(_ deg: Float) -> Float {
        (deg.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
    }
}
