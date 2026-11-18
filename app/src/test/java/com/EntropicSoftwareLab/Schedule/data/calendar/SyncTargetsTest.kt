package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTargetsTest {

    private val a = CalendarAccount("a@gmail.com", "com.google")
    private val b = CalendarAccount("b@gmail.com", "com.google")
    private val c = CalendarAccount("c@gmail.com", "com.google")
    private val local = CalendarAccount("test@local", "LOCAL")

    private val all: (CalendarAccount) -> Boolean = { true }
    private val none: (CalendarAccount) -> Boolean = { false }

    @Test
    fun emptyInGivesEmptyOut() {
        assertEquals(emptyList<CalendarAccount>(), SyncTargets.toAsk(emptyList(), all, none))
    }

    @Test
    fun localAccountIsDropped() {
        assertEquals(listOf(a), SyncTargets.toAsk(listOf(local, a), all, none))
    }

    @Test
    fun invisibleAccountIsDropped() {
        assertEquals(listOf(a, c), SyncTargets.toAsk(listOf(a, b, c), { it != b }, none))
    }

    @Test
    fun busyAccountIsDropped() {
        assertEquals(listOf(b), SyncTargets.toAsk(listOf(a, b, c), all, { it == a || it == c }))
    }

    @Test
    fun duplicatesCollapse() {
        assertEquals(listOf(a, b), SyncTargets.toAsk(listOf(a, b, a, b, a), all, none))
    }

    @Test
    fun inputOrderIsKept() {
        assertEquals(listOf(c, a, b), SyncTargets.toAsk(listOf(c, a, b), all, none))
    }
}
