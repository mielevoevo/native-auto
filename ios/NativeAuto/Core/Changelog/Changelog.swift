import Foundation

/// One release in `changelog.md`: `## <name> (<code>) · <date>` and its bullets (Android `ChangelogEntry`).
struct ChangelogEntry: Equatable, Identifiable {
    var versionName: String
    var versionCode: Int
    var date: String
    var bullets: [String]
    var id: Int { versionCode }
}

enum Changelog {
    /// The sheet after an update shows at most this many versions.
    static let maxSheetEntries = 3
    /// iOS 0.4 (build 4) was the last build without a changelog.
    static let beforeChangelog = 4

    /// Reads the changelog text. A header that doesn't match, or a bullet before the first header, is skipped: a typo
    /// in the file must never break the app. Newest first (by version code).
    static func parse(_ text: String) -> [ChangelogEntry] {
        let header = try! NSRegularExpression(pattern: #"^##\s+(\S+)\s+\((\d+)\)\s*[·\-]\s*(.*)$"#)
        var entries: [ChangelogEntry] = []
        var current: ChangelogEntry?
        for raw in text.components(separatedBy: "\n") {
            let line = raw.replacingOccurrences(of: #"\s+$"#, with: "", options: .regularExpression)
            if line.hasPrefix("## ") {
                if let c = current { entries.append(c) }
                current = nil
                let ns = line as NSString
                if let m = header.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)), let code = Int(ns.substring(with: m.range(at: 2))) {
                    current = ChangelogEntry(versionName: ns.substring(with: m.range(at: 1)), versionCode: code,
                                             date: ns.substring(with: m.range(at: 3)).trimmingCharacters(in: .whitespaces), bullets: [])
                }
            } else if line.hasPrefix("- "), current != nil {
                current!.bullets.append(String(line.dropFirst(2)).trimmingCharacters(in: .whitespaces))
            }
        }
        if let c = current { entries.append(c) }
        return entries.sorted { $0.versionCode > $1.versionCode }
    }

    /// Entries newer than `lastSeen` and not newer than the running build, newest first, at most `max`. Nothing while
    /// `deferred` (a trip or navigation).
    static func toShow(_ all: [ChangelogEntry], lastSeen: Int, current: Int, deferred: Bool, max: Int = maxSheetEntries) -> [ChangelogEntry] {
        guard !deferred, current > lastSeen else { return [] }
        return Array(all.filter { ((lastSeen + 1)...current).contains($0.versionCode) }.prefix(max))
    }

    /// The last build the rider saw the changelog for: a fresh install counts as having seen the running build; an
    /// update from a build without a changelog is `beforeChangelog`, so the update's entries show once.
    static func resolveLastSeen(stored: Int?, freshInstall: Bool, current: Int) -> Int {
        if let stored { return stored }
        return freshInstall ? current : beforeChangelog
    }
}

/// 4.2's rule: the first-run pages show only when no preference has ever been saved (a new install, or a reset).
func setupPendingFor(storedPreferenceCount: Int) -> Bool { storedPreferenceCount == 0 }
