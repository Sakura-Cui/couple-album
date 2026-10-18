package com.sakura.romance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed as nativeIsDisplayed
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaCompatibilityFlowTest {
    private val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val launchRule = withSkippedSplash(rule)
    @Test fun failedCopyRetryRendersWithoutASecondLoadingGap() {
        val model = rule.activity.model
        rule.mainClock.advanceTimeBy(5100)
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("pm grant ${rule.activity.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("pm grant ${rule.activity.packageName} android.permission.ACCESS_COARSE_LOCATION")
        device.executeShellCommand("appops set ${rule.activity.packageName} SCHEDULE_EXACT_ALARM allow")
        rule.runOnUiThread { model.vault.url = "https://10.0.2.2:18448" }
        val invite = runBlocking { model.api.obj("/qa/playback/invite").getString("invite") }
        rule.runOnUiThread { model.bind("https://10.0.2.2:18448", invite, "视频兼容回归") }
        rule.waitUntil(20000) { model.ready && !model.busy && model.albums.isNotEmpty() }
        rule.runOnUiThread { model.openAlbum(model.albums.single { it.id == "a".repeat(32) }) }
        rule.waitUntil(20000) { !model.mediaLoading && model.media.any { it.id == "5".repeat(32) } }
        runBlocking { model.api.obj("/qa/playback/reset", "POST") }
        rule.runOnUiThread { model.viewing = model.media.single { it.id == "5".repeat(32) } }
        rule.waitUntil(10000) { rule.onAllNodesWithText("视频暂时准备失败，请稍后重试。").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("重试").performClick()
        var sawLoading = false
        var gapsBeforeFrame = 0
        rule.waitUntil(25000) {
            val tags = rule.onAllNodes(hasTestTag("video-frame") or hasTestTag("loading-video"))
                .fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.TestTag) }
            val frame = "video-frame" in tags
            val loading = "loading-video" in tags
            if (loading) sawLoading = true
            if (sawLoading && !loading && !frame) gapsBeforeFrame++
            frame && !loading
        }
        assertTrue("Retry must show the generation/loading presentation", sawLoading)
        assertEquals("Loading disappeared between copy resolution and the first frame", 0, gapsBeforeFrame)
        rule.onNodeWithTag("video-poster").assertDoesNotExist()
        rule.onNodeWithText("视频暂时准备失败，请稍后重试。").assertDoesNotExist()
        rule.onNodeWithText("兼容模式播放").assertDoesNotExist()
        lateinit var retained: androidx.media3.common.Player
        onView(allOf(isAssignableFrom(PlayerView::class.java), nativeIsDisplayed())).check { view, missing ->
            if (missing != null) throw missing
            retained = (view as PlayerView).player!!
        }
        rule.onNodeWithTag("media-pager").performTouchInput { swipeLeft() }
        rule.waitUntil(10000) { model.viewing?.id == "1".repeat(32) }
        rule.onNodeWithTag("media-pager").performTouchInput { swipeRight() }
        rule.waitUntil(10000) { model.viewing?.id == "5".repeat(32) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("loading-video").assertDoesNotExist()
        rule.onNodeWithTag("video-poster").assertIsNotDisplayed()
        onView(allOf(isAssignableFrom(PlayerView::class.java), nativeIsDisplayed())).check { view, missing ->
            if (missing != null) throw missing
            assertSame("Returning to a loaded video recreated its player", retained, (view as PlayerView).player)
        }
        // A slow, uncached seek must show buffering without replaying the initial animation.
        rule.runOnIdle { retained.pause() }
        val context = rule.activity
        val source = StreamCache.factory(context, model.vault,
            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(model.api.client)).createDataSource()
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri(model.api.playbackUrl("5".repeat(32))).build()
        source.cache.removeResource(source.cacheKeyFactory.buildCacheKey(spec))
        runBlocking { model.api.obj("/qa/playback/delay?seconds=1.5", "POST") }
        try {
            rule.runOnIdle { retained.seekTo(retained.duration * 3 / 4) }
            rule.waitUntil(10000) { rule.onAllNodesWithTag("video-buffering").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("loading-video").assertDoesNotExist()
            rule.waitUntil(15000) {
                var ready = false
                rule.runOnIdle { ready = retained.playbackState == androidx.media3.common.Player.STATE_READY }
                ready
            }
            rule.onNodeWithTag("loading-video").assertDoesNotExist()
            rule.runOnIdle { retained.play() }
        } finally { runBlocking { model.api.obj("/qa/playback/delay", "POST") } }
        rule.onNodeWithContentDescription("关闭").performClick()
        rule.waitUntil(5000) { model.viewing == null }
        rule.onNodeWithTag("video-frame").assertDoesNotExist()
    }
    @Test fun startupBufferProgressUsesRealBufferedMedia() {
        val model = rule.activity.model
        rule.mainClock.advanceTimeBy(5100)
        rule.runOnUiThread { model.vault.url = "https://10.0.2.2:18448" }
        val invite = runBlocking { model.api.obj("/qa/playback/invite").getString("invite") }
        rule.runOnUiThread { model.bind("https://10.0.2.2:18448", invite, "缓冲进度回归") }
        rule.waitUntil(20000) { model.ready && !model.busy && model.albums.isNotEmpty() }
        rule.runOnUiThread { model.openAlbum(model.albums.single { it.id == "a".repeat(32) }) }
        rule.waitUntil(20000) { !model.mediaLoading && model.media.any { it.id == "5".repeat(32) } }
        val factory = StreamCache.factory(rule.activity, model.vault, androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(model.api.client))
        val cached = factory.createDataSource()
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri(model.api.playbackUrl("5".repeat(32))).build()
        cached.cache.removeResource(cached.cacheKeyFactory.buildCacheKey(spec))
        runBlocking { model.api.obj("/api/media/${"5".repeat(32)}/playback/retry", "POST") }
        runBlocking { model.api.obj("/qa/playback/stream?seconds=.12", "POST") }
        try {
            rule.runOnUiThread { model.viewing = model.media.single { it.id == "5".repeat(32) } }
            rule.waitUntil(60000) {
                val node = rule.onAllNodesWithTag("loading-video").fetchSemanticsNodes().singleOrNull()
                val progress = node?.config?.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
                progress != null && progress != androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate && progress.current > .02f && progress.current < .98f
            }
            rule.onNodeWithTag("loading-video").assertIsDisplayed()
            rule.waitUntil(60000) { rule.onAllNodesWithTag("video-frame").fetchSemanticsNodes().isNotEmpty() && rule.onAllNodesWithTag("loading-video").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("视频加载失败", substring = true).assertDoesNotExist()
        } finally { runBlocking { model.api.obj("/qa/playback/stream", "POST") } }
    }

}
