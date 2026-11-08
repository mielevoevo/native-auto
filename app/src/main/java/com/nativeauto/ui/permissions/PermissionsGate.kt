package com.nativeauto.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.nativeauto.location.LocationPermissionState
import com.nativeauto.location.TripService

/**
 * Runtime-permission onboarding, in the order the handoff asks for (§9, Phase 4):
 * precise location → notifications (Android 13+) → start [TripService].
 *
 * - Asks once per launch; a denial shows as `GpsStatus.PermissionDenied` ("Denied") on the
 *   speed card, which offers a retry through [LocalLocationAccess].
 * - Each time the app comes to the foreground it re-checks access (it may have changed in
 *   system settings) and (re)starts the service, so tracking resumes after "Stop".
 * - Notifications are optional: without them the trip still runs, just without the shade entry.
 *
 * Notification *listener* access (media, Phase 6) isn't a runtime permission and isn't here.
 */
@Composable
fun PermissionsGate(permission: LocationPermissionState, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var askedLocation by rememberSaveable { mutableStateOf(false) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or not, tracking goes ahead: the notification is a nicety.
        if (permission.granted.value) TripService.start(context)
    }

    fun afterLocationGranted() {
        if (needsNotificationPermission(context) && !askedNotifications) {
            askedNotifications = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TripService.start(context)
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permission.refresh()
        if (permission.granted.value) afterLocationGranted()
    }

    fun requestLocation() {
        askedLocation = true
        // Both are requested so Android 12+ shows the precise/approximate choice.
        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(Unit) {
        if (!permission.granted.value && !askedLocation) requestLocation()
    }

    LifecycleStartEffect(Unit) {
        permission.refresh()
        if (permission.granted.value) afterLocationGranted()
        onStopOrDispose { }
    }

    val access = remember(activity) {
        LocationAccess(
            requestPermission = {
                // After a second denial Android stops showing the dialog (no rationale), so
                // the only way left is the app's settings page.
                val dialogPossible = !askedLocation || activity == null ||
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                if (dialogPossible) requestLocation() else context.openAppSettings()
            },
            openLocationSettings = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).newTaskIfNeeded(activity))
            },
        )
    }

    CompositionLocalProvider(LocalLocationAccess provides access) {
        content()
    }
}

/** Actions the speed card offers when it can't show a speed. No-ops outside the gate. */
@Stable
class LocationAccess(
    val requestPermission: () -> Unit,
    val openLocationSettings: () -> Unit,
) {
    companion object {
        val None = LocationAccess(requestPermission = {}, openLocationSettings = {})
    }
}

val LocalLocationAccess = staticCompositionLocalOf { LocationAccess.None }

private fun needsNotificationPermission(context: Context) =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Intent.newTaskIfNeeded(activity: Activity?) =
    if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else this
