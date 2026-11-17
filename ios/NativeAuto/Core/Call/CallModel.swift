import Foundation

// 15e: what iOS tells an app about calls (CallKit's `CXCallObserver`): that a call rings, is dialling or is on, for the
// phone and for VoIP apps that use CallKit (WhatsApp, Teams, Zoom…). Not who calls, and no way to answer or end it.
// Android's `CallModel` (14a) reads the call notification and can do both.

enum CallPhase: Equatable {
    /// Incoming, not answered yet.
    case ringing
    /// Outgoing, not connected yet.
    case dialling
    /// Connected since `connectedAtMs` (wall clock).
    case active(connectedAtMs: Int64)
}

/// One CallKit call as the observer reports it.
struct CallFacts: Equatable {
    var outgoing: Bool
    var connected: Bool
    var onHold: Bool
    var ended: Bool
}

/// The phase of one call, or nil once it has ended. `connectedAtMs` is when we first saw it connected.
func callPhase(_ f: CallFacts, connectedAtMs: Int64?) -> CallPhase? {
    if f.ended { return nil }
    if f.connected { return .active(connectedAtMs: connectedAtMs ?? 0) }
    return f.outgoing ? .dialling : .ringing
}

/// The call the card shows when several exist (one on hold, a second ringing): a ringing call first, then a live one
/// not on hold, then any.
func pickCall(_ calls: [(facts: CallFacts, phase: CallPhase)]) -> CallPhase? {
    if let r = calls.first(where: { $0.phase == .ringing }) { return r.phase }
    if let a = calls.first(where: { !$0.facts.onHold }) { return a.phase }
    return calls.first?.phase
}

/// "0:42", "12:07", "1:02:09" (Android `formatCallDuration`).
func formatCallDuration(_ elapsedMs: Int64) -> String {
    let total = max(elapsedMs / 1000, 0)
    let h = total / 3600, m = (total % 3600) / 60, s = total % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}
