package com.nativeauto.ui.nav

import com.nativeauto.ui.components.sheetTag
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nativeauto.location.LiveLocation
import com.nativeauto.nav.GeoPoint
import com.nativeauto.nav.Place
import com.nativeauto.nav.PlaceSearch
import com.nativeauto.nav.MapsLinkResolver
import com.nativeauto.nav.findMapsUrl
import com.nativeauto.nav.RecentPlaces
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.haversineMeters
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the list shows: recent places until something is typed, then search results. */
data class SearchUiState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val failed: Boolean = false,
    val showingRecent: Boolean = true,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class DestinationSearchViewModel @Inject constructor(
    private val search: PlaceSearch,
    recents: RecentPlaces,
    private val live: LiveLocation,
    settings: SettingsRepository,
    private val links: MapsLinkResolver,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val recent = recents.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unit: StateFlow<SpeedUnit> = settings.speedUnit.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedUnit.KMH)

    val state: StateFlow<SearchUiState> = query
        // Fair use of the free geocoder: search on a pause in typing, not on every key.
        .debounce { if (it.trim().length < MIN_CHARS) 0L else DEBOUNCE_MS }
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.length < MIN_CHARS) {
                recent.map { SearchUiState(query = q, results = it, showingRecent = true) }
            } else if (findMapsUrl(q) != null) {
                // 10b: a pasted Google Maps link becomes its one place.
                flow {
                    emit(SearchUiState(query = q, searching = true, showingRecent = false))
                    emit(
                        try {
                            SearchUiState(query = q, results = listOf(links.resolve(q)), showingRecent = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            SearchUiState(query = q, failed = true, showingRecent = false)
                        },
                    )
                }
            } else {
                flow {
                    emit(SearchUiState(query = q, searching = true, showingRecent = false))
                    val near = live.latest?.let { GeoPoint(it.latitude, it.longitude) }
                    emit(
                        try {
                            SearchUiState(query = q, results = search.search(q, near), showingRecent = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            SearchUiState(query = q, failed = true, showingRecent = false)
                        },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    /** Distance from where the rider is, for each result line. */
    fun distanceTo(place: Place): Double? = live.latest?.let {
        haversineMeters(it.latitude, it.longitude, place.point.latitude, place.point.longitude)
    }

    private companion object {
        const val MIN_CHARS = 3
        const val DEBOUNCE_MS = 600L
    }
}

/**
 * Where to? Typed search (biased towards the current position) or a recent place. Meant for
 * a stop: the keyboard opens straight away and every row is a big target. Long-pressing the
 * map is the other way to pick a destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DestinationSearchSheet(
    onPick: (Place) -> Unit,
    onDismiss: () -> Unit,
    viewModel: DestinationSearchViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val focus = remember { FocusRequester() }
    var text by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 720.dp,
        modifier = Modifier.sheetTag("search_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            // The sheet is its own window, which the Compose keyboard controller doesn't reach;
            // ask the input method service directly, with this window's token.
            val view = LocalView.current
            val hideKeyboard = {
                view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
                Unit
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; viewModel.onQueryChange(it) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search_field"),
                placeholder = { Text("Where to?") },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = ""; viewModel.onQueryChange("") }, modifier = Modifier.size(56.dp)) {
                            Icon(AppIcons.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // In landscape the keyboard covers the list: the search key puts it away.
                keyboardActions = KeyboardActions(onSearch = { hideKeyboard() }),
            )
            Spacer(Modifier.padding(top = 8.dp))
            when {
                ui.searching -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Searching…")
                }
                ui.failed -> Text("Search needs a connection. Try again in a moment.", Modifier.padding(16.dp))
                ui.results.isEmpty() && !ui.showingRecent -> Text("Nothing found for \"${ui.query}\"", Modifier.padding(16.dp))
                ui.results.isEmpty() -> Text(
                    "Type a place or address, or long-press the map to drop a pin.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    if (ui.showingRecent) {
                        Text("Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("search_results")) {
                        items(ui.results, key = { "${it.point.latitude},${it.point.longitude},${it.name}" }) { place ->
                            PlaceRow(place, viewModel.distanceTo(place)?.let { formatNavDistance(it, unit) }, recent = ui.showingRecent) {
                                haptics.tap()
                                onPick(place)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun PlaceRow(place: Place, distance: String?, recent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .testTag("search_result"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (recent) AppIcons.History else AppIcons.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            place.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (distance != null) {
            Spacer(Modifier.width(12.dp))
            Text(distance, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
