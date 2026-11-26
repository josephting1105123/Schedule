package com.EntropicSoftwareLab.Schedule.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a time lands along one half-day row of the Day at a glance widget. 10dp cells with a 2dp
 * gap put cell `n` at `12 * n`, so every expected value below can be read off by hand.
 */
class DayGlanceGeometryTest {

    private val cellW = 10.dp
    private val gap = 2.dp

    private fun x(t: Float, isEnd: Boolean = false): Float =
        hourOffsetX(t, cellW, gap, isEnd).value

    private fun assertDp(expected: Float, actual: Dp) = assertEquals(expected, actual.value, 0.001f)

    @Test
    fun `start of the row is zero`() {
        assertEquals(0f, x(0f), 0.001f)
        assertEquals(0f, x(0f, isEnd = true), 0.001f)
    }

    @Test
    fun `a time inside an hour lands partway across that cell`() {
        // Cell 3 starts at 36dp; half of a 10dp cell is 5dp.
        assertEquals(41f, x(3.5f), 0.001f)
        assertEquals(41f, x(3.5f, isEnd = true), 0.001f)
    }

    @Test
    fun `a whole hour start is the left edge of that cell`() {
        assertEquals(60f, x(5f), 0.001f)
    }

    @Test
    fun `a whole hour end is the right edge of the cell before it`() {
        // Left edge of cell 5 is 60dp; the end of a bar through cell 4 is 48 + 10 = 58dp, which
        // leaves the 2dp gap clear instead of reaching into cell 5.
        assertEquals(58f, x(5f, isEnd = true), 0.001f)
        assertDp(60f - gap.value, hourOffsetX(5f, cellW, gap, isEnd = true))
    }

    @Test
    fun `the end of the row is the right edge of the last cell`() {
        // Cell 11 starts at 132dp and is 10dp wide.
        assertEquals(142f, x(12f), 0.001f)
        assertEquals(142f, x(12f, isEnd = true), 0.001f)
    }

    @Test
    fun `isEnd only changes whole hours`() {
        assertEquals(x(7.25f), x(7.25f, isEnd = true), 0.001f)
    }

    @Test
    fun `snapToPx lands on the nearest whole device pixel`() {
        val densities = listOf(2.8125f, 2.625f, 2.75f, 3f)
        val inputs = listOf(0.dp, 1.5.dp, 2.dp, 13.37.dp, 29.8667.dp, 101.23.dp)
        for (density in densities) {
            for (input in inputs) {
                val snapped = snapToPx(input, density)
                val px = snapped.value * density
                assertEquals(
                    "input=$input density=$density",
                    Math.round(input.value * density).toFloat(),
                    px,
                    0.001f,
                )
                assertEquals(
                    "idempotent for input=$input density=$density",
                    px,
                    snapToPx(snapped, density).value * density,
                    0.001f,
                )
            }
        }
    }

    @Test
    fun `snappedCellGeometry gives whole-pixel cells and gaps that fit inside innerW`() {
        val cases = listOf(
            383.29.dp to 2.8125f, // Pixel 9 Pro 1280px case
            320.dp to 2.625f,
            360.dp to 2.75f,
            250.dp to 3f,
        )
        for ((innerW, density) in cases) {
            val (snappedCell, snappedGap) = snappedCellGeometry(innerW, 2.dp, density)
            val cellPx = snappedCell.value * density
            val gapPx = snappedGap.value * density
            val innerPx = innerW.value * density

            assertEquals("cellPx whole ($innerW @ $density)", Math.round(cellPx).toFloat(), cellPx, 0.001f)
            assertEquals("gapPx whole ($innerW @ $density)", Math.round(gapPx).toFloat(), gapPx, 0.001f)
            assertTrue("gapPx >= 1 ($innerW @ $density)", gapPx >= 1f - 0.001f)

            val rowPx = 12f * cellPx + 11f * gapPx
            assertTrue("row ($rowPx) <= innerW ($innerPx)", rowPx <= innerPx + 0.001f)
            assertTrue("row uses all available cell pixels ($rowPx vs $innerPx)", innerPx - rowPx < 12f)
        }
    }

    @Test
    fun `snappedCellGeometry honours the 10dp cell floor on degenerate widths`() {
        val density = 2.8125f
        val (snappedCell, snappedGap) = snappedCellGeometry(40.dp, 2.dp, density)
        assertEquals(Math.round(10f * density).toFloat(), snappedCell.value * density, 0.001f)
        assertTrue(snappedGap.value * density >= 1f - 0.001f)
    }

    @Test
    fun `emitPx survives both truncation and rounding across all screen densities`() {
        for (dpi in 120..640) {
            val d = dpi * (1f / 160)
            for (px in 1..400) {
                assertEquals(px, (emitPx(px, d).value * d).toInt())
                assertEquals(px, (emitPx(px, d).value * d + 0.5f).toInt())
            }
        }
    }

    @Test
    fun `noBreakTitle replaces spaces and line breaks with no-break spaces`() {
        assertEquals("Team\u00A0meeting", noBreakTitle("Team meeting"))
        assertEquals("a\u00A0\u00A0b", noBreakTitle("a  b"))
        val converted = noBreakTitle("x\ny\tz")
        assertEquals("x\u00A0y\u00A0z", converted)
        assertTrue('\n' !in converted && '\t' !in converted)
        assertEquals("x\u00A0y", noBreakTitle("x\ry"))
        assertEquals("", noBreakTitle(""))
    }

    @Test
    fun `halfHeight adds 22dp for each clash lane beyond lane 0`() {
        assertDp(40f, halfHeight(1))
        assertDp(62f, halfHeight(2))
        assertDp(84f, halfHeight(3))
    }
}
