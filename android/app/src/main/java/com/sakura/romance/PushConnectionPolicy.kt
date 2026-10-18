package com.sakura.romance

internal class PushConnectionPolicy {
    private var nextRequestAt = 0L

    // vivo rejects repeated operations within 2 seconds; callers run on the main thread.
    fun reserveDelay(now: Long): Long {
        val scheduled = maxOf(now, nextRequestAt)
        nextRequestAt = scheduled + 2500L
        return scheduled - now
    }

    fun subscriptionSucceeded(code: Int) = code == 0 || code == 1
}
