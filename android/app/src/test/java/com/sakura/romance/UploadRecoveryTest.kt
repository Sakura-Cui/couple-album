package com.sakura.romance

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UploadRecoveryTest {
    @Test fun retryReportsItsAttemptBeforeWaiting() = runBlocking {
        val events = mutableListOf<String>()
        var attempts = 0
        val result = retryUploadRequest(wait = { events += "wait:$it" }, onRetry = { events += "retry:$it" }) {
            if (attempts++ == 0) throw IOException("offline")
            42L
        }
        assertEquals(42L, result)
        assertEquals(listOf("retry:1", "wait:1000"), events)
    }

    @Test fun permanentErrorNeverInvokesRetry() = runBlocking {
        try {
            retryUploadRequest(onRetry = { fail("must not retry authentication") }) { throw ApiError(423, "locked") }
            fail("must throw")
        } catch (_: ApiError) { }
    }

    @Test fun onlyExplicitlyUnrecoverableUploadsAreRebuilt() {
        assertTrue(uploadNeedsRestart("cancelled", ""))
        assertTrue(uploadNeedsRestart("pending", "文件校验失败，请取消任务后重新上传"))
        assertFalse(uploadNeedsRestart("pending", "保存失败，请重试；已上传文件保留"))
        assertFalse(uploadNeedsRestart("complete", "文件校验失败，请取消任务后重新上传"))
        assertFalse(uploadNeedsRestart("saving", ""))
    }

    @Test fun reconnectAcceptsOnlyTheCurrentChunkOrItsConfirmedEnd() {
        assertFalse(uploadChunkAlreadyReceived(100, 100, 50))
        assertTrue(uploadChunkAlreadyReceived(150, 100, 50))
        assertThrows(IllegalStateException::class.java) { uploadChunkAlreadyReceived(120, 100, 50) }
        assertThrows(IllegalStateException::class.java) { uploadChunkAlreadyReceived(200, 100, 50) }
    }
}
