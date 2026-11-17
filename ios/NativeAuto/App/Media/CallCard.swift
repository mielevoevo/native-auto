import CallKit
import SwiftUI

/// "A call is on" from CallKit (15e). iOS gives an app no caller name and no way to answer, decline or end a call, so
/// the card says what's happening and how long, and points at the phone's own controls (or the headset's button).
@MainActor
final class CallStore: NSObject, ObservableObject, CXCallObserverDelegate {
    @Published private(set) var phase: CallPhase?

    private let observer = CXCallObserver()
    private var connectedAt: [UUID: Int64] = [:]
    private let settings: SettingsStore

    init(settings: SettingsStore) {
        self.settings = settings
        super.init()
        observer.setDelegate(self, queue: .main)
        #if DEBUG
        // UI tests and screenshots: `-debug_call RINGING|ACTIVE` pretends a call is on.
        switch UserDefaults.standard.string(forKey: "debug_call") {
        case "RINGING": phase = .ringing
        case "ACTIVE": phase = .active(connectedAtMs: SystemWallClock().nowMs() - 65_000)
        default: update()
        }
        #else
        update()
        #endif
    }

    /// The card takes over only while the rider wants call controls (Settings, Audio).
    var active: Bool { phase != nil && settings.callControls }

    nonisolated func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        MainActor.assumeIsolated { update() }
    }

    private func update() {
        let now = SystemWallClock().nowMs()
        var seen: [(facts: CallFacts, phase: CallPhase)] = []
        for c in observer.calls {
            if c.hasConnected, connectedAt[c.uuid] == nil { connectedAt[c.uuid] = now }
            let facts = CallFacts(outgoing: c.isOutgoing, connected: c.hasConnected, onHold: c.isOnHold, ended: c.hasEnded)
            if let p = callPhase(facts, connectedAtMs: connectedAt[c.uuid]) { seen.append((facts, p)) }
        }
        let live = Set(observer.calls.filter { !$0.hasEnded }.map(\.uuid))
        connectedAt = connectedAt.filter { live.contains($0.key) }
        phase = pickCall(seen)
    }
}

/// The call pane: what's happening, for how long, and where the controls are.
struct CallCard: View {
    let mode: CardMode
    @EnvironmentObject private var call: CallStore
    @Environment(\.appColors) private var colors

    var body: some View {
        DashboardCard(pane: .call, mode: mode, title: "Call") {
            VStack(spacing: mode == .mini ? 4 : 10) {
                HStack(spacing: 12) {
                    Image(systemName: icon).font(.system(size: mode == .mini ? 22 : 30, weight: .semibold))
                        .foregroundStyle(call.phase == .ringing ? colors.accent : colors.text)
                    Text(status).font(.geist(mode == .mini ? 18 : 24, .semibold)).foregroundStyle(colors.text)
                        .accessibilityIdentifier("call_status")
                }
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    if case .active(let at)? = call.phase {
                        Text(formatCallDuration(Int64(ctx.date.timeIntervalSince1970 * 1000) - at))
                            .font(.geist(mode == .mini ? 20 : 40, .medium)).monospacedDigit().foregroundStyle(colors.text)
                            .accessibilityIdentifier("call_duration")
                    }
                }
                if mode != .mini {
                    Text(hint).font(.geist(14)).foregroundStyle(colors.textDim).multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                        .accessibilityIdentifier("call_hint")
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var icon: String {
        switch call.phase {
        case .ringing: "phone.arrow.down.left.fill"
        case .dialling: "phone.arrow.up.right.fill"
        default: "phone.fill"
        }
    }

    private var status: String {
        switch call.phase {
        case .ringing: "Incoming call"
        case .dialling: "Calling…"
        case .active: "On a call"
        case nil: "No call"
        }
    }

    private var hint: String {
        call.phase == .ringing
            ? "Answer with the phone or the headset's button."
            : "End it with the phone or the headset's button."
    }
}

/// The call over a fullscreen card (14b): a slim pill at the top, with the status and the time.
struct CallPill: View {
    @EnvironmentObject private var call: CallStore
    @Environment(\.appColors) private var colors

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: call.phase == .ringing ? "phone.arrow.down.left.fill" : "phone.fill").foregroundStyle(colors.accent)
            TimelineView(.periodic(from: .now, by: 1)) { ctx in
                switch call.phase {
                case .ringing: Text("Incoming call")
                case .dialling: Text("Calling…")
                case .active(let at)?: Text("On a call · \(formatCallDuration(Int64(ctx.date.timeIntervalSince1970 * 1000) - at))").monospacedDigit()
                case nil: EmptyView()
                }
            }
            .font(.geist(16, .semibold)).foregroundStyle(colors.text)
        }
        .padding(.horizontal, 18).frame(minHeight: 44)
        .background(colors.surface, in: Capsule())
        .overlay(Capsule().strokeBorder(colors.outline, lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("call_pill")
    }
}
