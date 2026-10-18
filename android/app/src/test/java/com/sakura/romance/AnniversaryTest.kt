package com.sakura.romance

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class AnniversaryTest {
    @Test
    fun dayBeforeStartShowsCountdown() {
        assertEquals(
            "距离我们的纪念日还有 1 天",
            anniversaryStatus(LocalDate.of(2025, 12, 31)).headline
        )
    }

    @Test
    fun startDateCountsAsDayOne() {
        assertEquals(
            "我们在一起的第1天",
            anniversaryStatus(LocalDate.of(2026, 1, 1)).headline
        )
    }

    @Test
    fun nextDateCountsAsDayTwo() {
        assertEquals(
            "我们在一起的第2天",
            anniversaryStatus(LocalDate.of(2026, 1, 2)).headline
        )
    }

    @Test
    fun currentProjectDateHasExpectedCount() {
        assertEquals(
            "我们在一起的第53天",
            anniversaryStatus(LocalDate.of(2026, 2, 22)).headline
        )
    }
}
