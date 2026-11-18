package com.EntropicSoftwareLab.Schedule.ui.components

import java.time.LocalTime
import java.util.Locale
import org.junit.Test
import org.junit.Assert.assertEquals

class TimeFormatTest {
    private val time = LocalTime.of(13, 5)

    @Test
    fun twentyFourHourIsHhMm() {
        assertEquals("13:05", time.format(clockFormatter(true, Locale.UK)))
    }

    @Test
    fun twelveHourHasMeridiem() {
        val text = time.format(clockFormatter(false, Locale.US))
        assertEquals("1:05 PM", text)
    }
}
