package com.nativeauto.ui.dashboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nativeauto.R
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import com.nativeauto.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a back press on the home screen does (Rider-Feedback §3). */
enum class BackStep { ShowHint, Close, AskToSave }

/**
 * Double back to close (9b). The first press shows "Press back again to close" for
 * [WINDOW_MS]; a second press inside it closes the app, after asking "Save this trip?" when
 * one is running or paused. The timer lives here, not in composition (v2 test dispatcher).
 */
@HiltViewModel
class CloseViewModel @Inject constructor(private val tracker: TripTracker) : ViewModel() {
    private val _hint = MutableStateFlow(false)
    val hint: StateFlow<Boolean> = _hint.asStateFlow()
    private var expire: Job? = null

    fun onBack(): BackStep {
        expire?.cancel()
        if (!_hint.value) {
            _hint.value = true
            expire = viewModelScope.launch {
                delay(WINDOW_MS)
                _hint.value = false
            }
            return BackStep.ShowHint
        }
        _hint.value = false
        val state = tracker.state.value
        return if (state == TripState.Running || state == TripState.Paused) BackStep.AskToSave else BackStep.Close
    }

    /** Ends and saves the trip (the recorder writes it), before the app closes. */
    fun save() = tracker.end()

    fun discard() = tracker.discard()

    companion object {
        const val WINDOW_MS = 2_000L
    }
}

/**
 * The home screen's back handling: [enabled] only when nothing else wants back (no sheet,
 * zoom or history). Draws the hint pill at the bottom of the dashboard and the save prompt.
 */
@Composable
fun BoxScope.CloseOnDoubleBack(enabled: Boolean, viewModel: CloseViewModel = hiltViewModel()) {
    val hint by viewModel.hint.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    fun close() = context.findActivity()?.finishAndRemoveTask()

    BackHandler(enabled) {
        when (viewModel.onBack()) {
            BackStep.ShowHint -> Unit
            BackStep.Close -> close()
            BackStep.AskToSave -> asking = true
        }
    }

    AnimatedVisibility(
        visible = hint,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
    ) {
        Box(
            Modifier
                .background(AppTheme.colors.surface, RoundedCornerShape(24.dp))
                .border(1.dp, AppTheme.colors.outline, RoundedCornerShape(24.dp))
                .padding(horizontal = 24.dp, vertical = 14.dp)
                .testTag("back_hint"),
        ) {
            Text(stringResource(R.string.back_hint), style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.text)
        }
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.save_prompt_title)) },
            text = { Text(stringResource(R.string.save_prompt_text)) },
            confirmButton = {
                FilledTonalButton(
                    onClick = { asking = false; viewModel.save(); close() },
                    modifier = Modifier.heightIn(min = 56.dp).testTag("save_prompt_save"),
                ) { Text(stringResource(R.string.save_prompt_save)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { asking = false; viewModel.discard(); close() },
                    modifier = Modifier.heightIn(min = 56.dp).testTag("save_prompt_discard"),
                ) { Text(stringResource(R.string.save_prompt_discard)) }
                TextButton(
                    onClick = { asking = false },
                    modifier = Modifier.heightIn(min = 56.dp).testTag("save_prompt_cancel"),
                ) { Text(stringResource(R.string.save_prompt_cancel)) }
            },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
