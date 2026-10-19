package com.EntropicSoftwareLab.Schedule.ui.screens.month

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Pins [MonthViewModel.landingDay] — where a long scroll past the edge of a month lands. */
class MonthLandingTest {

    private val august = YearMonth.of(2026, 8)

    @Test
    fun `forward lands on the earliest day with content in the month`() {
        val dates = setOf(
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2026, 8, 12),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 9, 1),
        )
        assertEquals(
            LocalDate.of(2026, 8, 3),
            MonthViewModel.landingDay(dates, august, forward = true),
        )
    }

    @Test
    fun `backward lands on the latest day with content in the month`() {
        val dates = setOf(
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2026, 8, 12),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 9, 1),
        )
        assertEquals(
            LocalDate.of(2026, 8, 12),
            MonthViewModel.landingDay(dates, august, forward = false),
        )
    }

    @Test
    fun `an empty month falls back to the 1st going forward`() {
        assertEquals(
            LocalDate.of(2026, 8, 1),
            MonthViewModel.landingDay(emptySet(), august, forward = true),
        )
    }

    @Test
    fun `an empty month falls back to the last day going backward`() {
        assertEquals(
            LocalDate.of(2026, 8, 31),
            MonthViewModel.landingDay(emptySet(), august, forward = false),
        )
    }

    @Test
    fun `content in neighbouring months is ignored`() {
        val dates = setOf(LocalDate.of(2026, 7, 31), LocalDate.of(2026, 9, 1))
        assertEquals(
            LocalDate.of(2026, 8, 1),
            MonthViewModel.landingDay(dates, august, forward = true),
        )
        assertEquals(
            LocalDate.of(2026, 8, 31),
            MonthViewModel.landingDay(dates, august, forward = false),
        )
    }
}
