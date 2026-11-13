package com.nativeauto.changelog

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nativeauto.BuildConfig
import com.nativeauto.settings.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The changelog and which version the rider has seen it for. Kept apart from
 * `SettingsRepository` (same DataStore file, its own key), so the settings fakes stay as they are.
 */
interface ChangelogStore {
    suspend fun entries(): List<ChangelogEntry>

    /** The last version code seen; a fresh install counts as having seen the current one. */
    suspend fun lastSeen(): Int

    /** The stored value as it changes (the current build's code until something is stored). */
    val seen: Flow<Int>

    suspend fun markSeen(versionCode: Int)
}

@Singleton
class DataStoreChangelogStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ChangelogStore {

    private val store = context.settingsDataStore

    override suspend fun entries(): List<ChangelogEntry> = withContext(Dispatchers.IO) {
        runCatching { context.assets.open(FILE).bufferedReader().use { parseChangelog(it.readText()) } }
            .getOrDefault(emptyList())
    }

    override suspend fun lastSeen(): Int {
        val stored = store.data.first()[SEEN]
        val resolved = resolveLastSeen(stored, InstallInfo.isFreshInstall(context), BuildConfig.VERSION_CODE)
        if (stored == null) markSeen(resolved)
        return resolved
    }

    override val seen: Flow<Int> = store.data.map { it[SEEN] ?: BuildConfig.VERSION_CODE }

    override suspend fun markSeen(versionCode: Int) {
        store.edit { it[SEEN] = versionCode }
    }

    private companion object {
        const val FILE = "changelog.md"
        val SEEN = intPreferencesKey("changelog_seen_code")
    }
}

/** What the package manager knows about this install. */
object InstallInfo {
    /** True when this build was installed new, not updated over an older one. */
    fun isFreshInstall(context: Context): Boolean = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    } catch (e: PackageManager.NameNotFoundException) {
        true
    }
}
