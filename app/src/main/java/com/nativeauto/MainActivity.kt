package com.nativeauto

import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nativeauto.location.LocationPermissionState
import com.nativeauto.ui.dashboard.DashboardRoute
import com.nativeauto.ui.permissions.PermissionsGate
import com.nativeauto.ui.theme.NativeAutoTheme
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.ThemeMode
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.nativeauto.core.SelfClose
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var locationPermission: LocationPermissionState
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var selfClose: SelfClose
    @Inject lateinit var tracker: TripTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        // 9a: ten minutes after a trip is ended from the notification, an app left in the
        // background closes itself.
        lifecycleScope.launch { selfClose.closeRequests.collect { finishAndRemoveTask() } }

        setContent {
            // Dark until DataStore answers (a few ms): the window is black anyway.
            val theme by settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            NativeAutoTheme(theme) {
                // Exposes testTags as resource ids, so the benchmarks (UiAutomator) can find buttons.
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    PermissionsGate(locationPermission) {
                        DashboardRoute()
                    }
                }
            }
        }
    }

    // Phase 8d (Rider-Feedback §12.5): on the mount, sun + charging + GPS + screen can throttle the
    // phone. Each thermal status change is logged ("NativeAuto/Thermal"), so a sluggish ride can be
    // matched against it with `adb logcat -s NativeAuto/Thermal`.
    @get:RequiresApi(Build.VERSION_CODES.Q)
    private val thermalListener by lazy {
        PowerManager.OnThermalStatusChangedListener { status ->
            Log.i(THERMAL_TAG, "thermal status ${thermalName(status)} ($status)")
        }
    }

    override fun onStart() {
        super.onStart()
        selfClose.onVisible()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(PowerManager::class.java)?.addThermalStatusListener(mainExecutor, thermalListener)
        }
    }

    override fun onStop() {
        selfClose.onHidden()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(PowerManager::class.java)?.removeThermalStatusListener(thermalListener)
        }
        super.onStop()
    }

    /**
     * 9a, closing the app stops everything: when the activity really finishes (not a rotation),
     * a trip in progress is ended and saved; the service then stops on its own, and the map's
     * GPS read ends with the activity. The Save / Discard prompt on double back is 9b.
     */
    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            val state = tracker.state.value
            if (state == TripState.Running || state == TripState.Paused) tracker.end()
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Bars come back after dialogs / the notification shade; re-hide when focus returns.
        if (hasFocus) hideSystemBars()
    }

    /** Immersive sticky: bars stay hidden, a swipe reveals them transiently. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private const val THERMAL_TAG = "NativeAuto/Thermal"

internal fun thermalName(status: Int): String = when (status) {
    0 -> "none"
    1 -> "light"
    2 -> "moderate"
    3 -> "severe"
    4 -> "critical"
    5 -> "emergency"
    6 -> "shutdown"
    else -> "unknown"
}
