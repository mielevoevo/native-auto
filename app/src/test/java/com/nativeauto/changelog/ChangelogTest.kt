package com.nativeauto.changelog

import com.nativeauto.TestClock
import com.nativeauto.location.SpeedSampleSource
import com.nativeauto.nav.GeoPoint
import com.nativeauto.nav.NavState
import com.nativeauto.nav.Navigator
import com.nativeauto.nav.Place
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripTracker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChangelogTest {

    private val text = """
        # What's new

        ## 3.4.1 (19) · 2026-11-14
        - First thing
        - Second thing

        ## 3.3 (18) · 2026-11-09
        - Older thing

        ## broken header
        - ignored bullet

        ## 2.0 (3) · 2026-11-08
        - Layouts
    """.trimIndent()

    @Test fun parsesEntriesNewestFirst() {
        val e = parseChangelog(text)
        assertEquals(listOf(19, 18, 3), e.map { it.versionCode })
        assertEquals("3.4.1", e[0].versionName)
        assertEquals("2026-11-14", e[0].date)
        assertEquals(listOf("First thing", "Second thing"), e[0].bullets)
    }

    @Test fun aBrokenHeaderAndItsBulletsAreSkipped() {
        assertTrue(parseChangelog(text).none { it.bullets.contains("ignored bullet") })
    }

    @Test fun emptyOrGarbageTextGivesNothing() {
        assertTrue(parseChangelog("").isEmpty())
        assertTrue(parseChangelog("- a bullet with no header").isEmpty())
    }

    @Test fun showsOnlyVersionsNewerThanLastSeen() {
        val all = parseChangelog(text)
        assertEquals(listOf(19), entriesToShow(all, lastSeen = 18, current = 19, deferred = false).map { it.versionCode })
        assertEquals(emptyList<Int>(), entriesToShow(all, lastSeen = 19, current = 19, deferred = false).map { it.versionCode })
    }

    @Test fun neverShowsVersionsNewerThanTheRunningBuild() {
        val all = parseChangelog(text)
        assertEquals(listOf(18, 3), entriesToShow(all, lastSeen = 2, current = 18, deferred = false).map { it.versionCode })
    }

    @Test fun showsAtMostThreeVersions() {
        val all = (1..6).map { ChangelogEntry("1.$it", it, "", listOf("x")) }.sortedByDescending { it.versionCode }
        assertEquals(listOf(6, 5, 4), entriesToShow(all, lastSeen = 0, current = 6, deferred = false).map { it.versionCode })
    }

    @Test fun nothingWhileATripOrNavigationIsRunning() {
        val all = parseChangelog(text)
        assertTrue(entriesToShow(all, lastSeen = 18, current = 19, deferred = true).isEmpty())
    }

    @Test fun aFreshInstallHasSeenTheCurrentVersion() {
        assertEquals(19, resolveLastSeen(stored = null, freshInstall = true, current = 19))
    }

    @Test fun anUpdateFromBeforeTheChangelogSeesTheNewEntryOnce() {
        val seen = resolveLastSeen(stored = null, freshInstall = false, current = 19)
        assertEquals(BEFORE_CHANGELOG, seen)
        assertEquals(listOf(19), entriesToShow(parseChangelog(text), seen, 19, false).map { it.versionCode })
    }

    @Test fun aStoredVersionWins() {
        assertEquals(19, resolveLastSeen(stored = 19, freshInstall = true, current = 20))
        assertEquals(5, resolveLastSeen(stored = 5, freshInstall = false, current = 20))
    }

    /** The file in assets parses, newest first, and has an entry for the build being made. */
    @Test fun theShippedChangelogParsesAndCoversTheCurrentBuild() {
        val file = File("src/main/assets/changelog.md")
        val entries = parseChangelog(file.readText())
        assertTrue(entries.size >= 10)
        assertTrue(entries.all { it.bullets.isNotEmpty() })
        assertEquals(entries.map { it.versionCode }, entries.map { it.versionCode }.sortedDescending())
        assertEquals(com.nativeauto.BuildConfig.VERSION_CODE, entries.first().versionCode)
        assertEquals(com.nativeauto.BuildConfig.VERSION_NAME, entries.first().versionName)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChangelogViewModelTest {
    private object Silent : SpeedSampleSource { override val samples: Flow<SpeedSample> = emptyFlow() }

    private class Store(initial: Int) : ChangelogStore {
        val all = listOf(ChangelogEntry("9.9", Int.MAX_VALUE - 1, "d", listOf("x")))
        var marked: Int? = null
        override val seen = kotlinx.coroutines.flow.MutableStateFlow(initial)
        override suspend fun entries() = all
        override suspend fun lastSeen() = seen.value
        override suspend fun markSeen(versionCode: Int) { marked = versionCode; this.seen.value = versionCode }
    }

    private class FakeNavigator : Navigator {
        override val state = MutableStateFlow<NavState>(NavState.Idle)
        override fun routeTo(place: Place) = Unit
        override fun start() = Unit
        override fun retry() = Unit
        override fun stop() { state.value = NavState.Idle }
        override fun rename(place: Place) = Unit
    }

    private val main = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(store: ChangelogStore, nav: Navigator = FakeNavigator(), tracker: TripTracker = TripTracker(Silent, TestClock(testScheduler), backgroundScope)) =
        ChangelogViewModel(store, tracker, nav)

    /** Collecting keeps the WhileSubscribed state flow alive. */
    private fun TestScope.watch(vm: ChangelogViewModel) {
        backgroundScope.launch { vm.pending.collect { } }
    }

    @Test fun nothingPendingWhenTheCurrentVersionWasSeen() = runTest(main) {
        val store = Store(initial = com.nativeauto.BuildConfig.VERSION_CODE)
        val vm = vm(store)
        watch(vm); runCurrent()
        assertTrue(vm.pending.value.isEmpty())
    }

    @Test fun dismissMarksTheCurrentVersionSeen() = runTest(main) {
        val store = Store(initial = 1)
        val vm = vm(store)
        watch(vm); runCurrent()
        vm.dismiss(); runCurrent()
        assertEquals(com.nativeauto.BuildConfig.VERSION_CODE, store.marked)
        assertTrue(vm.pending.value.isEmpty())
    }

    @Test fun waitsWhileNavigationIsActive() = runTest(main) {
        val nav = FakeNavigator()
        val store = Store(initial = 1)
        // An entry the running build includes, so "pending" would normally be non-empty.
        val withEntry = object : ChangelogStore by store {
            override suspend fun entries() = listOf(ChangelogEntry("x", com.nativeauto.BuildConfig.VERSION_CODE, "d", listOf("b")))
        }
        val vm = vm(withEntry, nav)
        watch(vm); runCurrent()
        assertEquals(1, vm.pending.value.size)
        nav.state.value = NavState.Routing(Place("a", null, GeoPoint(0.0, 0.0)))
        runCurrent()
        assertTrue(vm.pending.value.isEmpty())
        nav.state.value = NavState.Idle
        runCurrent()
        assertEquals(1, vm.pending.value.size)
    }
}
