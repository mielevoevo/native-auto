package com.nativeauto.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.6.3: the portrait scale holds together: readable minimums, no shape out of order, and the fixed-height cards add up. */
class PortraitSizesTest {
    private val all = mapOf("slim" to PortraitScale.Slim, "mini" to PortraitScale.Mini, "narrow" to PortraitScale.Narrow, "row" to PortraitScale.Row)

    @Test fun `nothing is smaller than 14 sp and only the slim rows go under 16 for reading lines`() {
        all.forEach { (name, s) ->
            assertTrue(name, s.figureLabel.value >= 14f)
            assertTrue(name, s.secondary.value >= if (s === PortraitScale.Slim) 14f else 16f)
            assertTrue(name, s.title.value > s.secondary.value)
            assertTrue("$name: play is glove-sized", s.play >= 56.dp)
        }
    }

    @Test fun `a bigger full-width card never has a smaller role`() {
        listOf(PortraitScale.Slim to PortraitScale.Mini, PortraitScale.Mini to PortraitScale.Row).forEach { (a, b) ->
            listOf<(PortraitSizes) -> Float>(
                { it.hero.value }, { it.value.value }, { it.valueWithUnit.value }, { it.valueLong.value }, { it.valueShared.value },
                { it.title.value }, { it.secondary.value }, { it.figure.value }, { it.date.value }, { it.glyph.value }, { it.art.value }, { it.play.value },
            ).forEach { role -> assertTrue(role(a) <= role(b)) }
        }
    }

    @Test fun `a value with its unit is at most the clock's size and a long one smaller still`() {
        all.forEach { (name, s) ->
            assertTrue(name, s.valueWithUnit.value <= s.value.value)
            assertTrue(name, s.valueLong.value < s.valueWithUnit.value)
        }
    }

    /** The turn card's stack (glyph, road lines, distance, arrival line, its gaps) inside the card's content height. */
    @Test fun `the navigation stack fits its card`() {
        fun height(s: PortraitSizes): Float = s.glyph.value + (if (s.glyph > 48.dp) 4f else 0f) + s.title.value * 1.25f * s.roadLines + 2f + s.valueShared.value + 2f + s.secondary.value * 1.25f
        assertTrue("slim ${height(PortraitScale.Slim)}", height(PortraitScale.Slim) <= 122f)
        assertTrue("mini ${height(PortraitScale.Mini)}", height(PortraitScale.Mini) <= 161f)
        assertTrue("row ${height(PortraitScale.Row)}", height(PortraitScale.Row) <= 230f)
    }

    @Test fun `the album art fits its card's height`() {
        fun fits(art: Dp, content: Float) = art.value + 8f <= content
        assertTrue(fits(PortraitScale.Slim.art, 122f))
        assertTrue(fits(PortraitScale.Mini.art, 161f))
        assertTrue(fits(PortraitScale.Row.art, 230f))
    }
}
