package com.EntropicSoftwareLab.Schedule.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
}
