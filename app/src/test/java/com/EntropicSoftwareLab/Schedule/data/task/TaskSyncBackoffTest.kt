package com.EntropicSoftwareLab.Schedule.data.task

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskSyncBackoffTest {
    @Test fun doublesFromTwentySeconds() {
        assertEquals(0L, TaskSync.insertBackoffMillis(0))
        assertEquals(20_000L, TaskSync.insertBackoffMillis(1))
        assertEquals(40_000L, TaskSync.insertBackoffMillis(2))
        assertEquals(80_000L, TaskSync.insertBackoffMillis(3))
    }

    @Test fun capsAtThirtyMinutes() {
        assertEquals(1_800_000L, TaskSync.insertBackoffMillis(8))
        assertEquals(1_800_000L, TaskSync.insertBackoffMillis(100))
        assertEquals(1_800_000L, TaskSync.insertBackoffMillis(Int.MAX_VALUE))
    }

    @Test fun backedOffOnlyInsideTheWindow() {
        assertEquals(true, TaskSync.insertBackedOff(1, 1_000L, 1_000L))
        assertEquals(true, TaskSync.insertBackedOff(1, 1_000L, 20_999L))
        assertEquals(false, TaskSync.insertBackedOff(1, 1_000L, 21_000L))
    }

    @Test fun clockGoingBackwardsExpiresTheBackoff() {
        assertEquals(false, TaskSync.insertBackedOff(5, 1_000_000L, 999_999L))
    }
}
