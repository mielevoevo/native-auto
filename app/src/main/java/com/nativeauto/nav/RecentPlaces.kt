package com.nativeauto.nav

import com.nativeauto.core.WallClock
import com.nativeauto.history.RecentPlaceDao
import com.nativeauto.history.RecentPlaceRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Places navigated to, newest first: the search sheet lists them before anything is typed. */
interface RecentPlaces {
    val recent: Flow<List<Place>>
    suspend fun remember(place: Place)
}

@Singleton
class RoomRecentPlaces @Inject constructor(
    private val dao: RecentPlaceDao,
    private val wall: WallClock,
) : RecentPlaces {

    override val recent: Flow<List<Place>> = dao.observeRecent(KEEP).map { rows ->
        rows.map { Place(it.name, it.detail, GeoPoint(it.latitude, it.longitude)) }
    }

    override suspend fun remember(place: Place) {
        dao.upsert(
            RecentPlaceRecord(
                key = placeKey(place.point),
                name = place.name,
                detail = place.detail,
                latitude = place.point.latitude,
                longitude = place.point.longitude,
                usedAtMs = wall.nowMs(),
            ),
        )
        dao.trim(KEEP)
    }

    private companion object {
        const val KEEP = 8
    }
}

/** ~10 m grid: picking the same place again moves it to the top instead of adding a row. */
internal fun placeKey(p: GeoPoint): String = String.format(Locale.ROOT, "%.4f,%.4f", p.latitude, p.longitude)
