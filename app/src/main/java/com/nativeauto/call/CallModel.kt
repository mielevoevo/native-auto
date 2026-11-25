package com.nativeauto.call

/**
 * 14a: calls, seen without tying the app to one dialer, one Android skin or one VoIP app.
 *
 * The idea is a few small backends that each know a part of the picture, and one pure place that
 * puts them together:
 *  1. **Call notifications** (the notification access the media card already has): any app that posts a call
 *     notification (the dialer on HyperOS / One UI / Pixel, WhatsApp, Teams, Zoom, Meet...) says who is calling,
 *     whether it rings or is on, since when, and carries its own Answer / Decline / Hang up intents.
 *  2. **Phone state + Telecom** (cellular, with the rider's permission): ringing / in a call, and answering or
 *     ending a cellular call when the notification has no such intent.
 *  3. **Audio**: mic mute and where the sound goes, shown only where the OS lets an ordinary app do it.
 *
 * This file is the pure part (no Android types), so it is unit-tested on the JVM.
 */
enum class CallPhase { RINGING, ACTIVE }

/** What the card shows about a call. */
data class CallInfo(
    /** Who is calling or being called, or the app's name when nothing better is known. */
    val caller: String,
    /** The app the call is in ("Phone", "WhatsApp"...). */
    val app: String,
    val phase: CallPhase,
    /** Wall-clock start of the call in ms, when the notification says (a chronometer), for the duration. */
    val startedAtMs: Long? = null,
)

/** The plain facts of one notification, read from a `StatusBarNotification` by the Android side. */
data class NotificationFacts(
    val key: String,
    val app: String,
    val category: String?,
    /** `Notification.EXTRA_TEMPLATE`, e.g. "android.app.Notification$CallStyle". */
    val template: String?,
    /** `android.callType` of a CallStyle: 1 incoming, 2 ongoing, 3 screening (0 / absent: none). */
    val callType: Int,
    val title: String?,
    /** The CallStyle's person name, when it has one. */
    val personName: String?,
    val text: String?,
    val actionTitles: List<String>,
    val hasAnswerIntent: Boolean,
    val hasDeclineIntent: Boolean,
    val hasHangUpIntent: Boolean,
    val whenMs: Long,
    val showsChronometer: Boolean,
    val ongoing: Boolean,
)

const val CALL_STYLE_TEMPLATE = "android.app.Notification\$CallStyle"
const val CATEGORY_CALL = "call"
const val CATEGORY_MISSED_CALL = "missed_call"

/**
 * The notification's [CallInfo], or null when it isn't a call that is ringing or on. A CallStyle notification
 * says so itself; older ones are recognised by the `call` category (the dialers and WhatsApp's own style),
 * and then by their actions to tell ringing from on.
 */
fun classifyCall(n: NotificationFacts): CallInfo? {
    val isCallStyle = n.template == CALL_STYLE_TEMPLATE
    if (!isCallStyle && n.category != CATEGORY_CALL) return null
    // A missed-call or finished-call notification isn't a call in progress.
    if (n.category == CATEGORY_MISSED_CALL) return null
    val phase = when {
        isCallStyle && n.callType == 1 -> CallPhase.RINGING
        isCallStyle && n.callType == 2 -> CallPhase.ACTIVE
        // Screening: the phone is checking the caller; it is still ringing for the rider.
        isCallStyle && n.callType == 3 -> CallPhase.RINGING
        // Plain call notifications: with an answer action it rings, otherwise it is on.
        n.hasAnswerIntent || findAnswerAction(n.actionTitles) != null -> CallPhase.RINGING
        else -> CallPhase.ACTIVE
    }
    // A non-CallStyle call notification that is neither ongoing nor has any action is a leftover: ignore it.
    if (!isCallStyle && !n.ongoing && n.actionTitles.isEmpty() && !n.hasAnswerIntent && !n.hasHangUpIntent) return null
    val caller = callerName(n) ?: n.app
    val started = if (phase == CallPhase.ACTIVE && n.showsChronometer && n.whenMs > 0) n.whenMs else null
    return CallInfo(caller = caller, app = n.app, phase = phase, startedAtMs = started)
}

/** What dialers write instead of a name; never shown as the caller. */
private val GENERIC_CALL_TEXT = setOf(
    "incoming call", "incoming voice call", "incoming video call", "ongoing call", "ongoing voice call", "ongoing video call",
    "outgoing call", "phone call", "voice call", "video call", "call", "calling", "dialing", "ringing", "on hold", "call in progress",
    "panggilan masuk", "panggilan suara masuk", "panggilan video masuk", "panggilan keluar", "panggilan berlangsung",
    "panggilan sedang berlangsung", "panggilan aktif", "panggilan", "memanggil", "berdering", "menelepon",
)
private val CALL_DURATION = Regex("""\d{1,2}(:\d{2}){1,2}""")

/** 4.7.1: the first of person, title and text that is a name or number, not "Incoming call" or a running duration. */
fun callerName(n: NotificationFacts): String? = listOfNotNull(n.personName, n.title, n.text).map { it.trim() }.firstOrNull {
    val plain = it.lowercase().trimEnd('.', '\u2026', ' ')
    plain.isNotEmpty() && plain !in GENERIC_CALL_TEXT && !CALL_DURATION.matches(plain) && !it.equals(n.app, ignoreCase = true)
}

/**
 * 4.7.1: some phones' dialers post their call in a form [classifyCall] doesn't accept. While the phone rings or is off-hook
 * ([mergeCall]), an ongoing notification of the dialer still tells who it is.
 */
fun dialerCallerName(n: NotificationFacts): String? =
    if (n.ongoing && n.category != CATEGORY_MISSED_CALL) callerName(n) else null

/** 4.7.1: the card's big line: the caller, or only what kind of call it is when the rider hides names. */
fun callTitle(info: CallInfo, showCaller: Boolean): String = when {
    showCaller -> info.caller
    info.phase == CallPhase.RINGING -> "Incoming call"
    else -> "On a call"
}

private val ANSWER_WORDS = listOf("answer", "accept", "pick up", "jawab", "terima", "angkat")
private val DECLINE_WORDS = listOf("decline", "reject", "dismiss", "tolak")
private val HANG_UP_WORDS = listOf("hang up", "hangup", "end call", "end", "leave", "akhiri", "tutup", "putus", "selesai")

/** The index of the action that answers, by its label, or null. */
fun findAnswerAction(titles: List<String>): Int? = titles.indexOfFirst { t -> ANSWER_WORDS.any { hasWord(t, it) } }.takeIf { it >= 0 }

/** The index of the action that declines a ringing call. */
fun findDeclineAction(titles: List<String>): Int? =
    titles.indexOfFirst { t -> DECLINE_WORDS.any { hasWord(t, it) } }.takeIf { it >= 0 }
        ?: titles.indexOfFirst { t -> HANG_UP_WORDS.any { hasWord(t, it) } }.takeIf { it >= 0 }

/** The index of the action that ends a call in progress. */
fun findHangUpAction(titles: List<String>): Int? =
    titles.indexOfFirst { t -> HANG_UP_WORDS.any { hasWord(t, it) } }.takeIf { it >= 0 }
        ?: titles.indexOfFirst { t -> DECLINE_WORDS.any { hasWord(t, it) } }.takeIf { it >= 0 }

/** When more than one call notification is up, the ringing one first, then the longest running. */
fun pickCall(calls: List<CallInfo>): CallInfo? =
    calls.sortedWith(compareBy<CallInfo> { it.phase != CallPhase.RINGING }.thenBy { it.startedAtMs ?: Long.MAX_VALUE }).firstOrNull()

/** The cellular side: what the telephony state says, when the rider allowed reading it. */
enum class PhoneState { IDLE, RINGING, OFFHOOK, UNKNOWN }

/** Which controls the card can offer for a call right now: only what some backend can really do on this phone. */
data class CallCapabilities(
    val canAnswer: Boolean,
    val canDecline: Boolean,
    val canEnd: Boolean,
    val canMute: Boolean,
    val canChooseAudio: Boolean,
)

/**
 * @param notification what the call notification offers (an Answer / Decline / Hang up intent it carries);
 * @param telecomEnabled the app may answer and end cellular calls (ANSWER_PHONE_CALLS granted);
 * @param phone the cellular state (a plain phone call shows as RINGING / OFFHOOK, VoIP does not).
 */
fun callCapabilities(
    phase: CallPhase,
    notification: NotificationActions?,
    telecomEnabled: Boolean,
    phone: PhoneState,
    micMuteSupported: Boolean,
    audioRoutesAvailable: Int,
): CallCapabilities {
    val cellularRinging = telecomEnabled && phone == PhoneState.RINGING
    val cellularOn = telecomEnabled && phone == PhoneState.OFFHOOK
    return CallCapabilities(
        canAnswer = phase == CallPhase.RINGING && ((notification?.answer ?: false) || cellularRinging),
        canDecline = phase == CallPhase.RINGING && ((notification?.decline ?: false) || (notification?.hangUp ?: false) || cellularRinging),
        canEnd = phase == CallPhase.ACTIVE && ((notification?.hangUp ?: false) || cellularOn),
        canMute = phase == CallPhase.ACTIVE && micMuteSupported,
        canChooseAudio = phase == CallPhase.ACTIVE && audioRoutesAvailable >= 2,
    )
}

/** Which actions the call notification carries. */
data class NotificationActions(val answer: Boolean, val decline: Boolean, val hangUp: Boolean)

/**
 * The call to show: the notification's, else one made from the cellular state alone (a dialer that posts no
 * notification, or one we can't read). With neither there is no call.
 * [dialerName] (4.7.1): who the dialer's notification names, for a phone call seen through the cellular state only.
 */
fun mergeCall(notificationCall: CallInfo?, phone: PhoneState, dialerName: String? = null): CallInfo? = when {
    notificationCall != null -> notificationCall
    phone == PhoneState.RINGING -> CallInfo(caller = dialerName ?: "Incoming call", app = "Phone", phase = CallPhase.RINGING)
    phone == PhoneState.OFFHOOK -> CallInfo(caller = dialerName ?: "Phone call", app = "Phone", phase = CallPhase.ACTIVE)
    else -> null
}

/** "0:42" / "12:07" / "1:02:09". */
fun formatCallDuration(elapsedMs: Long): String {
    val total = (elapsedMs / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Whole-word, case-insensitive: "end" must not match "Send" or "Pending". */
private fun hasWord(text: String, word: String): Boolean =
    Regex("""(^|[^\p{L}])""" + Regex.escape(word) + """([^\p{L}]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
