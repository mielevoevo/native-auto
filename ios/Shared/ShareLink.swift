import Foundation

/// 15e: what the share extension hands the app. Compiled into both the app and the extension (Foundation only).
/// Google Maps shares a place's name and a `maps.app.goo.gl` link as separate items (text + URL); Android gets them
/// as one text (`EXTRA_TEXT`), so they are joined the same way and the app's Maps-link parser reads them alike.
enum ShareLink {
    /// The shared texts and URLs as one text: texts first (the place's name), then any URL not already in them.
    static func combine(texts: [String], urls: [URL]) -> String {
        var parts = texts.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        for u in urls where !parts.contains(where: { $0.contains(u.absoluteString) }) { parts.append(u.absoluteString) }
        var seen = Set<String>()
        return parts.filter { seen.insert($0).inserted }.joined(separator: "\n")
    }

    /// `nativeauto://open?text=…`, which the app's `onOpenURL` resolves like a shared Maps link. Nil for empty text.
    static func openURL(for text: String) -> URL? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        var c = URLComponents()
        c.scheme = "nativeauto"; c.host = "open"
        c.queryItems = [URLQueryItem(name: "text", value: t)]
        // `+` survives as itself in a query; encode it so a text with one reads back the same.
        c.percentEncodedQuery = c.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return c.url
    }
}
