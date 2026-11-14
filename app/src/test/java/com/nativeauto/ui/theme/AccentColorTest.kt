package com.nativeauto.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nativeauto.settings.LEGACY_ACCENT_ARGB
import com.nativeauto.settings.initialiseAccent
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentColorTest {

    @Test fun `hex parses with or without the hash, short or long, any case`() {
        assertEquals(Color(0xFF3DDC84), parseHexColor("#3DDC84"))
        assertEquals(Color(0xFF3DDC84), parseHexColor("3ddc84"))
        assertEquals(Color(0xFFAABBCC), parseHexColor(" #abc "))
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("#GG0000"))
        assertNull(parseHexColor("#1234567"))
    }

    @Test fun `hex round trips`() {
        assertEquals("#3DDC84", Color(0xFF3DDC84).toHex())
        assertEquals("#000000", Color.Black.toHex())
        AccentPresets.forEach { assertEquals(it.dark, parseHexColor(it.dark.toHex())) }
    }

    @Test fun `contrast ratio is the WCAG one`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.001)
        // Symmetric.
        assertEquals(contrastRatio(Color(0xFF3DDC84), Color.Black), contrastRatio(Color.Black, Color(0xFF3DDC84)), 1e-9)
    }

    @Test fun `every preset reads on black, and its light variant reads on white`() {
        AccentPresets.forEach {
            assertTrue("${it.key} on black", contrastRatio(it.dark, Color.Black) >= MIN_ACCENT_CONTRAST)
            assertTrue("${it.key} light on white", contrastRatio(it.light, Color.White) >= MIN_ACCENT_CONTRAST)
        }
    }

    @Test fun `the light variant darkens a pale colour until it reads on white`() {
        val pale = Color(0xFF8CC8FF)
        val light = lightVariant(pale)
        assertTrue(contrastRatio(light, Color.White) >= LIGHT_TARGET_CONTRAST)
        assertTrue(light.luminance2() < pale.luminance2())
        // One that already reads is left alone.
        val dark = Color(0xFF0B7A3E)
        assertEquals(dark, lightVariant(dark))
    }

    @Test fun `the dark variant lightens a colour that vanishes on black`() {
        val navy = Color(0xFF0A1F44)
        val lifted = darkVariant(navy)
        assertTrue(contrastRatio(lifted, Color.Black) >= MIN_ACCENT_CONTRAST)
        assertEquals(Color(0xFF3DDC84), darkVariant(Color(0xFF3DDC84)))
    }

    @Test fun `a preset keeps its hand-picked light variant, a custom colour gets the computed one`() {
        val green = AccentPresets.first { it.key == "green" }
        assertEquals(green.dark, accentFor(green.dark, isDark = true))
        assertEquals(green.light, accentFor(green.dark, isDark = false))
        val custom = Color(0xFFFFE066)
        assertEquals(lightVariant(custom), accentFor(custom, isDark = false))
    }

    @Test fun `the hex field warns about colours hard to see on black or white`() {
        assertTrue(accentWarnings(Color(0xFF3DDC84)).size == 1) // fine on black, pale on white
        assertTrue(accentWarnings(Color(0xFF101030)).any { it.contains("black") })
        assertTrue(accentWarnings(Color(0xFFFFFF00)).any { it.contains("white") })
        assertTrue(accentWarnings(Color(0xFF808080)).isEmpty() || accentWarnings(Color(0xFF808080)).size <= 1)
    }

    @Test fun `every install starts cyan`() {
        assertEquals("cyan", initialAccent(hasSavedPreferences = false, freshInstall = true).key)
        assertEquals("cyan", initialAccent(hasSavedPreferences = true, freshInstall = true).key)
        assertEquals("cyan", initialAccent(hasSavedPreferences = false, freshInstall = false).key)
        assertEquals("cyan", initialAccent(hasSavedPreferences = true, freshInstall = false).key)
    }

    // ---- the migration on a real (temp) DataStore ----

    private fun TestStore(name: String) = PreferenceDataStoreFactory.create { File.createTempFile(name, ".preferences_pb").also { it.delete() } }

    @Test fun `an existing install with saved preferences and no accent key stays cyan`() = runTest {
        val store = TestStore("existing")
        store.edit { it[booleanPreferencesKey("show_battery")] = true } // any saved setting of v3.3 / 3.4.1
        store.initialiseAccent(freshInstall = false)
        assertEquals(LEGACY_ACCENT_ARGB, store.data.first()[intPreferencesKey("accent_color")])
        // Even if it looks freshly installed (a data restore), saved preferences mean existing.
        val store2 = TestStore("existing2")
        store2.edit { it[booleanPreferencesKey("show_battery")] = true }
        store2.initialiseAccent(freshInstall = true)
        assertEquals(LEGACY_ACCENT_ARGB, store2.data.first()[intPreferencesKey("accent_color")])
    }

    @Test fun `a fresh install starts cyan`() = runTest {
        val store = TestStore("fresh")
        store.initialiseAccent(freshInstall = true)
        assertEquals(0xFF3FC8E0.toInt(), DefaultAccent.dark.toArgb())
        assertEquals(DefaultAccent.dark.toArgb(), store.data.first()[intPreferencesKey("accent_color")])
    }

    @Test fun `an accent the rider already chose is never replaced`() = runTest {
        val store = TestStore("chosen")
        store.edit { it[intPreferencesKey("accent_color")] = 0xFFB794F6.toInt() }
        store.initialiseAccent(freshInstall = true)
        assertEquals(0xFFB794F6.toInt(), store.data.first()[intPreferencesKey("accent_color")])
    }

    @Test fun `running it twice changes nothing`() = runTest {
        val store = TestStore("twice")
        store.initialiseAccent(freshInstall = true)
        val first = store.data.first()[intPreferencesKey("accent_color")]
        store.edit { it[booleanPreferencesKey("show_battery")] = true }
        store.initialiseAccent(freshInstall = true)
        assertEquals(first, store.data.first()[intPreferencesKey("accent_color")])
        assertFalse(first == null)
    }
}
