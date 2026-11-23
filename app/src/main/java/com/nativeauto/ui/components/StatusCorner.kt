package com.nativeauto.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.nativeauto.device.BatteryStatus

/**
 * What the top-right header shows besides its own buttons: the time (null while a clock pane is
 * on screen), the battery (null when hidden), the layout switcher and the way into settings.
 * The app runs with the system bars hidden, so this stands in for the status bar.
 */
@Immutable
class StatusCorner(
    val time: String?,
    val battery: BatteryStatus?,
    /** 4.3a: the tank is in its last segment and no Garage card is on screen: a red pump beside the battery. */
    val lowFuel: Boolean = false,
    val onOpenLayouts: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/** Provided by DashboardScreen; null in isolation (previews, card tests). */
val LocalStatusCorner = compositionLocalOf<StatusCorner?> { null }

/** The corner's time ([CardChrome] places it where there is room). */
@Composable
internal fun StatusClock(time: String) {
    Text(
        text = time,
        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(horizontal = 6.dp).testTag("corner_clock"),
    )
}

/** The layout switcher and settings buttons: always shown in the top-right header. */
@Composable
internal fun StatusButtons(corner: StatusCorner) {
    val haptics = rememberHaptics()
    ChromeButton(AppIcons.Layouts, "Layouts", "layouts_open") {
        haptics.tap()
        corner.onOpenLayouts()
    }
    ChromeButton(AppIcons.Settings, "Settings", "settings_open") {
        haptics.tap()
        corner.onOpenSettings()
    }
}

/** Small and glanceable: a battery glyph filled to the level, then the percentage. */
@Composable
internal fun BatteryIndicator(battery: BatteryStatus) {
    val low = !battery.charging && battery.percent <= LOW_BATTERY_PERCENT
    val tint = when {
        low -> MaterialTheme.colorScheme.error
        battery.charging -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val description = "Battery ${battery.percent}%" + if (battery.charging) ", charging" else ""
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .testTag("battery_indicator")
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (battery.charging) {
            Icon(AppIcons.Bolt, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Canvas(Modifier.size(width = 24.dp, height = 13.dp)) {
            val stroke = 1.5.dp.toPx()
            val nub = 2.dp.toPx()
            val body = Size(size.width - nub, size.height)
            drawRoundRect(tint, size = body, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(stroke))
            val inset = stroke + 1.dp.toPx()
            drawRoundRect(
                color = tint,
                topLeft = Offset(inset, inset),
                size = Size((body.width - 2 * inset) * battery.percent / 100f, body.height - 2 * inset),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
            drawRect(tint, topLeft = Offset(body.width, size.height * 0.3f), size = Size(nub, size.height * 0.4f))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${battery.percent}%",
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            color = tint,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private const val LOW_BATTERY_PERCENT = 15
