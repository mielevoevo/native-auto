import XCTest
@testable import NativeAuto

/// Port of Android `ChangelogTest` (the pure part), plus the shipped iOS file.
final class ChangelogTests: XCTestCase {
    private let text = """
    # What's new

    ## 0.1 (3) · 2026-11-01
    - Old
    ## 3.4.1 (19) · 2026-11-14
    - First thing
    - Second thing
    ## broken header
    - ignored bullet
    ## 3.3 (18) - 2026-11-09
    - Before
    """

    func testParsesEntriesNewestFirst() {
        let e = Changelog.parse(text)
        XCTAssertEqual(e.map(\.versionCode), [19, 18, 3])
        XCTAssertEqual(e[0].versionName, "3.4.1")
        XCTAssertEqual(e[0].date, "2026-11-14")
        XCTAssertEqual(e[0].bullets, ["First thing", "Second thing"])
        XCTAssertFalse(e.contains { $0.bullets.contains("ignored bullet") })
    }

    func testEmptyOrGarbageTextGivesNothing() {
        XCTAssertTrue(Changelog.parse("").isEmpty)
        XCTAssertTrue(Changelog.parse("- a bullet with no header").isEmpty)
    }

    func testWhichEntriesShow() {
        let all = Changelog.parse(text)
        XCTAssertEqual(Changelog.toShow(all, lastSeen: 18, current: 19, deferred: false).map(\.versionCode), [19])
        XCTAssertTrue(Changelog.toShow(all, lastSeen: 19, current: 19, deferred: false).isEmpty)
        XCTAssertEqual(Changelog.toShow(all, lastSeen: 2, current: 18, deferred: false).map(\.versionCode), [18, 3])
        XCTAssertTrue(Changelog.toShow(all, lastSeen: 18, current: 19, deferred: true).isEmpty)
        let many = (1...6).map { ChangelogEntry(versionName: "\($0)", versionCode: $0, date: "", bullets: ["x"]) }.reversed()
        XCTAssertEqual(Changelog.toShow(Array(many), lastSeen: 0, current: 6, deferred: false).map(\.versionCode), [6, 5, 4])
    }

    func testLastSeen() {
        XCTAssertEqual(Changelog.resolveLastSeen(stored: nil, freshInstall: true, current: 5), 5)
        XCTAssertEqual(Changelog.resolveLastSeen(stored: nil, freshInstall: false, current: 5), Changelog.beforeChangelog)
        XCTAssertEqual(Changelog.resolveLastSeen(stored: 5, freshInstall: true, current: 6), 5)
    }

    func testTheShippedChangelogCoversTheCurrentBuild() throws {
        let url = try XCTUnwrap(Bundle(for: SettingsStore.self).url(forResource: "changelog", withExtension: "md"))
        let entries = Changelog.parse(try String(contentsOf: url, encoding: .utf8))
        XCTAssertGreaterThanOrEqual(entries.count, 5)
        XCTAssertTrue(entries.allSatisfy { !$0.bullets.isEmpty })
        let info = Bundle(for: SettingsStore.self).infoDictionary!
        XCTAssertEqual(entries.first?.versionName, info["CFBundleShortVersionString"] as? String)
        XCTAssertEqual(entries.first.map { String($0.versionCode) }, info["CFBundleVersion"] as? String)
    }
}

final class SetupRuleTests: XCTestCase {
    func testOnlyAnEmptyStoreShowsTheSetup() {
        XCTAssertTrue(setupPendingFor(storedPreferenceCount: 0))
        XCTAssertFalse(setupPendingFor(storedPreferenceCount: 1))
    }
}

/// 15e: the share extension's hand-over, read back by the app's `incomingLinkText`.
final class ShareLinkTests: XCTestCase {
    func testGoogleMapsNameAndLinkBecomeOneText() {
        let t = ShareLink.combine(texts: ["Corner Cafe\n"], urls: [URL(string: "https://maps.app.goo.gl/abc123")!])
        XCTAssertEqual(t, "Corner Cafe\nhttps://maps.app.goo.gl/abc123")
        // A link already in the text isn't repeated.
        XCTAssertEqual(ShareLink.combine(texts: ["See https://maps.app.goo.gl/abc123"], urls: [URL(string: "https://maps.app.goo.gl/abc123")!]),
                       "See https://maps.app.goo.gl/abc123")
        XCTAssertEqual(ShareLink.combine(texts: [" "], urls: []), "")
    }

    @MainActor func testTheAppReadsBackExactlyWhatWasShared() throws {
        for text in ["Corner Cafe\nhttps://maps.app.goo.gl/abc123", "Main Street & Co + 1 #2", "geo:-7.79,110.36?q=a b"] {
            let url = try XCTUnwrap(ShareLink.openURL(for: text))
            XCTAssertEqual(incomingLinkText(url), text)
        }
        XCTAssertNil(ShareLink.openURL(for: "  "))
    }
}
