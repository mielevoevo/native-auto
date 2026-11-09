package com.nativeauto.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing for the moves the rider reported as sluggish (Rider-Feedback §12): maximize, swap,
 * settings, layout switch. Each runs without precompilation and with the baseline profile, so the
 * profile's effect shows. On the emulator only relative numbers mean anything.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DashboardBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    private fun frames(mode: CompilationMode, moves: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = {
            grantPermissions()
            startDashboardInDuo()
        },
        measureBlock = moves,
    )

    private val none = CompilationMode.None()
    private val profiled = CompilationMode.Partial(BaselineProfileMode.Require)

    @Test fun startupNone() = startup(none)
    @Test fun startupProfiled() = startup(profiled)

    @Test fun maximizeNone() = frames(none) { repeat(2) { maximizeAndBack() } }
    @Test fun maximizeProfiled() = frames(profiled) { repeat(2) { maximizeAndBack() } }

    @Test fun swapProfiled() = frames(profiled) { repeat(2) { swapAndBack() } }

    @Test fun settingsNone() = frames(none) { openAndCloseSettings() }
    @Test fun settingsProfiled() = frames(profiled) { openAndCloseSettings() }

    @Test fun layoutSwitchProfiled() = frames(profiled) {
        switchLayout("QUAD")
        switchLayout("DUO")
    }

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 3,
        setupBlock = { grantPermissions() },
    ) {
        startDashboardInDuo()
    }
}
