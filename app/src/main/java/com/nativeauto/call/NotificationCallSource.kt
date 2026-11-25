package com.nativeauto.call

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A call notification and the intents it carries. */
class ObservedCall(
    val info: CallInfo,
    val actions: NotificationActions,
    private val answer: PendingIntent?,
    private val decline: PendingIntent?,
    private val hangUp: PendingIntent?,
) {
    fun answer(): Boolean = fire(answer)
    fun decline(): Boolean = fire(decline ?: hangUp)
    fun hangUp(): Boolean = fire(hangUp ?: decline)

    private fun fire(intent: PendingIntent?): Boolean = try {
        intent?.send()
        intent != null
    } catch (e: PendingIntent.CanceledException) {
        Log.w(TAG, "The call's own action was cancelled", e)
        false
    }
}

/**
 * Backend 1 of the call card (14a): what the call notifications of any app say. The notification
 * listener ([com.nativeauto.media.MediaListenerService]) feeds it every notification posted or removed; only
 * those that [classifyCall] accepts are kept, in memory, with their intents. Nothing else about a notification is
 * read, stored or sent anywhere.
 */
@Singleton
class NotificationCallSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val calls = LinkedHashMap<String, ObservedCall>()

    /** 4.7.1: names from the dialer's own ongoing notifications that aren't recognisable calls, by key. */
    private val dialerNames = LinkedHashMap<String, String>()
    private val _dialerName = MutableStateFlow<String?>(null)

    /** Who the phone's dialer names; only meaningful while the phone rings or is off-hook. In memory only. */
    val dialerName: StateFlow<String?> = _dialerName.asStateFlow()

    private val _call = MutableStateFlow<ObservedCall?>(null)

    /** The call to show: the ringing one first (several may be up at once, e.g. a call waiting). */
    val call: StateFlow<ObservedCall?> = _call.asStateFlow()

    @Synchronized
    fun onPosted(sbn: StatusBarNotification) {
        dialerNames.remove(sbn.key)
        val observed = observe(sbn)
        if (observed == null) calls.remove(sbn.key) else calls[sbn.key] = observed
        publish()
    }

    @Synchronized
    fun onRemoved(key: String) {
        val named = dialerNames.remove(key) != null
        if (calls.remove(key) != null || named) publish()
    }

    /** The listener (re)connected: start from what is up right now. */
    @Synchronized
    fun reset(active: Array<StatusBarNotification>?) {
        calls.clear()
        dialerNames.clear()
        active?.forEach { sbn -> observe(sbn)?.let { calls[sbn.key] = it } }
        publish()
    }

    @Synchronized
    fun clear() {
        calls.clear()
        dialerNames.clear()
        publish()
    }

    private fun publish() {
        _dialerName.value = dialerNames.values.lastOrNull()
        val best = pickCall(calls.values.map { it.info })
        _call.value = calls.values.firstOrNull { it.info == best }
        if (com.nativeauto.BuildConfig.DEBUG) Log.d(TAG, "call notifications: ${calls.size}, showing $best")
    }

    private fun observe(sbn: StatusBarNotification): ObservedCall? = try {
        val n = sbn.notification
        val extras = n.extras
        val titles = n.actions?.map { it.title?.toString().orEmpty() }.orEmpty()
        val answer = intentExtra(extras, Notification.EXTRA_ANSWER_INTENT)
        val decline = intentExtra(extras, Notification.EXTRA_DECLINE_INTENT)
        val hangUp = intentExtra(extras, Notification.EXTRA_HANG_UP_INTENT)
        val person = extras.getParcelable<android.app.Person>(Notification.EXTRA_CALL_PERSON)
        val facts = NotificationFacts(
            key = sbn.key,
            app = appLabel(sbn.packageName),
            category = n.category,
            template = extras.getString(Notification.EXTRA_TEMPLATE),
            callType = extras.getInt(Notification.EXTRA_CALL_TYPE, 0),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            personName = person?.name?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            actionTitles = titles,
            hasAnswerIntent = answer != null,
            hasDeclineIntent = decline != null,
            hasHangUpIntent = hangUp != null,
            whenMs = n.`when`,
            showsChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false),
            ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
        )
        val call = classifyCall(facts)
        if (call == null && isDialer(sbn.packageName)) dialerCallerName(facts)?.let { dialerNames[sbn.key] = it }
        call?.let { info ->
            // The notification's own intents first; older call notifications only have labelled actions.
            fun action(index: Int?) = index?.let { n.actions?.getOrNull(it)?.actionIntent }
            val answerIntent = answer ?: action(findAnswerAction(titles))
            val declineIntent = decline ?: action(findDeclineAction(titles))
            val hangUpIntent = hangUp ?: action(findHangUpAction(titles))
            ObservedCall(
                info = info,
                actions = NotificationActions(answer = answerIntent != null, decline = declineIntent != null, hangUp = hangUpIntent != null),
                answer = answerIntent,
                decline = declineIntent,
                hangUp = hangUpIntent,
            )
        }
    } catch (e: RuntimeException) {
        // A notification we can't read (a bad extra) is simply not a call for us; never crash the listener.
        Log.w(TAG, "Couldn't read a notification", e)
        null
    }

    /** The phone's own dialer, or the in-call screen some skins ship apart from it. */
    private fun isDialer(pkg: String): Boolean =
        pkg == IN_CALL_UI || pkg == context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage

    @Suppress("DEPRECATION")
    private fun intentExtra(extras: android.os.Bundle, key: String): PendingIntent? = extras.getParcelable(key) as? PendingIntent

    private fun appLabel(pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}

private const val TAG = "NativeAuto/Call"
private const val IN_CALL_UI = "com.android.incallui"
