package com.nativeauto

import android.os.Bundle
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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var locationPermission: LocationPermissionState
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        setContent {
            // Dark until DataStore answers (a few ms): the window is black anyway.
            val theme by settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            NativeAutoTheme(theme) {
                PermissionsGate(locationPermission) {
                    DashboardRoute()
                }
            }
        }
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
