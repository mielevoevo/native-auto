package com.nativeauto.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nativeauto.call.NotificationCallSource
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Notification access is what lets the app see other apps' media sessions (§7), and since 14a their **call
 * notifications**: who is calling, ringing or on, and the Answer / Decline / Hang up intents the call app
 * itself offers ([NotificationCallSource]). Only notifications that are calls are looked at (and kept in
 * memory); nothing is stored or sent anywhere. The system binds the service when access is granted and
 * unbinds it when revoked; both nudge the repository to re-check.
 */
@AndroidEntryPoint
class MediaListenerService : NotificationListenerService() {

    @Inject lateinit var repository: MediaSessionRepository
    @Inject lateinit var calls: NotificationCallSource

    override fun onListenerConnected() {
        repository.refreshAccess()
        calls.reset(runCatching { activeNotifications }.getOrNull())
    }

    override fun onListenerDisconnected() {
        repository.refreshAccess()
        calls.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        calls.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        calls.onRemoved(sbn.key)
    }

    override fun onDestroy() {
        super.onDestroy()
        calls.clear()
        repository.refreshAccess() // unbound after a revoke, whether or not "disconnected" came first
    }
}
