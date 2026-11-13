package com.nativeauto.call

import com.nativeauto.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The call as the card draws it: what it is, what can be done to it on this phone, and the audio state. */
data class CallState(
    val info: CallInfo,
    val capabilities: CallCapabilities,
    val micMuted: Boolean,
    val route: AudioRoute?,
    val routes: List<AudioRoute>,
    /** Wall-clock ms the call became active (the notification's chronometer, else when we first saw it on). */
    val activeSinceMs: Long?,
)

/**
 * The three backends put together (14a): [NotificationCallSource] (any app's call notification and its intents),
 * [PhoneCallSource] (cellular state and answer / end, with permission) and [CallAudio] (mic, route). The pure
 * decisions ([mergeCall], [callCapabilities]) are in CallModel.kt. Every control is offered only if some backend
 * can really do it on this phone, and each action goes to the backend that can: the notification's own intent
 * first (it is what the app itself would do), the telephony API second.
 */
@Singleton
class CallRepository @Inject constructor(
    private val notifications: NotificationCallSource,
    private val phone: PhoneCallSource,
    private val audio: CallAudio,
    @ApplicationScope scope: CoroutineScope,
) {
    /** Bumped after an audio action so [state] reads the audio manager again. */
    private val audioTick = MutableStateFlow(0)
    private var activeSince: Long? = null

    val state: StateFlow<CallState?> =
        combine(notifications.call, phone.state, phone.canControl, audioTick) { observed, cellular, control, _ ->
            val info = mergeCall(observed?.info, cellular)
            if (info == null) {
                activeSince = null
                audio.newCall()
                return@combine null
            }
            if (info.phase == CallPhase.ACTIVE) {
                if (activeSince == null) activeSince = System.currentTimeMillis()
            } else {
                activeSince = null
            }
            val routes = if (info.phase == CallPhase.ACTIVE) audio.routes() else emptyList()
            CallState(
                info = info,
                capabilities = callCapabilities(
                    phase = info.phase,
                    notification = observed?.actions,
                    telecomEnabled = control,
                    phone = cellular,
                    micMuteSupported = audio.micMuteSupported,
                    audioRoutesAvailable = routes.size,
                ),
                micMuted = audio.micMuted(),
                route = if (info.phase == CallPhase.ACTIVE) audio.current() else null,
                routes = routes,
                activeSinceMs = info.startedAtMs ?: activeSince,
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

    fun answer() {
        val call = notifications.call.value
        if (call?.actions?.answer == true && call.answer()) return
        phone.answer()
    }

    fun decline() {
        val call = notifications.call.value
        if ((call?.actions?.decline == true || call?.actions?.hangUp == true) && call.decline()) return
        phone.end()
    }

    fun hangUp() {
        val call = notifications.call.value
        if ((call?.actions?.hangUp == true || call?.actions?.decline == true) && call.hangUp()) return
        phone.end()
    }

    fun toggleMute() {
        audio.setMicMuted(!audio.micMuted())
        audioTick.value++
    }

    /** One tap moves the sound to the next route (phone, speaker, Bluetooth...). */
    fun nextRoute() {
        val routes = audio.routes()
        audio.next(routes, audio.current())?.let { audio.select(it) }
        audioTick.value++
    }

    /** Permissions may have changed (the rider came back from the system dialog or settings). */
    fun refreshPermissions() = phone.refreshPermissions()
}
