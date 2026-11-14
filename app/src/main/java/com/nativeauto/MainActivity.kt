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
import android.content.Intent
import com.nativeauto.nav.IncomingLinks
import com.nativeauto.core.SelfClose
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import com.nativeauto.history.Recovery
import com.nativeauto.history.TripRecorder
import com.nativeauto.location.TripService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var locationPermission: LocationPermissionState
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var selfClose: SelfClose
    @Inject lateinit var tracker: TripTracker
    @Inject lateinit var links: IncomingLinks
    @Inject lateinit var recorder: TripRecorder
    @Inject lateinit var autoStart: com.nativeauto.location.AutoStartMonitor
    @Inject lateinit var calls: com.nativeauto.call.CallRepository
    @Inject lateinit var setup: com.nativeauto.setup.SetupState

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        // 9a: ten minutes after a trip is ended from the notification, an app left in the
        // background closes itself.
        lifecycleScope.launch { selfClose.closeRequests.collect { finishAndRemoveTask() } }
        // 10b: launched by a share or a geo: link. Not again on a recreation (rotation).
        if (savedInstanceState == null) openSharedLink(intent)
        // 13b: auto-start watches only while the app is on screen; a fresh open starts a clean session.
        if (savedInstanceState == null) autoStart.onAppOpened()
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { autoStart.watch() } }
        // 14b: the Orientation setting. A fixed mode is requested before the first frame, so a portrait rider never
        // sees the landscape start; Auto follows the system's rotation lock and a steady tilt (device/OrientationController).
        kotlinx.coroutines.runBlocking { settings.orientationMode.first() }.let { mode ->
            com.nativeauto.device.RequestedOrientation.fixed(mode)?.let { requestedOrientation = it }
        }
        val orientation = com.nativeauto.device.OrientationController(this, settings.orientationMode)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { orientation.run() } }

        // The accent is already stored (NativeAutoApp saw to it), so the first frame has the right colour.
        val firstAccent = kotlinx.coroutines.runBlocking { settings.accentColor.first() }
        // 4.2: decided in NativeAutoApp; read once so the first frame is the right screen.
        val firstPending = kotlinx.coroutines.runBlocking { setup.pending.first() }
        setContent {
            // Dark until DataStore answers (a few ms): the window is black anyway.
            val theme by settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            val accent by settings.accentColor.collectAsStateWithLifecycle(initialValue = firstAccent)
            NativeAutoTheme(theme, androidx.compose.ui.graphics.Color(accent)) {
                // Exposes testTags as resource ids, so the benchmarks (UiAutomator) can find buttons.
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    // 4.2: a new install starts with the setup pages (no permission gate behind them: they ask).
                    val pending by setup.pending.collectAsStateWithLifecycle(initialValue = firstPending)
                    when (pending) {
                        true -> com.nativeauto.ui.setup.SetupScreen()
                        false -> PermissionsGate(locationPermission) {
                            DashboardRoute()
                        }
                        null -> Unit
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSharedLink(intent)
    }

    /** A Google Maps share (name, address, link), any shared text, or a `geo:` URI → route preview. */
    private fun openSharedLink(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.startsWith("geo:", ignoreCase = true) }
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return
        links.open(text)
    }

    override fun onStart() {
        super.onStart()
        selfClose.onVisible()
        // 14a: the rider may have changed the phone permissions in system settings.
        calls.refreshPermissions()
        // 11b: a trip lost with the process (killed mid-ride) is resumed or saved. Checked once
        // per process, here because the trip service may only start while the app is visible.
        lifecycleScope.launch {
            if (recorder.recover() == Recovery.Resumed) TripService.start(this@MainActivity)
        }
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
