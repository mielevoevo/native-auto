package com.nativeauto.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods used by startup and every dashboard move, so they ship
 * precompiled (Rider-Feedback §12.4: the first animations stopped stuttering). Run with
 * `./gradlew :app:generateBaselineProfile`; the result lands in `app/src/release/generated/`.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        grantPermissions()
        startDashboardInDuo()
        fullJourney()
    }
}
