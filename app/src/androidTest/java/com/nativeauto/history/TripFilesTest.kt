package com.nativeauto.history

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nativeauto.di.SettingsEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 9d on a device: the FileProvider really serves the exported GPX and the ZIP backup, and a
 * backup imports back (skipping trips already there).
 */
@RunWith(AndroidJUnit4::class)
class TripFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry = EntryPointAccessors.fromApplication(context.applicationContext, SettingsEntryPoint::class.java)
    private val dao = entry.tripDao()
    private val files = entry.tripFiles()
    private val start = 1_000_000_000_000L // 2001: never collides with a real ride

    private val trip = TripRecord(
        startedAtMs = start, endedAtMs = start + 60_000, durationMs = 60_000, movingMs = 60_000, distanceM = 900.0,
        maxSpeedMps = 16f, startLat = -7.8, startLon = 110.37, endLat = -7.79, endLon = 110.37, track = "",
    )

    @After fun clean() = runBlocking {
        dao.observeAll().first().filter { it.startedAtMs == start }.forEach { dao.delete(it.id) }
    }

    private fun Intent.sharedUri(): Uri {
        val send = IntentCompat.getParcelableExtra(this, Intent.EXTRA_INTENT, Intent::class.java)!!
        return IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
    }

    @Test fun gpxIsServedThroughTheFileProvider() = runBlocking {
        val id = dao.insertWithTrack(
            trip,
            List(3) { i -> TrackPointRecord(tripId = 0, timeMs = start + i * 1_000L, latitude = -7.8 + i * 1e-4, longitude = 110.37, speedMps = 15f, bearingDeg = 0f, accuracyM = 4f) },
        )
        val uri = files.gpxShareIntent(dao.observe(id).first()!!).sharedUri()
        assertEquals("content", uri.scheme)
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        assertTrue(text.contains("<gpx"))
        assertEquals(3, Regex("<trkpt ").findAll(text).count())
    }

    @Test fun aBackupImportsBackWithoutDuplicates() = runBlocking {
        dao.insertWithTrack(trip, emptyList())
        val uri = files.backupShareIntent().sharedUri()

        // Same file again: everything is already there.
        assertEquals(0, files.import(uri))
        // Remove the trip, then restore it from the backup.
        dao.observeAll().first().filter { it.startedAtMs == start }.forEach { dao.delete(it.id) }
        assertEquals(1, files.import(uri))
        assertEquals(900.0, dao.observeAll().first().single { it.startedAtMs == start }.distanceM, 0.0)
    }
}
