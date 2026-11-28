package com.nativeauto.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.nativeauto.R
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nativeauto.history.Backup
import com.nativeauto.history.BackupScope
import com.nativeauto.history.TripFiles
import com.nativeauto.ui.garage.GarageDialog
import com.nativeauto.ui.garage.Segments
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The shortest password a backup takes. */
internal const val BACKUP_PASSWORD_MIN = 6

/** A protected backup waiting for its password; [wrong]: the last one didn't open it. */
data class PasswordRequest(val source: Uri, val wrong: Boolean = false)

/**
 * The backup and its restore (9d), behind both the settings' rows and the trip history's buttons. 4.8: Everything or the
 * Garage only, with an optional password, which lives only in the dialog and the call that uses it.
 */
class BackupController(private val files: TripFiles, private val scope: CoroutineScope) {
    private val _share = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    /** The share sheet to open for a finished backup. */
    val share: SharedFlow<Intent> = _share.asSharedFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _password = MutableStateFlow<PasswordRequest?>(null)
    val password: StateFlow<PasswordRequest?> = _password.asStateFlow()

    fun say(text: String?) {
        _message.value = text
    }

    fun backup(what: BackupScope, password: String?) = scope.launch {
        _message.value = "Preparing backup…"
        runCatching { files.backupShareIntent(what, password?.toCharArray()) }
            .onSuccess { _message.value = null; _share.emit(it) }
            .onFailure { _message.value = "Backup failed: ${it.message}" }
    }

    fun import(source: Uri, password: String? = null) = scope.launch {
        _message.value = "Importing…"
        _message.value = runCatching { files.import(source, password?.toCharArray()) }.fold(
            onSuccess = { r ->
                _password.value = null
                val trips = if (r.trips == 0) null else "${r.trips} ${if (r.trips == 1) "trip" else "trips"}"
                val garage = if (r.garageEntries == 0) null else "Garage data"
                listOfNotNull(trips, garage).takeIf { it.isNotEmpty() }?.joinToString(" and ", prefix = "Imported ") ?: "Nothing new in that backup"
            },
            onFailure = {
                if (it is Backup.PasswordException) {
                    _password.value = PasswordRequest(source, it.wrong)
                    if (it.wrong) "Wrong password: nothing was imported" else null
                } else {
                    _password.value = null
                    "Import failed: ${it.message}"
                }
            },
        )
    }

    fun cancelPassword() {
        _password.value = null
        _message.value = null
    }
}

@HiltViewModel
class BackupViewModel @Inject constructor(files: TripFiles) : ViewModel() {
    val backup = BackupController(files, viewModelScope)
}

/** The share sheet, the export dialog ([exporting]) and the password question. */
@Composable
internal fun BackupDialogs(backup: BackupController, exporting: Boolean, onExportDone: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    LaunchedEffect(backup) { backup.share.collect { context.startActivity(it) } }
    if (exporting) {
        BackupDialog(
            onBackup = { what, password -> haptics.confirm(); onExportDone(); backup.backup(what, password) },
            onDismiss = onExportDone,
        )
    }
    val request by backup.password.collectAsStateWithLifecycle()
    request?.let { r ->
        BackupPasswordDialog(
            wrong = r.wrong,
            onOpen = { haptics.tap(); backup.import(r.source, it) },
            onDismiss = { backup.cancelPassword() },
        )
    }
}

/** 4.8: what goes in the backup, and a password for it if wanted. Not saved across a recreation: the password stays here. */
@Composable
private fun BackupDialog(onBackup: (BackupScope, String?) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    var what by rememberSaveable { mutableStateOf(BackupScope.EVERYTHING) }
    var protect by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val ok = !protect || password.length >= BACKUP_PASSWORD_MIN
    GarageDialog(
        tag = "backup_dialog",
        title = stringResource(R.string.settings_backup_title),
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onBackup(what, password.takeIf { protect }) },
                enabled = ok,
                modifier = Modifier.heightIn(min = 56.dp).testTag("backup_save"),
            ) { Text(stringResource(R.string.settings_backup_action)) }
        },
    ) {
        val names = mapOf(
            BackupScope.EVERYTHING to stringResource(R.string.backup_everything),
            BackupScope.GARAGE to stringResource(R.string.backup_garage),
        )
        Segments(BackupScope.entries, what, { names.getValue(it) }, { "backup_scope_${it.name}" }) {
            haptics.tick()
            what = it
        }
        Text(
            stringResource(if (what == BackupScope.EVERYTHING) R.string.backup_everything_hint else R.string.backup_garage_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textDim,
        )
        // Once on, the hint is the warning: it stays in view over the field when the keyboard is up.
        SwitchRow(R.string.backup_password, if (protect) R.string.backup_password_warning else R.string.backup_password_hint, protect, "backup_protect") { haptics.toggle(it); protect = it }
        if (protect) {
            PasswordField(password, { password = it }, stringResource(R.string.backup_password_field, BACKUP_PASSWORD_MIN), password.isNotEmpty() && !ok, "backup_password") {
                if (ok) onBackup(what, password)
            }
        }
    }
}

@Composable
private fun BackupPasswordDialog(wrong: Boolean, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    GarageDialog(
        tag = "backup_password_dialog",
        title = stringResource(R.string.backup_password_ask),
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onOpen(password) },
                enabled = password.isNotEmpty(),
                modifier = Modifier.heightIn(min = 56.dp).testTag("backup_open"),
            ) { Text(stringResource(R.string.settings_import_action)) }
        },
    ) {
        PasswordField(password, { password = it }, stringResource(R.string.backup_password), wrong, "backup_password_input") { if (password.isNotEmpty()) onOpen(password) }
        if (wrong) {
            Text(
                stringResource(R.string.backup_password_wrong),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.red,
                modifier = Modifier.testTag("backup_password_wrong"),
            )
        }
    }
}

/** Shown as typed: a typo in a password nobody can recover costs more than a glance at the screen. */
@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean, tag: String, onDone: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(64)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** 4.3a: Back up and Import as two settings rows (button on the right), the message under them. 4.6.2: on the Others page. */
@Composable
internal fun BackupRows(viewModel: BackupViewModel = hiltViewModel()) {
    val message by viewModel.backup.message.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var exporting by rememberSaveable { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.backup.import(it) } }
    BackupDialogs(viewModel.backup, exporting) { exporting = false }
    ActionRow(
        title = stringResource(R.string.settings_backup_title),
        hint = stringResource(R.string.settings_backup_hint),
        action = stringResource(R.string.settings_backup_action),
        tag = "backup_export",
    ) { haptics.tap(); exporting = true }
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
