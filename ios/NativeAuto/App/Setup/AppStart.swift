import SwiftUI

/// Decided once per launch, before anything writes a preference (Android `SetupState.start` + `ChangelogStore`):
/// whether the first-run pages show, and which changelog entries are new to the rider.
@MainActor
final class AppStart: ObservableObject {
    /// The first-run pages are on screen instead of the dashboard.
    @Published private(set) var setupPending: Bool
    /// Changelog entries the rider hasn't seen (shown as a sheet when nothing important is going on).
    @Published private(set) var newEntries: [ChangelogEntry] = []

    private let d: UserDefaults
    let current: Int

    init(defaults: UserDefaults = .standard) {
        d = defaults
        current = Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") ?? 0
        // The app's own saved keys only (launch arguments live in another domain and don't count).
        let stored = Bundle.main.bundleIdentifier.flatMap { defaults.persistentDomain(forName: $0) } ?? [:]
        let fresh: Bool
        if let done = stored["setup_done"] as? Bool {
            fresh = false
            setupPending = !done
        } else {
            fresh = setupPendingFor(storedPreferenceCount: stored.count)
            setupPending = fresh
            d.set(!fresh, forKey: "setup_done")
        }
        #if DEBUG
        // UI tests: `-setup_done 1` skips the pages, `-setup_done 0` shows them (argument domain wins over stored).
        if d.object(forKey: "setup_done") != nil, ProcessInfo.processInfo.arguments.contains("-setup_done") {
            setupPending = !d.bool(forKey: "setup_done")
        }
        #endif
        let stored_seen = d.object(forKey: "changelog_seen_code") as? Int
        let seen = Changelog.resolveLastSeen(stored: stored_seen, freshInstall: fresh || setupPending, current: current)
        if stored_seen == nil { d.set(seen, forKey: "changelog_seen_code") }
        newEntries = Changelog.toShow(Changelog.shipped, lastSeen: seen, current: current, deferred: false)
        #if DEBUG
        // UI tests: `-setup_done` skips the changelog sheet too, unless the test sets `-changelog_seen_code` itself.
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-setup_done"), !args.contains("-changelog_seen_code") { newEntries = [] }
        #endif
    }

    func finishSetup() {
        d.set(true, forKey: "setup_done")
        withAnimation(.easeInOut(duration: 0.3)) { setupPending = false }
    }

    func changelogSeen() {
        d.set(current, forKey: "changelog_seen_code")
        newEntries = []
    }
}

extension Changelog {
    /// The bundled `changelog.md`, parsed once.
    static let shipped: [ChangelogEntry] = {
        guard let url = Bundle.main.url(forResource: "changelog", withExtension: "md"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return parse(text)
    }()
}

/// The changelog: after an update (new entries only, at most three) or from Settings, Display, What's new (all).
struct ChangelogSheet: View {
    let entries: [ChangelogEntry]
    let title: String
    var onClose: (() -> Void)?
    @Environment(\.appColors) private var colors
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title).font(.geist(22, .semibold)).foregroundStyle(colors.text)
                Spacer()
                Button("Done") { onClose?(); dismiss() }
                    .font(.geist(17, .semibold)).foregroundStyle(colors.accent).frame(minWidth: 56, minHeight: 56)
                    .accessibilityIdentifier("changelog_done")
            }
            .padding(.horizontal, 20).padding(.top, 8)
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    ForEach(entries) { e in
                        VStack(alignment: .leading, spacing: 8) {
                            Text("\(e.versionName) · \(e.date)").font(.geist(17, .semibold)).foregroundStyle(colors.text)
                            ForEach(e.bullets, id: \.self) { b in
                                HStack(alignment: .firstTextBaseline, spacing: 10) {
                                    Text("•").foregroundStyle(colors.accent)
                                    Text(b).font(.geist(16)).foregroundStyle(colors.text).fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                    }
                }
                .padding(20).frame(maxWidth: 760, alignment: .leading).frame(maxWidth: .infinity)
            }
        }
        .background(colors.surface.ignoresSafeArea())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("changelog_sheet")
    }
}
