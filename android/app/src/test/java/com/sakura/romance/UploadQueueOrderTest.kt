package com.sakura.romance

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadQueueOrderTest {
    private fun task(id: String, status: String, completedAt: Long = 0) =
        UploadTask(id, "content://photo/$id", "album", "$id.jpg", "image/jpeg", 100,
            status = status, completedAt = completedAt)

    @Test fun unfinishedTasksStayAheadOfCompletedTasksWithoutChangingTheirOrder() {
        val tasks = listOf(task("done", "已完成", 100), task("failed", "上传失败"),
            task("waiting", "等待上传"), task("paused", "已暂停"), task("active", "正在上传"))
        assertEquals(listOf("failed", "waiting", "paused", "active", "done"), displayUploadTasks(tasks).map { it.id })
    }

    @Test fun completionTimeDeterminesOrderEvenWhenEnqueueOrderDiffers() {
        val tasks = listOf(task("finished-last", "已完成", 300), task("finished-first", "已完成", 100),
            task("finished-middle", "已完成", 200))
        assertEquals(listOf("finished-first", "finished-middle", "finished-last"), displayUploadTasks(tasks).map { it.id })
        assertEquals("finished-last", tasks.first().id)
    }

    @Test fun legacyTasksWithoutCompletionTimeKeepTheirRelativeOrder() {
        val tasks = listOf(task("legacy-a", "已完成"), task("recent", "已完成", 100), task("legacy-b", "已完成"))
        assertEquals(listOf("legacy-a", "legacy-b", "recent"), displayUploadTasks(tasks).map { it.id })
    }
    @Test fun immediateUploadGoesBeforeInterruptedTaskAndPreservesTheRest() {
        val tasks = listOf(task("done", "已完成"), task("a", "正在上传"), task("b", "等待上传"), task("c", "等待上传"))
        assertEquals(listOf("c", "a", "b", "done"), prioritizeUploadTasks(tasks, "c", "a").map { it.id })
    }

    @Test fun latestPriorityWinsAndCompletedTasksCannotBePrioritized() {
        val tasks = listOf(task("a", "正在上传"), task("b", "等待上传"), task("c", "等待上传"), task("done", "已完成"))
        val first = prioritizeUploadTasks(tasks, "b", "a")
        assertEquals(listOf("c", "a", "b", "done"), prioritizeUploadTasks(first, "c", "a").map { it.id })
        assertEquals(tasks, prioritizeUploadTasks(tasks, "done", "a"))
    }

    @Test fun displayedProgressDoesNotChangeConfirmedResumePosition() {
        val item = task("a", "正在上传").copy(offset = 20, sentOffset = 55)
        assertEquals(20L, item.offset)
        assertEquals(55L, item.displayOffset)
        assertEquals(55, item.progressPercent)
        assertEquals(100L, item.copy(sentOffset = 150).displayOffset)
    }

    @Test fun queueFocusFollowsActiveThenPendingThenCompleted() {
        val tasks = listOf(task("done", "已完成"), task("failed", "上传失败"), task("a", "正在上传"))
        assertEquals("a", uploadFocusId(tasks, "a"))
        assertEquals("failed", uploadFocusId(tasks, null))
        assertEquals("done", uploadFocusId(listOf(tasks.first()), null))
        assertEquals(null, uploadFocusId(emptyList(), null))
    }
    @Test fun smallProgressAndPreparationAreNotReportedAsZeroPercent() {
        val item = task("a", "正在上传").copy(size = 2L * 1024 * 1024 * 1024, sentOffset = 65536)
        assertEquals("<0.1%", item.progressLabel)
        assertEquals("0.5%", item.copy(sentOffset = 10L * 1024 * 1024).progressLabel)
        org.junit.Assert.assertTrue(item.copy(status = "校验续传位置").preparing)
        org.junit.Assert.assertFalse(item.preparing)
    }
}
