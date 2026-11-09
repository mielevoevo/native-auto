package com.nativeauto.debug

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nativeauto.BuildConfig
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 10a's debug search comparison: the query list, and both columns answering a tap. */
@RunWith(AndroidJUnit4::class)
class SearchTestScreenTest {
    @get:Rule val rule = createAndroidComposeRule<SearchTestActivity>()

    @Test fun aQueryAsksBothServices() {
        // Item 14: scroll the list to it first.
        rule.onNodeWithTag("search_test_queries").performScrollToNode(hasTestTag("search_test_q_Monas"))
        rule.onNodeWithTag("search_test_q_Monas", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("search_test_q_Monas", useUnmergedTree = true).performClick()
        // Photon answers one way or another (results, nothing, or a network failure on the emulator).
        rule.waitUntil(20_000) { rule.onAllNodesWithText("Searching…", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertTrue(rule.onAllNodesWithText("Tap a query", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun withoutAKeyTomTomSaysSo() {
        assumeFalse("a real TomTom key is configured", BuildConfig.HAS_TOMTOM_KEY)
        rule.onNodeWithTag("search_test_q_Malioboro", useUnmergedTree = true).performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("No TomTom key yet (local.properties: TOMTOM_API_KEY)", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("No TomTom key yet (local.properties: TOMTOM_API_KEY)", useUnmergedTree = true).assertIsDisplayed()
    }
}
