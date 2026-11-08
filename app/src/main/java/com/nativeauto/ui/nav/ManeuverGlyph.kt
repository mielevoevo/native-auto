package com.nativeauto.ui.nav

import androidx.compose.foundation.Canvas
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nativeauto.nav.Instruction
import com.nativeauto.nav.ManeuverKind
import com.nativeauto.nav.TurnDirection
import com.nativeauto.ui.components.AppIcons
import kotlin.math.cos
import kotlin.math.sin

/**
 * The maneuver as one bold arrow, readable at arm's length: a stem coming up from the bottom
 * that bends towards the turn. Roundabouts add the ring; arrival shows a pin.
 */
@Composable
internal fun ManeuverGlyph(instruction: Instruction, color: Color, modifier: Modifier) {
    val label = instruction.maneuverWords()
    if (instruction.kind == ManeuverKind.Arrive) {
        Icon(AppIcons.Place, contentDescription = label, tint = color, modifier = modifier)
        return
    }
    Canvas(modifier.semantics { contentDescription = label }) {
        val w = size.minDimension
        val stroke = w * 0.12f
        val cx = size.width / 2
        val bottom = size.height * 0.92f
        val mid = size.height * 0.52f
        when {
            instruction.direction == TurnDirection.UTurn -> uTurn(color, stroke, w)
            instruction.kind == ManeuverKind.Roundabout -> {
                val r = w * 0.17f
                val center = Offset(cx, size.height * 0.40f)
                drawCircle(color, r, center, style = Stroke(stroke * 0.8f))
                drawLine(color, Offset(cx, bottom), Offset(cx, center.y + r), stroke, StrokeCap.Round)
                val angle = angleOf(instruction.direction ?: TurnDirection.Straight)
                val start = center + direction(angle) * r
                arrow(color, stroke, start, start + direction(angle) * (w * 0.24f), angle)
            }
            else -> {
                val angle = angleOf(instruction.direction ?: TurnDirection.Straight)
                val bend = Offset(cx, mid)
                val tip = bend + direction(angle) * (w * 0.36f)
                drawLine(color, Offset(cx, bottom), bend, stroke, StrokeCap.Round)
                arrow(color, stroke, bend, tip, angle)
            }
        }
    }
}

/** Degrees from straight ahead; negative is left. */
private fun angleOf(d: TurnDirection): Float = when (d) {
    TurnDirection.Straight -> 0f
    TurnDirection.SlightLeft -> -45f
    TurnDirection.Left -> -90f
    TurnDirection.SharpLeft -> -135f
    TurnDirection.SlightRight -> 45f
    TurnDirection.Right -> 90f
    TurnDirection.SharpRight -> 135f
    TurnDirection.UTurn -> 180f
}

/** Unit vector on screen for a heading relative to "up". */
private fun direction(angleDeg: Float): Offset {
    val r = Math.toRadians(angleDeg.toDouble())
    return Offset(sin(r).toFloat(), -cos(r).toFloat())
}

private fun DrawScope.arrow(color: Color, stroke: Float, from: Offset, tip: Offset, angleDeg: Float) {
    val head = size.minDimension * 0.20f
    val dir = direction(angleDeg)
    val base = tip - dir * head
    drawLine(color, from, base, stroke, StrokeCap.Round)
    val side = Offset(-dir.y, dir.x) * (head * 0.85f)
    val path = Path().apply {
        moveTo(tip.x + dir.x * stroke * 0.3f, tip.y + dir.y * stroke * 0.3f)
        lineTo(base.x + side.x, base.y + side.y)
        lineTo(base.x - side.x, base.y - side.y)
        close()
    }
    drawPath(path, color)
    drawPath(path, color, style = Stroke(stroke * 0.3f, join = StrokeJoin.Round))
}

private fun DrawScope.uTurn(color: Color, stroke: Float, w: Float) {
    // Indonesia drives on the left, so a U-turn swings right; the glyph doesn't need to know:
    // up the left side, over the top, down the right with the head pointing down.
    val left = size.width / 2 - w * 0.18f
    val right = size.width / 2 + w * 0.18f
    val top = size.height * 0.30f
    val path = Path().apply {
        moveTo(left, size.height * 0.92f)
        lineTo(left, top)
        quadraticTo(left, top - w * 0.18f, size.width / 2, top - w * 0.18f)
        quadraticTo(right, top - w * 0.18f, right, top)
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    arrow(color, stroke, Offset(right, top), Offset(right, size.height * 0.80f), 180f)
}
