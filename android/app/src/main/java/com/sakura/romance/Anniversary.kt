package com.sakura.romance

import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val anniversaryStart = LocalDate.of(2026, 1, 1)

internal data class AnniversaryStatus(
    val headline: String
)

internal fun anniversaryStatus(today: LocalDate): AnniversaryStatus {
    if (today.isBefore(anniversaryStart)) {
        val remainingDays = ChronoUnit.DAYS.between(today, anniversaryStart)
        return AnniversaryStatus("距离我们的纪念日还有 $remainingDays 天")
    }

    val daysTogether = ChronoUnit.DAYS.between(anniversaryStart, today) + 1
    return AnniversaryStatus("我们在一起的第${daysTogether}天")
}
