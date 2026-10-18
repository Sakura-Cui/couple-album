package com.sakura.romance

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

internal object MorningPolicy {
    const val MAX_LOCATION_AGE = 7L * 24 * 60 * 60 * 1000
    const val MAX_FIX_AGE = 15L * 60 * 1000
    fun useLocation(permitted: Boolean, timestamp: Long, now: Long) = permitted && fresh(timestamp, now, MAX_LOCATION_AGE)
    const val MAX_WEATHER_AGE = 24L * 60 * 60 * 1000
    fun fresh(timestamp: Long, now: Long, maxAge: Long) = timestamp > 0 && now - timestamp in 0..maxAge
    fun nextRefresh(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(6, 0).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(6, 0).atZone(now.zone)
    }
    fun usable(date: String, place: String, zone: String, fetched: Long, currentPlace: String, now: Instant, currentZone: ZoneId): Boolean =
        date == now.atZone(currentZone).toLocalDate().toString() && place == currentPlace && zone == currentZone.id && fresh(fetched, now.toEpochMilli(), MAX_WEATHER_AGE)
    fun needsRefresh(lastRefresh: Long, hasToday: Boolean, now: ZonedDateTime): Boolean {
        val today = now.toLocalDate().atTime(6, 0).atZone(now.zone)
        val boundary = if (now.isBefore(today)) today.minusDays(1) else today
        return !hasToday || lastRefresh <= 0 || lastRefresh > now.toInstant().toEpochMilli() ||
            lastRefresh < boundary.toInstant().toEpochMilli()
    }
}
