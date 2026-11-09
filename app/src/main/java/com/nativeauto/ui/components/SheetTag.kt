package com.nativeauto.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * A bottom sheet's test tag. Sheets live in their own window, outside the activity's semantics
 * root, so they re-expose test tags as resource ids for the benchmarks' UiAutomator (8d).
 */
fun Modifier.sheetTag(tag: String): Modifier = semantics { testTagsAsResourceId = true }.testTag(tag)
