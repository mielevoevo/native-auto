package com.nativeauto.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.nativeauto.R
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nativeauto.history.TripFiles
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 13c: the backup (trips and Garage in one ZIP) and its restore, in settings. The same files and
 * rules as the trip history's own Back up / Import buttons ([TripFiles]).
 */
@HiltViewModel
class BackupViewModel @Inject constructor(private val files: TripFiles) : ViewModel() {
    private val _share = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    /** The share sheet to open for a finished backup. */
    val share: SharedFlow<Intent> = _share.asSharedFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun backup() = viewModelScope.launch {
        _message.value = "Preparing backup…"
        runCatching { files.backupShareIntent() }
            .onSuccess { _message.value = null; _share.emit(it) }
            .onFailure { _message.value = "Backup failed: ${it.message}" }
    }

    fun import(source: Uri) = viewModelScope.launch {
        _message.value = "Importing…"
        _message.value = runCatching { files.import(source) }.fold(
            onSuccess = { r ->
                val trips = if (r.trips == 0) null else "${r.trips} ${if (r.trips == 1) "trip" else "trips"}"
                val garage = if (r.garageEntries == 0) null else "Garage data"
                listOfNotNull(trips, garage).takeIf { it.isNotEmpty() }?.joinToString(" and ", prefix = "Imported ") ?: "Nothing new in that backup"
            },
            onFailure = { "Import failed: ${it.message}" },
        )
    }
}

/** 4.3a: Back up and Import as two settings rows (button on the right), the message under them. 4.6.2: on the Others page. */
@Composable
internal fun BackupRows(viewModel: BackupViewModel = hiltViewModel()) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    LaunchedEffect(viewModel) { viewModel.share.collect { context.startActivity(it) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::import) }
    ActionRow(
        title = stringResource(R.string.settings_backup_title),
        hint = stringResource(R.string.settings_backup_hint),
        action = stringResource(R.string.settings_backup_action),
        tag = "backup_export",
    ) { haptics.tap(); viewModel.backup() }
    ActionRow(
        title = stringResource(R.string.settings_import_title),
        hint = stringResource(R.string.settings_import_hint),
        action = stringResource(R.string.settings_import_action),
        tag = "backup_import",
    ) { haptics.tap(); pick.launch(arrayOf("application/zip", "application/octet-stream")) }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.text, modifier = Modifier.testTag("backup_message"))
    }
}
