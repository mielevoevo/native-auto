package com.nativeauto.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.location.LiveLocation
import com.nativeauto.location.LocationPermissionState
import com.nativeauto.nav.NavState
import com.nativeauto.nav.Navigator
import com.nativeauto.nav.GeoPoint
import com.nativeauto.nav.Place
import com.nativeauto.nav.PlaceSearch
import com.nativeauto.settings.SpeedUnit
import java.util.Locale
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The map's inputs. The position comes from [LiveLocation] (shared with the trip and
 * navigation); while navigating it's the fix snapped to the route, so the dot rides the line.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    live: LiveLocation,
    tracker: TripTracker,
    permission: LocationPermissionState,
    private val settings: SettingsRepository,
    private val navigator: Navigator,
    private val places: PlaceSearch,
) : ViewModel() {

    val nav: StateFlow<NavState> = navigator.state

    private val sleep = MapSleep(viewModelScope)

    /** False once the map has been off screen past the grace period: the view is released. */
    val awake: StateFlow<Boolean> = sleep.awake

    fun setOnScreen(visible: Boolean) = sleep.setOnScreen(visible)

    /** The camera across map views (sleep, configuration changes). */
    internal val camera = MapCameraMemory()

    val fix: StateFlow<SpeedSample?> = combine(live.fixes, navigator.state) { f, n ->
        (n as? NavState.Navigating)?.position ?: f
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), tracker.lastFix.value)

    val unit: StateFlow<SpeedUnit> =
        settings.speedUnit.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedUnit.KMH)

    /** 13c: the route line's own colour; null follows the accent. */
    val routeColor: StateFlow<Int?> =
        settings.routeColor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val voiceGuidance: StateFlow<Boolean> =
        settings.voiceGuidance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setVoiceGuidance(enabled: Boolean) {
        viewModelScope.launch { settings.setVoiceGuidance(enabled) }
    }

    fun routeTo(place: Place) = navigator.routeTo(place)

    /** Long-press: route there at once; the pin gets a street name when the lookup answers. */
    fun routeToPoint(point: GeoPoint) {
        navigator.routeTo(Place(DROPPED_PIN, formatCoordinates(point), point))
        viewModelScope.launch {
            places.reverse(point)?.let { navigator.rename(it.copy(point = point)) }
        }
    }
    fun startNavigation() = navigator.start()
    fun retryRoute() = navigator.retry()
    fun stopNavigation() = navigator.stop()

    val locationGranted: StateFlow<Boolean> = permission.granted

    /** Remembered across launches, like the speed unit. */
    val showTraffic: StateFlow<Boolean> =
        settings.showTraffic.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setShowTraffic(enabled: Boolean) {
        viewModelScope.launch { settings.setShowTraffic(enabled) }
    }
}

private const val DROPPED_PIN = "Dropped pin"

internal fun formatCoordinates(p: GeoPoint): String = String.format(Locale.ROOT, "%.5f, %.5f", p.latitude, p.longitude)
