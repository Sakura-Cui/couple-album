package com.sakura.romance

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MorningPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    @Test fun schedulesSixAMAndUsesLocalZone() {
        val before = ZonedDateTime.of(2026, 9, 18, 5, 59, 59, 0, zone)
        assertEquals(before.withHour(6).withSecond(0).withMinute(0), MorningPolicy.nextRefresh(before))
        val exact = before.withHour(6).withSecond(0).withMinute(0)
        assertEquals(exact.plusDays(1), MorningPolicy.nextRefresh(exact))
        val travel = before.withZoneSameInstant(ZoneId.of("Europe/Paris"))
        assertEquals(6, MorningPolicy.nextRefresh(travel).hour)
        assertEquals(0, MorningPolicy.nextRefresh(travel).minute)
        assertEquals(travel.zone, MorningPolicy.nextRefresh(travel).zone)
    }
    @Test fun rejectsExpiredFutureWrongDatePlaceAndTimezone() {
        val now = ZonedDateTime.of(2026, 9, 18, 6, 25, 0, 0, zone).toInstant()
        val fresh = now.minusSeconds(60).toEpochMilli()
        assertTrue(MorningPolicy.usable("2026-09-18", "a", zone.id, fresh, "a", now, zone))
        assertFalse(MorningPolicy.usable("2026-09-17", "a", zone.id, fresh, "a", now, zone))
        assertFalse(MorningPolicy.usable("2026-09-18", "a", zone.id, fresh, "b", now, zone))
        assertFalse(MorningPolicy.usable("2026-09-18", "a", "UTC", fresh, "a", now, zone))
        assertFalse(MorningPolicy.usable("2026-09-18", "a", zone.id, now.minusSeconds(86401).toEpochMilli(), "a", now, zone))
        assertFalse(MorningPolicy.fresh(now.plusSeconds(1).toEpochMilli(), now.toEpochMilli(), MorningPolicy.MAX_WEATHER_AGE))
    }
    @Test fun usesNanjingWhenPermissionMissingOrLocationExpired() {
        val now = 1_800_000_000_000L
        assertTrue(MorningPolicy.useLocation(true, now - 1000, now))
        assertFalse(MorningPolicy.useLocation(false, now - 1000, now))
        assertFalse(MorningPolicy.useLocation(true, now - MorningPolicy.MAX_LOCATION_AGE - 1, now))
        assertFalse(MorningPolicy.useLocation(true, now + 1, now))
    }
    @Test fun refreshesAtSixAndCatchesUpAfterMissedBackgroundRun() {
        val six = ZonedDateTime.of(2026, 9, 20, 6, 0, 0, 0, zone)
        val earlier = six.minusMinutes(1).toInstant().toEpochMilli()
        assertFalse(MorningPolicy.needsRefresh(earlier, true, six.minusSeconds(1)))
        assertTrue(MorningPolicy.needsRefresh(earlier, true, six))
        assertTrue(MorningPolicy.needsRefresh(earlier, true, six.withHour(14)))
        assertFalse(MorningPolicy.needsRefresh(six.toInstant().toEpochMilli(), true, six.plusHours(8)))
        assertTrue(MorningPolicy.needsRefresh(six.toInstant().toEpochMilli(), false, six.plusHours(8)))
    }
    @Test fun handlesMidnightClockRollbackAndTimezoneChange() {
        val midnight = ZonedDateTime.of(2026, 9, 20, 0, 0, 0, 0, zone)
        val yesterday = midnight.minusHours(2).toInstant().toEpochMilli()
        assertFalse(MorningPolicy.needsRefresh(yesterday, true, midnight))
        assertTrue(MorningPolicy.needsRefresh(yesterday, false, midnight))
        assertTrue(MorningPolicy.needsRefresh(midnight.plusHours(1).toInstant().toEpochMilli(), true, midnight))
        val paris = midnight.withZoneSameInstant(ZoneId.of("Europe/Paris"))
        assertTrue(MorningPolicy.needsRefresh(yesterday, false, paris))
    }
}
