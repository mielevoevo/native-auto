package com.nativeauto.ui.components

import android.view.Gravity
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.nativeauto.ui.theme.AppTheme

/** How tall a portrait sheet may get: 90% of the screen, so the dashboard still shows above it (14b). */
internal const val PORTRAIT_SHEET_FRACTION = 0.9f

/**
 * The shell of the app's dialogs (14b). Landscape: a centred rounded card, as before. Portrait: a bottom sheet,
 * full width, at most [PORTRAIT_SHEET_FRACTION] of the screen tall, one column ([DialogColumns]); the content
 * scrolls inside when it must. The dialogs that need a primary action pinned at the bottom use [PinnedActionDialog].
 *
 * The orientation comes from [LocalPortrait], which the dialog's window inherits from the dashboard.
 */
@Composable
internal fun AppDialogSurface(
    tag: String,
    onDismiss: () -> Unit,
    maxWidth: Dp = 1000.dp,
    scroll: Boolean = true,
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val portrait = LocalPortrait.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (portrait) BottomGravity()
        val maxHeight = (LocalConfiguration.current.screenHeightDp * PORTRAIT_SHEET_FRACTION).dp
        Surface(
            shape = if (portrait) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) else RoundedCornerShape(28.dp),
            color = AppTheme.colors.surface,
            modifier = (if (portrait) Modifier.fillMaxWidth().heightIn(max = maxHeight) else Modifier.fillMaxWidth(0.92f).widthIn(max = maxWidth))
                .sheetTag(tag),
        ) {
            Column(
                Modifier
                    .padding(if (portrait) PaddingValues(horizontal = 20.dp, vertical = 16.dp) else PaddingValues(24.dp))
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}

/** Puts the dialog's window at the bottom of the screen, full width (the portrait sheet look). */
@Composable
private fun BottomGravity() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.setGravity(Gravity.BOTTOM)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/**
 * Two groups side by side in landscape; one under the other in portrait (a 400 dp screen has no room for two columns).
 * Inside, give each group `Column(column, ...)`: it is `weight(1f)` in a row, full width in a column.
 */
@Composable
internal fun DialogTwoColumns(content: @Composable DialogColumnsScope.() -> Unit) {
    if (LocalPortrait.current) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { DialogColumnsScope(null).content() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { DialogColumnsScope(this).content() }
    }
}

internal class DialogColumnsScope(private val row: RowScope?) {
    /** The modifier for one group of the two. */
    val column: Modifier get() = row?.run { Modifier.weight(1f) } ?: Modifier.fillMaxWidth()
}

/**
 * A portrait bottom sheet whose [action] (Save, Done, Cancel) sits in a pinned footer, always visible above the keyboard,
 * while [content] scrolls above it (14b). Landscape dialogs keep the action beside the title.
 */
@Composable
internal fun PinnedActionDialog(
    tag: String,
    title: String,
    onDismiss: () -> Unit,
    action: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BottomGravity()
        val maxHeight = (LocalConfiguration.current.screenHeightDp * PORTRAIT_SHEET_FRACTION).dp
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = AppTheme.colors.surface,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).sheetTag(tag),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                androidx.compose.material3.Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                Column(
                    Modifier.weight(1f, fill = false).padding(top = 12.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) { content() }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    action()
                }
            }
        }
    }
}
