package com.nativeauto.media

import android.service.notification.NotificationListenerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Exists only so the user can grant Notification access, which is what lets the app see
 * other apps' media sessions (§7). It doesn't read notifications. The system binds it when
 * access is granted and unbinds it when revoked; both nudge the repository to re-check.
 */
@AndroidEntryPoint
class MediaListenerService : NotificationListenerService() {

    @Inject lateinit var repository: MediaSessionRepository

    override fun onListenerConnected() {
        repository.refreshAccess()
    }

    override fun onListenerDisconnected() {
        repository.refreshAccess()
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.refreshAccess() // unbound after a revoke, whether or not "disconnected" came first
    }
}
