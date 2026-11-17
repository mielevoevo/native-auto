import CoreLocation
import Foundation

/// One HTTP session for the community services (FOSSGIS Valhalla, Photon): they ask for an identifying User-Agent,
/// like Android's `DataModule` client.
enum Http {
    static let userAgent: String = {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        return "NativeAuto/\(v) (iOS dashboard; personal use)"
    }()

    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.httpAdditionalHeaders = ["User-Agent": userAgent]
        c.timeoutIntervalForRequest = 15
        return URLSession(configuration: c)
    }()

    /// The same session that doesn't follow redirects, so each hop of a short link can be read.
    static let noRedirects: URLSession = {
        let c = URLSessionConfiguration.default
        c.httpAdditionalHeaders = ["User-Agent": userAgent]
        c.timeoutIntervalForRequest = 15
        return URLSession(configuration: c, delegate: NoRedirects(), delegateQueue: nil)
    }()

    private final class NoRedirects: NSObject, URLSessionTaskDelegate {
        func urlSession(_ s: URLSession, task: URLSessionTask, willPerformHTTPRedirection r: HTTPURLResponse,
                        newRequest: URLRequest) async -> URLRequest? { nil }
    }
}

/// Searching failed for a reason the rider should see ("offline").
struct SearchOffline: Error {}

/// Finding destinations by name, and naming a point. Port of Android `PhotonPlaceSearch` (Photon: komoot's free OSM
/// geocoder, the same data as the map and the router; fair use only, so the UI searches on a pause in typing).
/// Naming a point asks Apple's geocoder first (free, like Android's platform `Geocoder`), then Photon.
final class PlaceSearch {
    private static let base = "https://photon.komoot.io/"

    /// Throws `SearchOffline` when offline or the service fails.
    func search(_ query: String, near: GeoPoint?) async throws -> [Place] {
        var c = URLComponents(string: Self.base + "api/")!
        c.queryItems = [URLQueryItem(name: "q", value: query), URLQueryItem(name: "limit", value: "8")]
        // Bias towards where the rider is, so "Monas" means the one in this city.
        if let near {
            c.queryItems! += [URLQueryItem(name: "lat", value: String(near.latitude)), URLQueryItem(name: "lon", value: String(near.longitude))]
        }
        return parsePhoton(try await get(c.url!))
    }

    /// Nil when nothing is known there or the lookup fails.
    func reverse(_ point: GeoPoint) async -> Place? {
        if let p = await apple(point) { return p }
        var c = URLComponents(string: Self.base + "reverse")!
        c.queryItems = [URLQueryItem(name: "lat", value: String(point.latitude)), URLQueryItem(name: "lon", value: String(point.longitude)),
                        URLQueryItem(name: "limit", value: "1")]
        guard let data = try? await get(c.url!), var p = parsePhoton(data).first else { return nil }
        p.point = point
        return p
    }

    private func apple(_ point: GeoPoint) async -> Place? {
        guard let m = try? await CLGeocoder().reverseGeocodeLocation(CLLocation(latitude: point.latitude, longitude: point.longitude)).first
        else { return nil }
        let street = [m.thoroughfare, m.subThoroughfare].compactMap { $0 }.joined(separator: " ")
        guard let name = m.name ?? (street.isEmpty ? nil : street) else { return nil }
        let area = [m.subLocality, m.locality].compactMap { $0 }.filter { $0 != name }
        return Place(name: name, detail: area.isEmpty ? nil : area.joined(separator: ", "), point: point)
    }

    private func get(_ url: URL) async throws -> Data {
        do {
            let (data, res) = try await Http.session.data(from: url)
            guard (res as? HTTPURLResponse)?.statusCode == 200 else { throw SearchOffline() }
            return data
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            if (error as? URLError)?.code == .cancelled { throw CancellationError() }
            throw SearchOffline()
        }
    }
}

/// Why a shared link couldn't become a destination; `message` is shown to the rider.
struct LinkError: Error { let message: String }

/// Port of Android `MapsLinkResolver`: shared or pasted text → a place. Expands short Google Maps links (they only
/// redirect), reads the position from the link, and searches when it holds only a name. Plain text without a link
/// is searched as it is (an address from a chat).
struct MapsLinkResolver {
    let search: PlaceSearch
    static let maxHops = 5

    func resolve(_ text: String, near: GeoPoint?) async throws -> Place {
        let url = findMapsUrl(text)
        let target: LinkTarget?
        if let url {
            target = isShortMapsLink(url) ? parseMapsLink(try await expand(url), sharedText: text) : parseMapsLink(url, sharedText: text)
        } else {
            target = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }
                .first { !$0.isEmpty }.map { .query($0) }
        }
        guard let target else { throw LinkError(message: "Couldn't read a place from that link") }
        switch target {
        case .at(let point, let name):
            return Place(name: name ?? "Shared place", detail: url.flatMap { sharedDetail(text, url: $0) }, point: point)
        case .query(let q):
            let found: [Place]
            do { found = try await search.search(q, near: near) } catch is SearchOffline { throw LinkError(message: "Search is offline") }
            guard let first = found.first else { throw LinkError(message: "Couldn't find \"\(q)\"") }
            return first
        }
    }

    /// Follows up to `maxHops` redirects until a full maps URL; Google's consent page hands over its `continue` target.
    func expand(_ start: String) async throws -> String {
        var url = start
        for _ in 0..<Self.maxHops {
            guard let u = URL(string: url) else { return url }
            let res: URLResponse
            do { (_, res) = try await Http.noRedirects.data(from: u) } catch {
                throw LinkError(message: "Offline: couldn't open the short link")
            }
            guard let http = res as? HTTPURLResponse, (300...399).contains(http.statusCode),
                  let loc = http.value(forHTTPHeaderField: "Location"),
                  let next = URL(string: loc, relativeTo: u)?.absoluteURL else { return url }
            if next.host?.hasPrefix("consent.") == true,
               let cont = URLComponents(url: next, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "continue" })?.value {
                url = cont
            } else {
                url = next.absoluteString
            }
            // Done once it's a full maps URL; any other hop (tracking, consent) is followed on.
            if findMapsUrl(url) != nil, !isShortMapsLink(url) { return url }
        }
        return url
    }
}
