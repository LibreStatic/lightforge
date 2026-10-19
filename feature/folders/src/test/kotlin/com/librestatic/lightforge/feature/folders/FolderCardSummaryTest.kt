package com.librestatic.lightforge.feature.folders

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderCardSummaryTest {
    private fun card(items: Long, bytes: Long = items * 10, folders: Int = 0) = FolderCardUi(
        key = "k",
        name = "n",
        itemCount = items,
        sizeBytes = bytes,
        subfolderCount = folders,
        cover = null,
        action = FolderAction.Enter(FolderLocation("v", "n/")),
        location = FolderLocation("v", "n/"),
    )

    @Test
    fun aFolderWithMediaCountsEverythingBelowItAndDropsTheSubfolderCount() {
        assertEquals(CardCountLine.Items(15, 150), cardCountLine(card(items = 15, folders = 1)))
        assertEquals(CardCountLine.Items(3, 30), cardCountLine(card(items = 3)))
    }

    @Test
    fun aFolderWithoutMediaSaysHowManyFoldersItHolds() {
        assertEquals(CardCountLine.Folders(2), cardCountLine(card(items = 0, folders = 2)))
    }

    @Test
    fun aFolderWithNothingIsEmpty() {
        assertEquals(CardCountLine.Empty, cardCountLine(card(items = 0)))
    }

    private val zone = ZoneId.of("UTC")

    private fun millis(year: Int, month: Int, day: Int) =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun theRangeShowsDaysWithinOneMonth() {
        assertEquals("yMMMd", dateRangeSkeleton(millis(2026, 7, 3), millis(2026, 7, 9), zone))
        assertEquals("yMMMd", dateRangeSkeleton(millis(2026, 7, 3), millis(2026, 7, 3), zone))
    }

    @Test
    fun theRangeShowsMonthsWithinOneYear() {
        assertEquals("yMMM", dateRangeSkeleton(millis(2026, 6, 12), millis(2026, 10, 17), zone))
    }

    @Test
    fun theRangeShowsYearsAcrossYears() {
        assertEquals("y", dateRangeSkeleton(millis(2023, 12, 31), millis(2026, 1, 1), zone))
    }

    @Test
    fun theRangeFollowsTheLocalCalendarNotUtc() {
        val ny = ZoneId.of("America/New_York")
        // 2026-07-01 02:00 UTC is still June 30 in New York.
        val start = millis(2026, 7, 1) + 2 * 3_600_000L
        assertEquals("yMMM", dateRangeSkeleton(start, millis(2026, 7, 20), ny))
        assertEquals("yMMMd", dateRangeSkeleton(start, millis(2026, 7, 20), zone))
    }
}
