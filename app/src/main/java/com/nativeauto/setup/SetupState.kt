package com.nativeauto.setup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.nativeauto.settings.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 4.2: whether the first-run setup pages still have to be shown. Kept apart from `SettingsRepository` (same DataStore
 * file, its own key), like the changelog's last-seen version, so the settings fakes stay as they are.
 *
 * The decision is made once, by [start], before anything else writes a preference: a DataStore with nothing in it is
 * a new install (or one that was reset), so the pages show; any saved preference means an existing install, which
 * never sees them. The answer is stored, so an update half-way through the setup doesn't lose it.
 */
interface SetupState {
    /** True while the setup is to be shown; null until [start] has decided. */
    val pending: Flow<Boolean?>

    /** Decides once (no-op when decided). Call before the first preference is written. */
    suspend fun start()

    /** The rider finished the setup. */
    suspend fun complete()

    /** Show the setup again (tests, and nothing else). */
    suspend fun reopen()
}

/** Pure rule behind [SetupState.start]: show the pages only when no preference has ever been saved. */
fun setupPendingFor(storedPreferenceCount: Int): Boolean = storedPreferenceCount == 0

@Singleton
class DataStoreSetupState @Inject constructor(
    @ApplicationContext private val context: Context,
) : SetupState {

    private val store = context.settingsDataStore

    override val pending: Flow<Boolean?> = store.data.map { prefs -> prefs[DONE]?.not() }

    override suspend fun start() {
        store.edit { p ->
            if (DONE !in p) p[DONE] = !setupPendingFor(p.asMap().size)
        }
    }

    override suspend fun complete() {
        store.edit { it[DONE] = true }
    }

    override suspend fun reopen() {
        store.edit { it[DONE] = false }
    }

    /** What [pending] says right now (null if undecided). */
    suspend fun current(): Boolean? = pending.first()

    private companion object {
        val DONE = booleanPreferencesKey("setup_done")
    }
}
