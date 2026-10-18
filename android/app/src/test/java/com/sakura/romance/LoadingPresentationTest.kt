package com.sakura.romance

import org.junit.Assert.*
import org.junit.Test

class LoadingPresentationTest {
    private fun task(id: String, size: Long, sent: Long, status: String = "正在上传") =
        UploadTask(id, "content://test/$id", "album", id, "image/png", size, offset = sent, status = status)

    @Test fun successfulLoadWaitsForAnimationButCachedContentDoesNot() {
        assertFalse(LoadingPresentation(false).visible)
        val state = LoadingPresentation(true)
        state.update(false, false)
        assertTrue(state.visible)
        state.finish(state.operation)
        assertFalse(state.visible)
    }

    @Test fun failureDismissesImmediatelyAndRetryRejectsOldCompletion() {
        val state = LoadingPresentation(true)
        val previous = state.operation
        state.update(false, true)
        assertFalse(state.visible)
        state.update(true, false)
        assertTrue(state.visible)
        state.finish(previous)
        assertTrue(state.visible)
        state.finish(state.operation)
        assertTrue(state.visible)
        state.update(false, false)
        state.finish(state.operation)
        assertFalse(state.visible)
    }

    @Test fun newRequestInterruptsSuccessWithoutKeepingItsCompletion() {
        val state = LoadingPresentation(true)
        state.update(false, false)
        val previous = state.operation
        state.update(true, false)
        state.update(false, false)
        state.finish(previous)
        assertTrue(state.visible)
        state.finish(state.operation)
        assertFalse(state.visible)
    }

    @Test fun retryRestartsEvenIfTransportStillReportsBufferingAfterFailure() {
        val state = LoadingPresentation(true)
        val previous = state.operation
        state.update(true, true)
        assertFalse(state.visible)
        state.update(true, false)
        assertTrue(state.visible)
        assertTrue(state.operation > previous)
        state.update(false, false)
        state.finish(previous)
        assertTrue(state.visible)
        state.finish(state.operation)
        assertFalse(state.visible)
    }

    @Test fun queueUsesBytesRatherThanFileCounts() {
        val progress = queueLoadingProgress(listOf(task("small", 100, 0, "已完成"), task("large", 900, 400)))
        assertEquals(.5f, progress.progress, .0001f)
        assertFalse(progress.completed)
    }

    @Test fun allBytesSentStillWaitsForServerSuccess() {
        for (status in listOf("正在保存", "上传失败", "已暂停")) {
            val progress = queueLoadingProgress(listOf(task("one", 100, 100, status)))
            assertEquals(1f, progress.progress, 0f)
            assertFalse(progress.completed)
        }
        assertTrue(queueLoadingProgress(listOf(task("one", 100, 100, "已完成"))).completed)
    }

    @Test fun queueMutationRecalculatesAndEmptyQueueIsNeverSuccessful() {
        val done = task("done", 100, 100, "已完成")
        assertEquals(.25f, queueLoadingProgress(listOf(done, task("new", 300, 0))).progress, 0f)
        assertEquals(1f, queueLoadingProgress(listOf(done)).progress, 0f)
        assertEquals(QueueLoadingProgress(0f, false), queueLoadingProgress(emptyList()))
        assertEquals(QueueLoadingProgress(0f, false), queueLoadingProgress(listOf(task("zero", 0, 0))))
    }
}
