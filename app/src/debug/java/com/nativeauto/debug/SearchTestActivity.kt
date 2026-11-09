package com.nativeauto.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nativeauto.nav.GeoPoint
import com.nativeauto.nav.PhotonPlaceSearch
import com.nativeauto.nav.Place
import com.nativeauto.nav.PlaceSearch
import com.nativeauto.nav.TomTomPlaceSearch
import com.nativeauto.settings.ThemeMode
import com.nativeauto.trip.haversineMeters
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.theme.AppTheme
import com.nativeauto.ui.theme.NativeAutoTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Debug only (Rider-Feedback §7 test harness, 10a): one tap runs a local query on every search
 * service side by side, so they can be scored by hand (found / right place / position). Queries
 * run only on a tap, one at a time: Photon is fair-use and TomTom has a monthly quota.
 */
@AndroidEntryPoint
class SearchTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NativeAutoTheme(ThemeMode.DARK) { SearchTestScreen(onBack = ::finish) } }
    }
}

/** A test query and the town it's asked from (the search bias). */
data class TestQuery(val text: String, val city: City, val note: String)

enum class City(val label: String, val center: GeoPoint) {
    YK("Yogyakarta", GeoPoint(-7.7956, 110.3695)),
    JKT("Jakarta", GeoPoint(-6.1754, 106.8272)),
}

/** The fixed list from Rider-Feedback §7: landmarks, small businesses, addresses, a gang, typos. */
val TEST_QUERIES = listOf(
    TestQuery("Malioboro", City.YK, "landmark"),
    TestQuery("Stasiun Tugu", City.YK, "landmark"),
    TestQuery("UGM", City.YK, "abbreviation"),
    TestQuery("Tugu Jogja", City.YK, "landmark"),
    TestQuery("Alun-alun Kidul", City.YK, "landmark"),
    TestQuery("Ambarrukmo Plaza", City.YK, "mall"),
    TestQuery("warung makan", City.YK, "small business"),
    TestQuery("bengkel motor", City.YK, "small business"),
    TestQuery("kos putri", City.YK, "small business"),
    TestQuery("Jalan Kaliurang km 5", City.YK, "street + km"),
    TestQuery("Gang Sawo", City.YK, "gang"),
    TestQuery("Maliboro", City.YK, "misspelling"),
    TestQuery("Stasiun Tugo", City.YK, "misspelling"),
    TestQuery("Monas", City.JKT, "landmark"),
    TestQuery("GBK", City.JKT, "abbreviation"),
    TestQuery("Stasiun Gambir", City.JKT, "landmark"),
    TestQuery("Grand Indonesia", City.JKT, "mall"),
    TestQuery("Kota Tua", City.JKT, "landmark"),
    TestQuery("Jl. Sudirman 1", City.JKT, "street + number"),
    TestQuery("Monass", City.JKT, "misspelling"),
)

/** One service's answer to the current query. */
sealed interface ServiceResult {
    data object Idle : ServiceResult
    data object Loading : ServiceResult
    data class Unavailable(val why: String) : ServiceResult
    data class Done(val places: List<Place>) : ServiceResult
    data class Failed(val message: String) : ServiceResult
}

@HiltViewModel
class SearchTestViewModel @Inject constructor(
    private val photon: PhotonPlaceSearch,
    private val tomTom: TomTomPlaceSearch,
) : ViewModel() {
    private val _query = MutableStateFlow<TestQuery?>(null)
    val query: StateFlow<TestQuery?> = _query.asStateFlow()
    private val _photon = MutableStateFlow<ServiceResult>(ServiceResult.Idle)
    val photonResult: StateFlow<ServiceResult> = _photon.asStateFlow()
    private val _tomTom = MutableStateFlow<ServiceResult>(ServiceResult.Idle)
    val tomTomResult: StateFlow<ServiceResult> = _tomTom.asStateFlow()

    fun run(q: TestQuery) {
        _query.value = q
        viewModelScope.launch {
            val p = async { ask(photon, q, _photon) }
            if (tomTom.available) {
                ask(tomTom, q, _tomTom)
            } else {
                // WIP (10a): the key goes in local.properties as TOMTOM_API_KEY.
                _tomTom.value = ServiceResult.Unavailable("No TomTom key yet (local.properties: TOMTOM_API_KEY)")
            }
            p.await()
        }
    }

    private suspend fun ask(service: PlaceSearch, q: TestQuery, into: MutableStateFlow<ServiceResult>) {
        into.value = ServiceResult.Loading
        into.value = runCatching { service.search(q.text, q.city.center) }
            .fold({ ServiceResult.Done(it) }, { ServiceResult.Failed(it.message ?: it.javaClass.simpleName) })
    }
}

@Composable
private fun SearchTestScreen(onBack: () -> Unit, vm: SearchTestViewModel = hiltViewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val photon by vm.photonResult.collectAsStateWithLifecycle()
    val tomTom by vm.tomTomResult.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize().testTag("search_test"), color = AppTheme.colors.bg) {
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(0.8f).fillMaxHeight()) {
                Row {
                    IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) { Icon(AppIcons.ArrowBack, "Back") }
                    Text("Search test", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("search_test_queries")) {
                    items(TEST_QUERIES) { q ->
                        val selected = q == query
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .background(if (selected) AppTheme.colors.navy else AppTheme.colors.surface, RoundedCornerShape(12.dp))
                                .clickable { vm.run(q) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("search_test_q_${q.text}"),
                        ) {
                            Text(q.text, style = MaterialTheme.typography.titleMedium, color = if (selected) AppTheme.colors.onNavy else AppTheme.colors.text)
                            Text("${q.city.label} · ${q.note}", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textDim)
                        }
                    }
                }
            }
            ResultColumn("Photon (OSM)", photon, query, Modifier.weight(1f).testTag("search_test_photon"))
            ResultColumn("TomTom", tomTom, query, Modifier.weight(1f).testTag("search_test_tomtom"))
        }
    }
}

@Composable
private fun ResultColumn(title: String, result: ServiceResult, query: TestQuery?, modifier: Modifier) {
    Column(modifier.fillMaxHeight()) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
        when (result) {
            ServiceResult.Idle -> Dim("Tap a query")
            ServiceResult.Loading -> Dim("Searching…")
            is ServiceResult.Unavailable -> Dim(result.why)
            is ServiceResult.Failed -> Dim("Failed: ${result.message}")
            is ServiceResult.Done -> if (result.places.isEmpty()) Dim("Nothing found") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(result.places) { i, p ->
                    val center = query?.city?.center
                    val km = center?.let { haversineMeters(it.latitude, it.longitude, p.point.latitude, p.point.longitude) / 1000 }
                    Column {
                        Text("${i + 1}. ${p.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(p.detail, km?.let { String.format(Locale.ROOT, "%.1f km away", it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.textDim,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Dim(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.textDim, modifier = Modifier.testTag("search_test_note"))
}
