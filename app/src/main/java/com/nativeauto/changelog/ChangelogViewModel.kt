package com.nativeauto.changelog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.BuildConfig
import com.nativeauto.nav.NavState
import com.nativeauto.nav.Navigator
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the "What's new" sheet shown once after an update, and the full history in settings.
 * The sheet waits while a trip is running or paused or navigation is active: nothing covers the
 * dashboard on the bike.
 */
@HiltViewModel
class ChangelogViewModel @Inject constructor(
    private val store: ChangelogStore,
    tracker: TripTracker,
    navigator: Navigator,
) : ViewModel() {

    private val _history = MutableStateFlow<List<ChangelogEntry>>(emptyList())

    /** The whole file, newest first (settings' "What's new"). */
    val history: StateFlow<List<ChangelogEntry>> = _history.asStateFlow()

    private val lastSeen = MutableStateFlow<Int?>(null)

    init {
        viewModelScope.launch {
            _history.value = store.entries()
            store.lastSeen() // resolves (and stores) the first-run value
            store.seen.collect { lastSeen.value = it }
        }
    }

    /** The versions to show now, empty when there is nothing new or the rider is busy. */
    val pending: StateFlow<List<ChangelogEntry>> =
        combine(_history, lastSeen, tracker.state, navigator.state) { all, seen, trip, nav ->
            val busy = trip == TripState.Running || trip == TripState.Paused || nav !is NavState.Idle
            if (seen == null) emptyList() else entriesToShow(all, seen, BuildConfig.VERSION_CODE, busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The sheet was closed: the current version counts as seen. */
    fun dismiss() {
        lastSeen.value = BuildConfig.VERSION_CODE
        viewModelScope.launch { store.markSeen(BuildConfig.VERSION_CODE) }
    }
}
