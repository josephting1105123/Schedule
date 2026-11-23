package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailCountTest {

    @Test
    fun unchangedCountCarriesSeriesCount() {
        assertTrue(carriesSeriesCount("FREQ=DAILY;COUNT=30", "FREQ=DAILY;COUNT=30"))
    }

    @Test
    fun countAddedToUnboundedSeriesIsUserPicked() {
        assertFalse(carriesSeriesCount("FREQ=DAILY;COUNT=10", "FREQ=DAILY"))
    }

    @Test
    fun changedRuleAndCountIsUserPicked() {
        assertFalse(carriesSeriesCount("FREQ=WEEKLY;COUNT=5", "FREQ=DAILY;COUNT=30"))
    }

    @Test
    fun changedRuleWithSameCountIsUserPicked() {
        assertFalse(carriesSeriesCount("FREQ=WEEKLY;COUNT=30", "FREQ=DAILY;COUNT=30"))
    }

    @Test
    fun partOrderDoesNotMatter() {
        assertTrue(
            carriesSeriesCount(
                "FREQ=DAILY;INTERVAL=2;COUNT=8",
                "COUNT=8;INTERVAL=2;FREQ=DAILY",
            ),
        )
    }

    @Test
    fun nullOriginalIsFalse() {
        assertFalse(carriesSeriesCount("FREQ=DAILY;COUNT=10", null))
    }

    @Test
    fun keysAreCaseInsensitiveAndBlankPartsIgnored() {
        assertTrue(carriesSeriesCount("freq=DAILY;count=12;; ", "FREQ=DAILY;COUNT=12"))
    }

    @Test
    fun neitherHavingCountIsFalse() {
        assertFalse(carriesSeriesCount("FREQ=DAILY", "FREQ=DAILY"))
    }

    @Test
    fun wkstDroppedByEditorStillCarriesSeriesCount() {
        assertTrue(
            carriesSeriesCount(
                "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
                "FREQ=WEEKLY;WKST=SU;BYDAY=MO;COUNT=10",
            ),
        )
    }

    @Test
    fun bydayAddedByEditorStillCarriesSeriesCount() {
        assertTrue(
            carriesSeriesCount(
                "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
                "FREQ=WEEKLY;COUNT=10",
            ),
        )
    }

    @Test
    fun rrulePrefixStrippedByEditorStillCarriesSeriesCount() {
        assertTrue(
            carriesSeriesCount(
                "FREQ=DAILY;COUNT=5",
                "RRULE:FREQ=DAILY;COUNT=5",
            ),
        )
    }

    @Test
    fun changedCountWithWkstInOriginalIsUserPicked() {
        assertFalse(
            carriesSeriesCount(
                "FREQ=DAILY;COUNT=7",
                "FREQ=DAILY;WKST=SU;COUNT=5",
            ),
        )
    }

    @Test
    fun unsupportedRuleWithRrulePrefixStillCarriesSeriesCount() {
        assertTrue(
            carriesSeriesCount(
                "FREQ=MONTHLY;BYDAY=3TH;COUNT=10",
                "RRULE:FREQ=MONTHLY;BYDAY=3TH;COUNT=10",
            ),
        )
    }

    @Test
    fun simpleEditedAgainstUnsupportedOriginalIsUserPicked() {
        assertFalse(
            carriesSeriesCount(
                "FREQ=WEEKLY;BYDAY=MO;COUNT=30",
                "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=30",
            ),
        )
    }
}
