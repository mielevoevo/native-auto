package com.nativeauto.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import com.nativeauto.garage.GarageBackup
import com.nativeauto.garage.GarageDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 9d's files: a trip's GPX and the ZIP backup are written to `cache/exports/` and handed to
 * Android's share sheet (Drive, Files, chat apps...); an import reads a ZIP the rider picked.
 * Each export replaces the folder's old files, so the cache doesn't grow.
 */
@Singleton
class TripFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TripDao,
    private val garage: GarageDao,
) {
    private val dir get() = File(context.cacheDir, "exports").apply { mkdirs() }

    /** A share intent for one trip's GPX. */
    suspend fun gpxShareIntent(trip: TripRecord): Intent = withContext(Dispatchers.IO) {
        val file = fresh(gpxFileName(trip))
        file.writeText(buildGpx(trip, dao.points(trip.id)))
        share(uri(file), "application/gpx+xml", file.name)
    }

    /** A share intent for a ZIP of every trip (save it to Drive or Files to keep it). */
    suspend fun backupShareIntent(): Intent = withContext(Dispatchers.IO) {
        val file = fresh("native-auto-backup-${LocalDate.now()}.zip")
        val trips = dao.observeAll().first().map { Backup.TripWithTrack(it, dao.points(it.id)) }
        // 11c: the Garage goes in the same file (bikes, odometer, fuel, services).
        val garageData = GarageBackup.collect(garage)
        file.outputStream().use { Backup.write(trips, it, garageData) }
        share(uri(file), "application/zip", file.name)
    }

    /** Restores a picked backup; returns how many trips were new. Throws with a reason on a bad file. */
    suspend fun import(source: Uri): ImportResult = withContext(Dispatchers.IO) {
        val contents = context.contentResolver.openInputStream(source)?.use(Backup::readAll)
            ?: throw IllegalArgumentException("Couldn't open the file")
        // The Garage first, so restored trips find their bikes.
        val garageAdded = contents.garage?.let { GarageBackup.restore(garage, it) } ?: 0
        ImportResult(trips = Backup.restore(dao, contents.trips), garageEntries = garageAdded)
    }

    private fun fresh(name: String): File {
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, name)
    }

    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun share(uri: Uri, mime: String, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** What an import added: trips, and Garage entries (bikes, readings, fill-ups, services). */
data class ImportResult(val trips: Int, val garageEntries: Int)
