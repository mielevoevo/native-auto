package com.nativeauto.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Where the sound of a call can go. */
enum class AudioRoute(val label: String) {
    EARPIECE("Phone"),
    SPEAKER("Speaker"),
    BLUETOOTH("Bluetooth"),
    WIRED("Headset"),
}

/**
 * Backend 3 of the call card (14a): mic mute and the audio route through [AudioManager]. Both are only offered
 * where they take effect: after a change the state is read back, and a phone that ignores it (some skins only
 * let the dialer do this) gets the control hidden for the rest of the call instead of a button that lies.
 * The route list is the platform's own (API 31+: communication devices; before: earpiece and speaker).
 */
@Singleton
class CallAudio @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val audio = context.getSystemService(AudioManager::class.java)

    @Volatile private var muteIgnored = false

    fun micMuted(): Boolean = audio.isMicrophoneMute

    /** False once a mute attempt didn't stick: the phone doesn't let this app do it. */
    val micMuteSupported: Boolean get() = !muteIgnored

    /** Sets the mic; returns whether it took. */
    fun setMicMuted(muted: Boolean): Boolean {
        audio.isMicrophoneMute = muted
        val took = audio.isMicrophoneMute == muted
        if (!took) muteIgnored = true
        return took
    }

    /** Forgets a failed mute attempt (a new call may behave differently). */
    fun newCall() {
        muteIgnored = false
    }

    fun routes(): List<AudioRoute> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices.mapNotNull { it.route() }.distinct().sortedBy { it.ordinal }
        } else {
            listOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)
        }

    fun current(): AudioRoute? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.communicationDevice?.route()
        } else {
            if (audio.isSpeakerphoneOn) AudioRoute.SPEAKER else AudioRoute.EARPIECE
        }

    /** Moves the sound to [route]; false when the platform refused. */
    @Suppress("DEPRECATION")
    fun select(route: AudioRoute): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audio.availableCommunicationDevices.firstOrNull { it.route() == route }
            device != null && audio.setCommunicationDevice(device)
        } else {
            audio.isSpeakerphoneOn = route == AudioRoute.SPEAKER
            true
        }

    /** The next route in the list after [current], wrapping round (one tap on the card cycles through them). */
    fun next(routes: List<AudioRoute>, current: AudioRoute?): AudioRoute? =
        routes.takeIf { it.size >= 2 }?.let { it[(it.indexOf(current).coerceAtLeast(-1) + 1) % it.size] }

    private fun AudioDeviceInfo.route(): AudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID -> AudioRoute.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.WIRED
        else -> null
    }
}
