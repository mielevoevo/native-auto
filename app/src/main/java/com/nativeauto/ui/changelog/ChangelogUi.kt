package com.nativeauto.ui.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.R
import com.nativeauto.changelog.ChangelogEntry
import com.nativeauto.changelog.ChangelogViewModel
import com.nativeauto.ui.components.sheetTag
import com.nativeauto.ui.theme.AppTheme

/**
 * Shown once after an update: the new versions' entries only, never while a trip or navigation
 * runs ([ChangelogViewModel.pending] is empty then). Closing it marks the version as seen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogSheet(viewModel: ChangelogViewModel = hiltViewModel()) {
    val entries by viewModel.pending.collectAsStateWithLifecycle()
    if (entries.isEmpty()) return
    ModalBottomSheet(
        onDismissRequest = viewModel::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 900.dp,
        dragHandle = null,
        modifier = Modifier.sheetTag("changelog_sheet"),
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.changelog_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                FilledTonalButton(
                    onClick = viewModel::dismiss,
                    modifier = Modifier.heightIn(min = 56.dp).testTag("changelog_done"),
                ) { Text(stringResource(R.string.settings_done)) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Entries(entries)
            }
        }
    }
}

/** Settings' "What's new": the whole history, newest first. */
@Composable
fun ChangelogHistoryDialog(onDismiss: () -> Unit, viewModel: ChangelogViewModel = hiltViewModel()) {
    val entries by viewModel.history.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = AppTheme.colors.surface,
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 800.dp).sheetTag("changelog_history"),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.changelog_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 56.dp).testTag("changelog_history_done"),
                    ) { Text(stringResource(R.string.settings_done)) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Entries(entries)
                }
            }
        }
    }
}

@Composable
private fun Entries(entries: List<ChangelogEntry>) {
    entries.forEach { entry ->
        Column(Modifier.padding(bottom = 16.dp).testTag("changelog_entry_${entry.versionCode}")) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Version ${entry.versionName}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(entry.date, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textDim)
            }
            entry.bullets.forEach { line ->
                Row(Modifier.padding(top = 6.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.textDim, modifier = Modifier.padding(end = 10.dp))
                    Text(line, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
