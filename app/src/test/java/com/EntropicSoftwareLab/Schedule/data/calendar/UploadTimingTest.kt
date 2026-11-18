package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadTimingTest {

    private val a = CalendarAccount("a@gmail.com", "com.google")
    private val b = CalendarAccount("b@gmail.com", "com.google")

    @Test
    fun startsTheClockOnFirstDirtyRow() {
        val out = UploadTiming.observe(emptyMap(), mapOf(a to 2), nowMs = 1_000)
        assertEquals(UploadTimingEntry(1_000, null, null), out[a])
    }

    @Test
    fun stillDirtyDoesNotResetTheClock() {
        val first = UploadTiming.observe(emptyMap(), mapOf(a to 1), nowMs = 1_000)
        val second = UploadTiming.observe(first, mapOf(a to 3), nowMs = 9_000)
        assertEquals(1_000L, second[a]?.pendingSinceMs)
        assertNull(second[a]?.lastDurationMs)
    }

    @Test
    fun finishingRecordsDurationAndClearsPending() {
        val started = mapOf(a to UploadTimingEntry(1_000, null, null))
        val out = UploadTiming.observe(started, mapOf(a to 0), nowMs = 13_500)
        assertEquals(UploadTimingEntry(null, 12_500, 13_500), out[a])
    }

    @Test
    fun clockGoingBackwardsGivesZeroNotNegative() {
        val started = mapOf(a to UploadTimingEntry(10_000, null, null))
        val out = UploadTiming.observe(started, emptyMap(), nowMs = 4_000)
        assertEquals(0L, out[a]?.lastDurationMs)
        assertEquals(4_000L, out[a]?.lastFinishedAtMs)
    }

    @Test
    fun accountAbsentFromDirtyMapFinishes() {
        val started = mapOf(a to UploadTimingEntry(1_000, 5, 6), b to UploadTimingEntry(2_000, null, null))
        val out = UploadTiming.observe(started, mapOf(b to 1), nowMs = 8_000)
        assertEquals(UploadTimingEntry(null, 7_000, 8_000), out[a])
        assertEquals(UploadTimingEntry(2_000, null, null), out[b])
    }

    @Test
    fun cleanAccountWithNoHistoryIsNotAdded() {
        val out = UploadTiming.observe(emptyMap(), mapOf(a to 0), nowMs = 1_000)
        assertTrue(out.isEmpty())
    }

    @Test
    fun allNullEntriesAreDropped() {
        val prev = mapOf(a to UploadTimingEntry(null, null, null))
        assertTrue(UploadTiming.observe(prev, emptyMap(), nowMs = 1).isEmpty())
    }

    @Test
    fun lastResultSurvivesANewUploadStarting() {
        val prev = mapOf(a to UploadTimingEntry(null, 4_000, 5_000))
        val out = UploadTiming.observe(prev, mapOf(a to 1), nowMs = 9_000)
        assertEquals(UploadTimingEntry(9_000, 4_000, 5_000), out[a])
    }

    @Test
    fun codecRoundTripsWithNullsAndOddNames() {
        val odd = CalendarAccount("Jo Smith <j@x.com>", "com.google")
        val map = mapOf(
            a to UploadTimingEntry(1, 2, 3),
            b to UploadTimingEntry(null, 20_000, 30_000),
            odd to UploadTimingEntry(5, null, null),
        )
        assertEquals(map, UploadTimingCodec.decode(UploadTimingCodec.encode(map)))
    }

    @Test
    fun codecRoundTripsEmpty() {
        assertTrue(UploadTimingCodec.decode(UploadTimingCodec.encode(emptyMap())).isEmpty())
        assertTrue(UploadTimingCodec.decode(null).isEmpty())
        assertTrue(UploadTimingCodec.decode("").isEmpty())
    }

    @Test
    fun codecSkipsMalformedLines() {
        val raw = listOf(
            "com.google\ta@gmail.com\t1\t2\t3",
            "too\tfew",
            "com.google\tb@gmail.com\tnotanumber\t\t",
            "com.google\tc@gmail.com\t1\t2\t3\t4",
            "",
            "\t\t1\t2\t3",
            "com.google\td@gmail.com\t\t\t9",
        ).joinToString("\n")
        val out = UploadTimingCodec.decode(raw)
        assertEquals(
            mapOf(
                CalendarAccount("a@gmail.com", "com.google") to UploadTimingEntry(1, 2, 3),
                CalendarAccount("d@gmail.com", "com.google") to UploadTimingEntry(null, null, 9),
            ),
            out,
        )
    }

    @Test
    fun durationPartsBoundaries() {
        assertEquals(DurationParts(DurationUnit.Seconds, 0, 0), UploadTiming.durationParts(0))
        assertEquals(DurationParts(DurationUnit.Seconds, 59, 0), UploadTiming.durationParts(59_999))
        assertEquals(DurationParts(DurationUnit.MinutesSeconds, 1, 0), UploadTiming.durationParts(60_000))
        assertEquals(DurationParts(DurationUnit.MinutesSeconds, 59, 59), UploadTiming.durationParts(3_599_999))
        assertEquals(DurationParts(DurationUnit.HoursMinutes, 1, 0), UploadTiming.durationParts(3_600_000))
        assertEquals(DurationParts(DurationUnit.HoursMinutes, 2, 5), UploadTiming.durationParts(2 * 3_600_000L + 5 * 60_000L + 59_000L))
        assertEquals(DurationParts(DurationUnit.Seconds, 0, 0), UploadTiming.durationParts(-5))
    }
}
