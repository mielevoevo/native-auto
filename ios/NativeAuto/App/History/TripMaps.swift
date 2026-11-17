import MapLibre
import SwiftUI
import UIKit

/// A trip's path drawn on a plain background: shown at once, and until the map snapshot is ready (Android
/// `TrackSketch`). North up, the path fitted with equal scale on both axes; start dot plain, end dot in the route colour.
struct TrackSketch: View {
    let track: String
    var lineWidth: CGFloat = 4
    @Environment(\.appColors) private var colors

    var body: some View {
        let points = Polyline.decode(track)
        Canvas { ctx, size in
            guard points.count >= 2 else { return }
            let lats = points.map(\.latitude), lons = points.map(\.longitude)
            let minLat = lats.min()!, maxLat = lats.max()!, minLon = lons.min()!, maxLon = lons.max()!
            let lonScale = cos((minLat + maxLat) / 2 * .pi / 180)
            let spanX = max((maxLon - minLon) * lonScale, 1e-6), spanY = max(maxLat - minLat, 1e-6)
            let pad: CGFloat = 14
            let scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
            let offX = (size.width - spanX * scale) / 2, offY = (size.height - spanY * scale) / 2
            func at(_ p: GeoPoint) -> CGPoint {
                CGPoint(x: offX + (p.longitude - minLon) * lonScale * scale, y: offY + (maxLat - p.latitude) * scale)
            }
            var path = Path()
            path.move(to: at(points[0]))
            for p in points.dropFirst() { path.addLine(to: at(p)) }
            ctx.stroke(path, with: .color(colors.accent), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
            for (p, c) in [(points.first!, colors.text), (points.last!, colors.accent)] {
                let q = at(p)
                ctx.fill(Path(ellipseIn: CGRect(x: q.x - 5, y: q.y - 5, width: 10, height: 10)), with: .color(c))
            }
        }
        .background(colors.buttonQuiet)
        .accessibilityLabel("Route sketch")
    }
}

/// The trip's overview map: the sketch first, then a MapLibre snapshot (OpenFreeMap, the dashboard's look) with the path
/// drawn on it, cached per theme. Port of Android `SnapshotTripMaps`.
struct TripMapImage: View {
    let trip: TripRecord
    var size = CGSize(width: 520, height: 300)
    @Environment(\.appColors) private var colors
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            TrackSketch(track: trip.track)
            if let image {
                Image(uiImage: image).resizable().scaledToFill().transition(.opacity)
                    .accessibilityLabel("Route map")
            }
        }
        .clipped()
        .task(id: "\(trip.id)-\(colors.isDark)") {
            image = nil
            image = await TripSnapshots.shared.image(for: trip, dark: colors.isDark, route: UIColor(colors.accent), size: size)
        }
    }
}

/// Snapshots of trip maps, made one at a time and cached in Caches (`trip-<id>-dark.png` / `-light.png`).
@MainActor
final class TripSnapshots: NSObject, MLNMapSnapshotterDelegate {
    static let shared = TripSnapshots()
    private var memory: [String: UIImage] = [:]
    private var running: MLNMapSnapshotter?
    private var look: MapLook?
    private var queue: [() -> Void] = []
    private var busy = false

    private func file(_ key: String) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("trip-maps", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(key + ".png")
    }

    /// Drops a deleted trip's cached maps.
    func forget(_ id: Int64) {
        for theme in ["dark", "light"] {
            let key = "trip-\(id)-\(theme)"
            memory[key] = nil
            try? FileManager.default.removeItem(at: file(key))
        }
    }

    func image(for trip: TripRecord, dark: Bool, route: UIColor, size: CGSize) async -> UIImage? {
        let key = "trip-\(trip.id)-\(dark ? "dark" : "light")"
        if let m = memory[key] { return m }
        if let d = try? Data(contentsOf: file(key)), let img = UIImage(data: d) { memory[key] = img; return img }
        let points = Polyline.decode(trip.track)
        guard points.count >= 2 else { return nil }
        let img: UIImage? = await withCheckedContinuation { cont in
            enqueue { [weak self] in
                guard let self else { return cont.resume(returning: nil) }
                self.snapshot(points, look: MapLook.of(dark: dark, route: route), size: size) { cont.resume(returning: $0) }
            }
        }
        if let img {
            memory[key] = img
            try? img.pngData()?.write(to: file(key))
        }
        return img
    }

    private func enqueue(_ job: @escaping () -> Void) {
        queue.append(job)
        runNext()
    }

    private func runNext() {
        guard !busy, !queue.isEmpty else { return }
        busy = true
        queue.removeFirst()()
    }

    private func snapshot(_ points: [GeoPoint], look: MapLook, size: CGSize, done: @escaping (UIImage?) -> Void) {
        let lats = points.map(\.latitude), lons = points.map(\.longitude)
        let dLat = max(lats.max()! - lats.min()!, 0.004) * 0.18, dLon = max(lons.max()! - lons.min()!, 0.004) * 0.18
        let bounds = MLNCoordinateBounds(sw: CLLocationCoordinate2D(latitude: lats.min()! - dLat, longitude: lons.min()! - dLon),
                                         ne: CLLocationCoordinate2D(latitude: lats.max()! + dLat, longitude: lons.max()! + dLon))
        let options = MLNMapSnapshotOptions(styleURL: look.styleURL, camera: MLNMapCamera(), size: size)
        options.coordinateBounds = bounds
        let s = MLNMapSnapshotter(options: options)
        self.look = look
        s.delegate = self
        running = s
        // Offline or slow: give up after 20 s; the sketch stays.
        let timeout = DispatchWorkItem { [weak self, weak s] in s?.cancel(); self?.finish(nil, done) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: timeout)
        s.start { [weak self] snap, _ in
            MainActor.assumeIsolated {
                timeout.cancel()
                guard let self else { return }
                guard let snap else { return self.finish(nil, done) }
                let r = UIGraphicsImageRenderer(size: snap.image.size)
                let img = r.image { ctx in
                    snap.image.draw(at: .zero)
                    let c = ctx.cgContext
                    c.setLineCap(.round); c.setLineJoin(.round)
                    let pts = points.map { snap.point(for: CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)) }
                    for (color, width) in [(look.routeCasing, CGFloat(8)), (look.route, CGFloat(5))] {
                        c.setStrokeColor(color.cgColor); c.setLineWidth(width)
                        c.beginPath(); c.addLines(between: pts); c.strokePath()
                    }
                    for (p, color) in [(pts.first!, UIColor.white), (pts.last!, look.route)] {
                        c.setFillColor(color.cgColor)
                        c.fillEllipse(in: CGRect(x: p.x - 6, y: p.y - 6, width: 12, height: 12))
                    }
                }
                self.finish(img, done)
            }
        }
    }

    private func finish(_ img: UIImage?, _ done: (UIImage?) -> Void) {
        guard busy else { return }
        running = nil
        busy = false
        done(img)
        runNext()
    }

    nonisolated func mapSnapshotter(_ snapshotter: MLNMapSnapshotter, didFinishLoading style: MLNStyle) {
        MainActor.assumeIsolated { look?.apply(to: style) }
    }
}
