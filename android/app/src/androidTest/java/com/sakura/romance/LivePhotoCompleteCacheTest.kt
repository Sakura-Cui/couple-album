package com.sakura.romance

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cold starts use a genuinely chunk-throttled HTTPS stream of synthetic quadrants only. */
@RunWith(AndroidJUnit4::class)
class LivePhotoCompleteCacheTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var model: AppModel
    private var size = 0L
    private var hd = false
    private val active = mutableStateOf(true)
    private fun setup(options: String = "", mirrored: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        model = AppModel(context.applicationContext as Application)
        model.vault.url = "https://10.0.2.2:18448"; model.vault.token = "qa-live-only"
        val factory = StreamCache.factory(context, model.vault, OkHttpDataSource.Factory(model.api.client))
        for (url in listOf(model.api.mediaUrl("2".repeat(32)), model.api.playbackUrl("2".repeat(32))+"?live=true")) {
            val spec = DataSpec.Builder().setUri(url).build()
            factory.cache!!.removeResource(factory.cacheKeyFactory.buildCacheKey(spec))
        }
        hd = mirrored
        val mode = runBlocking { model.api.obj("/qa/live/mode?orientation=${if (mirrored) "transverse" else "legacy"}$options", "POST") }
        size = mode.getLong(if (mirrored) "hd_size" else "size")
        val item = Media("1".repeat(32), "a".repeat(32), "synthetic.JPG", "image/jpeg", 1, 1, true, motionId = "2".repeat(32))
        rule.setContent { Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().testTag("live-static-backdrop")) {
                val width=size.width; val height=width*3/4; val top=(size.height-height)/2
                val colors=if (hd) listOf(androidx.compose.ui.graphics.Color.Yellow, androidx.compose.ui.graphics.Color.Green, androidx.compose.ui.graphics.Color.Blue, androidx.compose.ui.graphics.Color.Red)
                    else listOf(androidx.compose.ui.graphics.Color.Red, androidx.compose.ui.graphics.Color.Green, androidx.compose.ui.graphics.Color.Blue, androidx.compose.ui.graphics.Color.Yellow)
                colors.forEachIndexed { index,color -> drawRect(color, androidx.compose.ui.geometry.Offset((index%2)*width/2, top+(index/2)*height/2), androidx.compose.ui.geometry.Size(width/2,height/2)) }
            }
            LivePhotoOverlay(model, item, active.value)
        } }
    }
    private fun waitTag(tag: String, timeout: Long = 30000) = rule.waitUntil(timeout) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(text: String, timeout: Long = 30000) = rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun stats() = runBlocking { model.api.obj("/qa/live/stats") }
    private fun complete(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = StreamCache.factory(context, model.vault, OkHttpDataSource.Factory(model.api.client))
        val url = if (hd) model.api.playbackUrl("2".repeat(32))+"?live=true" else model.api.mediaUrl("2".repeat(32))
        val key = factory.cacheKeyFactory.buildCacheKey(DataSpec.Builder().setUri(url).build())
        return factory.cache!!.isCached(key, 0, size)
    }
    private fun player(): Player {
        lateinit var result: Player
        onView(isAssignableFrom(PlayerView::class.java)).check { view, missing ->
            if (missing != null) throw missing
            result = (view as PlayerView).player!!
        }
        return result
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun completeCacheHasBoundedOfflineLengthAndEndOfInput() {
        setup()
        waitText("实况 · 按住播放")
        assertTrue(complete())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = StreamCache.factory(context, model.vault, OkHttpDataSource.Factory(model.api.client))
            .setUpstreamDataSourceFactory(null)
        val source = factory.createDataSource()
        try {
            assertEquals(size, source.open(DataSpec.Builder().setUri(model.api.mediaUrl("2".repeat(32))).build()))
            var total = 0L
            val buffer = ByteArray(4096)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == -1) break
                total += count
            }
            assertEquals(size, total)
        } finally { source.close() }
    }
    @Test fun coldOriginalHoldsStaticUntilEveryByteAndFrameAreReadyThenReusesCache() {
        setup("&throttle=0.12")
        waitTag("live-photo-loading")
        rule.onNodeWithTag("live-photo-control").performTouchInput { down(center) }
        rule.waitUntil(15000) { stats().getInt("bytes_sent")>1024 }
        assertFalse(complete())
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        rule.onNodeWithTag("live-photo-loading").assertExists()
        screenshot("live-complete-loading.png")
        waitTag("live-photo-frame")
        assertTrue(complete())
        rule.onNodeWithTag("live-photo-loading").assertDoesNotExist()
        screenshot("live-complete-playing.png")
        val originalReads = stats().getInt("original_reads")
        rule.onNodeWithTag("live-photo-control").performTouchInput { up(); down(center) }
        waitTag("live-photo-frame")
        assertEquals(originalReads, stats().getInt("original_reads"))
        assertEquals(0, stats().getInt("legacy_reads"))
        rule.onNodeWithTag("live-photo-control").performTouchInput { up() }
    }
    @Test fun releaseDuringHdPreparationNeverStartsAfterDownloadCompletes() {
        setup("&throttle=0.12", true)
        waitTag("live-photo-loading")
        rule.onNodeWithTag("live-photo-control").performTouchInput { down(center) }
        Thread.sleep(500)
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        rule.onNodeWithTag("live-photo-control").performTouchInput { up() }
        waitText("实况 · 按住播放")
        assertTrue(complete())
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        val paused = player()
        rule.runOnIdle { assertFalse(paused.playWhenReady) }
        val reads = stats().getInt("hd_reads")
        rule.onNodeWithTag("live-photo-control").performTouchInput { down(center) }
        waitTag("live-photo-frame")
        assertEquals(reads,stats().getInt("hd_reads"))
        assertEquals(1,stats().getInt("hd_requests"))
        rule.onNodeWithTag("live-photo-control").performTouchInput { up() }
    }
    @Test fun deactivationCancelsStreamsAndForegroundPreloadsWithoutStarting() {
        setup("&throttle=0.12")
        rule.waitUntil(15000) { stats().getInt("bytes_sent")>1024 }
        rule.runOnIdle { active.value=false }
        rule.waitUntil(5000) { stats().getInt("streams_active")==0 }
        rule.onNodeWithTag("live-photo-control").assertDoesNotExist()
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        rule.runOnIdle { active.value=true }
        waitText("实况 · 按住播放")
        assertTrue(complete())
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        val paused = player()
        rule.runOnIdle { assertFalse(paused.playWhenReady) }
    }
    @Test fun truncatedResponseShowsRetryAndRetryCompletesMissingCacheRange() {
        setup("&truncated=true")
        waitText("点击重试")
        assertFalse(complete())
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        runBlocking { model.api.obj("/qa/live/mode", "POST") }
        rule.onNodeWithTag("live-photo-control").performClick()
        waitText("实况 · 按住播放")
        assertTrue(complete())
    }
    @Test fun cachedClipStillRequiresCurrentPermissionOnHold() {
        setup()
        waitText("实况 · 按住播放")
        assertTrue(complete())
        runBlocking { model.api.obj("/qa/live/behavior?denied=true", "POST") }
        rule.onNodeWithTag("live-photo-control").performTouchInput { down(center) }
        waitText("实况访问失败")
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
    }
    @Test fun unknownContentLengthFailsWithoutPretendingDownloadCompleted() {
        setup("&unknown_length=true")
        waitText("无法确认实况大小")
        assertFalse(complete())
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
    }
    @Test fun permissionTimeoutOffersRetryAndNeverPlaysLate() {
        setup()
        waitText("实况 · 按住播放")
        runBlocking { model.api.obj("/qa/live/behavior?auth_delay=35", "POST") }
        rule.onNodeWithTag("live-photo-control").performTouchInput { down(center) }
        waitText("实况访问超时",40000)
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        Thread.sleep(5500)
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
    }
    @Test fun pendingHdPreparationHasBoundedTimeout() {
        setup("&stuck=true",true)
        waitText("正在准备动态画面")
        try { waitText("实况准备超时",130000) }
        finally {
            java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "live-pending-state.txt")
                .writeText(rule.onRoot(useUnmergedTree = true).printToString())
        }
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        assertFalse(complete())
        assertEquals(1,stats().getInt("hd_requests"))
    }
}
