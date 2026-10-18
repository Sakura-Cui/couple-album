package com.sakura.romance

import org.junit.Assert.*
import org.junit.Test

class PushConnectionPolicyTest {
    @Test fun startupPermissionCallbackAndReconnectNeverHitTwoSecondLimit() {
        val policy = PushConnectionPolicy()
        val times = listOf(10000L, 10005L, 10010L).map { it + policy.reserveDelay(it) }
        assertEquals(10000L, times.first())
        times.zipWithNext().forEach { (previous, next) -> assertTrue(next - previous > 2000L) }
        assertEquals(0L, policy.reserveDelay(20000L))
    }

    @Test fun alreadySubscribedIsSuccessButRateLimitIsNot() {
        val policy = PushConnectionPolicy()
        assertTrue(policy.subscriptionSucceeded(0))
        assertTrue(policy.subscriptionSucceeded(1))
        assertFalse(policy.subscriptionSucceeded(1002))
        assertFalse(policy.subscriptionSucceeded(10004))
    }
}
