package com.nativeauto.greeting

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nativeauto.settings.GreetingMode
import com.nativeauto.settings.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 4.3c: the greeting. Never while the rider is busy (a trip running or paused, navigation active: it waits for the next
 * open). Once a day: the first app open of the local day (the first-run setup counts: finishing it marks the day).
 * 4.6.2, every open: each [freshOpen] (a new start, or back after being away a while).
 */
fun shouldGreet(today: LocalDate, lastDay: LocalDate?, mode: GreetingMode, busy: Boolean, freshOpen: Boolean): Boolean = when {
    busy || mode == GreetingMode.OFF -> false
    mode == GreetingMode.EVERY_OPEN -> freshOpen
    else -> lastDay != today
}

/** "Good morning" 04:00–11:59, "Good afternoon" 12:00–17:59, "Good evening" otherwise. */
fun greetingFor(hour: Int): String = when (hour) {
    in 4..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * Which local day last had the greeting. Own key in the settings DataStore file (like the changelog's last-seen
 * version), outside `SettingsRepository`, so the settings fakes stay as they are.
 */
interface GreetingStore {
    suspend fun lastDay(): LocalDate?
    suspend fun markGreeted(day: LocalDate)

    /** Tests only: the next open greets again. */
    suspend fun clear()
}

@Singleton
class DataStoreGreetingStore @Inject constructor(
    @ApplicationContext context: Context,
) : GreetingStore {

    private val store = context.settingsDataStore

    override suspend fun lastDay(): LocalDate? =
        store.data.first()[LAST_DAY]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    override suspend fun markGreeted(day: LocalDate) {
        store.edit { it[LAST_DAY] = day.toString() }
    }

    override suspend fun clear() {
        store.edit { it.remove(LAST_DAY) }
    }

    private companion object {
        val LAST_DAY = stringPreferencesKey("greeting_last_day")
    }
}
