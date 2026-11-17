import Foundation

// Port of Android `nav/MapsLink.kt`: Google Maps links (and `geo:`) → a destination. Pure; `MapsLinkResolver` adds
// the redirects and the search.

enum LinkTarget: Equatable {
    /// The link carries a position. `name` when the link or the shared text names the place.
    case at(GeoPoint, name: String?)
    /// Only a name or address: it goes through the search service.
    case query(String)
}

private func re(_ p: String, _ o: NSRegularExpression.Options = []) -> NSRegularExpression { try! NSRegularExpression(pattern: p, options: o) }

private let urlRe = re(#"\b(?:https?://\S+|geo:\S+)"#, .caseInsensitive)
private let mapsHost = re(#"^https?://(?:maps\.app\.goo\.gl|goo\.gl/maps|(?:www\.|maps\.)?google\.[a-z.]+/maps|maps\.google\.[a-z.]+)"#, .caseInsensitive)
private let shortHost = re(#"^https?://(?:maps\.app\.goo\.gl|goo\.gl/maps)/"#, .caseInsensitive)
private let pinRe = re(#"!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)"#)
private let atRe = re(#"@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)"#)
private let latLonRe = re(#"^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$"#)
private let placeSegmentRe = re(#"/maps/place/([^/@?]+)"#)
private let labelRe = re(#"\(([^)]*)\)"#)

private extension NSRegularExpression {
    func first(_ s: String) -> [String]? {
        guard let m = firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        return (0..<m.numberOfRanges).map { i in Range(m.range(at: i), in: s).map { String(s[$0]) } ?? "" }
    }
    func all(_ s: String) -> [String] {
        matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap { Range($0.range, in: s).map { String(s[$0]) } }
    }
    func contains(_ s: String) -> Bool { firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil }
}

/// The first maps link (Google Maps or `geo:`) in shared or pasted text, or nil.
func findMapsUrl(_ text: String) -> String? {
    urlRe.all(text).lazy
        .map { trimEnd($0, ".,)]") }
        .first { $0.lowercased().hasPrefix("geo:") || mapsHost.contains($0) }
}

/// `maps.app.goo.gl/...` and `goo.gl/maps/...` only redirect; they must be expanded first.
func isShortMapsLink(_ url: String) -> Bool { shortHost.contains(url) }

/// What a (full) maps link points at. The place pin (`!3d…!4d…`) beats the camera centre (`@lat,lon`); then `q` /
/// `query` / `destination` / `daddr` / `ll`, which may be coordinates or a name. `sharedText` is the whole shared
/// message: Google Maps puts the place name on its first line.
func parseMapsLink(_ url: String, sharedText: String? = nil) -> LinkTarget? {
    let name = sharedName(sharedText, url: url) ?? placeSegment(url)
    if url.lowercased().hasPrefix("geo:") { return parseGeo(url, name: name) }

    if let m = pinRe.first(url) { return .at(point(m), name: name) }
    let params = queryParams(url)
    for key in ["q", "query", "destination", "daddr", "ll"] {
        guard let v = params[key], !v.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
        if let m = latLonRe.first(v) { return .at(point(m), name: name) }
        // Google sometimes adds a "loc:" prefix or a name in parentheses after the address.
        let bare = v.hasPrefix("loc:") ? String(v.dropFirst(4)) : v
        return .query(name ?? bare.trimmingCharacters(in: .whitespaces))
    }
    if let m = atRe.first(url) { return .at(point(m), name: name) }
    return name.map { .query($0) }
}

/// `geo:lat,lon`, `geo:0,0?q=lat,lon(Name)` or `geo:0,0?q=Some+address`.
private func parseGeo(_ url: String, name: String?) -> LinkTarget? {
    let body = url.split(separator: ":", maxSplits: 1).dropFirst().first.map(String.init) ?? ""
    if let q = queryParams(url)["q"] {
        let label = labelRe.first(q).map { $0[1] }.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        let bare = String(q.split(separator: "(", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
        if let m = latLonRe.first(bare) { return .at(point(m), name: label ?? name) }
        let text = label ?? bare.trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? nil : .query(text)
    }
    let coords = String(body.split(separator: "?", omittingEmptySubsequences: false).first ?? "")
        .split(separator: ";", omittingEmptySubsequences: false).first.map(String.init) ?? ""
    guard let m = latLonRe.first(coords) else { return nil }
    let p = point(m)
    if p.latitude == 0, p.longitude == 0 { return nil }
    return .at(p, name: name)
}

/// The non-link lines of a share ("Malioboro Mall\nJl. Malioboro No.52\nhttps://…").
private func textLines(_ sharedText: String?, url: String) -> [String] {
    (sharedText ?? "").components(separatedBy: .newlines)
        .map { $0.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty && !$0.contains(url) && !urlRe.contains($0) }
}

/// The first non-link line: the place's name.
func sharedName(_ sharedText: String?, url: String) -> String? {
    textLines(sharedText, url: url).first.flatMap { $0.count <= 120 ? $0 : nil }
}

/// The second non-link line of a Google Maps share (usually the address), for the place's detail.
func sharedDetail(_ sharedText: String?, url: String) -> String? {
    textLines(sharedText, url: url).dropFirst().first
}

private func placeSegment(_ url: String) -> String? {
    placeSegmentRe.first(url).map { decode($0[1]) }.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
}

private func queryParams(_ url: String) -> [String: String] {
    guard let q = url.firstIndex(of: "?") else { return [:] }
    let query = url[url.index(after: q)...].split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""
    var out: [String: String] = [:]
    for pair in query.split(separator: "&") {
        guard let eq = pair.firstIndex(of: "="), eq != pair.startIndex else { continue }
        let k = String(pair[..<eq])
        out[k] = decode(String(pair[pair.index(after: eq)...])) // the last one wins, as in Kotlin's `toMap`
    }
    return out
}

/// Form decoding, like Java's `URLDecoder`: "+" is a space.
private func decode(_ s: String) -> String {
    let spaced = s.replacingOccurrences(of: "+", with: " ")
    return spaced.removingPercentEncoding ?? spaced
}

private func point(_ m: [String]) -> GeoPoint { GeoPoint(Double(m[1]) ?? 0, Double(m[2]) ?? 0) }

/// Drops sentence punctuation stuck to the end of a link ("…/maps/xyz)." in a chat). A closing bracket stays when the
/// link opened it: `geo:…(Warung Bu Ning)` keeps its label. (Android trims every `)`, which loses that label; noted
/// for the Android update in DECISIONS.md.)
private func trimEnd(_ s: String, _ chars: String) -> String {
    var s = s
    while let last = s.last, chars.contains(last) {
        if last == ")", s.filter({ $0 == "(" }).count >= s.filter({ $0 == ")" }).count { break }
        s.removeLast()
    }
    return s
}
