package com.sakura.romance

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed as nativeIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class OriginalVideoFlowTest {
    private val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val launchRule = withSkippedSplash(rule)

    @Test fun originalWaitsForCompleteCacheThenPlaysAndSeeksWithoutNetwork() {
        val model = rule.activity.model
        rule.mainClock.advanceTimeBy(5100)
        rule.runOnUiThread { model.vault.url = "https://10.0.2.2:18449" }
        runBlocking { model.api.obj("/qa/reset", "POST") }
        val invite = runBlocking { model.api.obj("/qa/invite").getString("invite") }
        rule.runOnUiThread { model.bind("https://10.0.2.2:18449", invite, "原片完整缓存验收") }
        rule.waitUntil(20000) { model.ready && !model.busy && model.albums.isNotEmpty() }
        rule.runOnUiThread { model.openAlbum(model.albums.first()) }
        rule.waitUntil(20000) { !model.mediaLoading && model.media.isNotEmpty() }
        val item = model.media.single { it.video }
        val factory = StreamCache.factory(rule.activity, model.vault, OkHttpDataSource.Factory(model.api.client))
        val spec = DataSpec.Builder().setUri(model.api.mediaUrl(item.id)).setLength(item.size).build()
        val cache = factory.cache!!
        val key = factory.cacheKeyFactory.buildCacheKey(spec)
        cache.removeResource(key)
        rule.runOnUiThread { model.viewing = item }
        rule.waitUntil(15000) {
            val progress = rule.onAllNodesWithTag("loading-video").fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
            progress != null && progress.current > 0 && progress.current < .99f
        }
        rule.onNodeWithText("正在缓存原片", substring = true).assertIsDisplayed()
        rule.waitUntil(600000) { rule.onAllNodesWithTag("video-frame").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("Original started before its full encrypted cache was ready", cache.isCached(key, 0, item.size))
        rule.waitUntil(15000) { rule.onAllNodesWithTag("loading-video").fetchSemanticsNodes().isEmpty() }
        val downloaded = runBlocking { model.api.obj("/qa/state").getLong("requests") }
        lateinit var player: Player
        onView(allOf(isAssignableFrom(PlayerView::class.java), nativeIsDisplayed())).check { view, missing ->
            if (missing != null) throw missing
            player = (view as PlayerView).player!!
        }
        var ended = false
        rule.waitUntil(150000) {
            rule.runOnUiThread { ended = player.playbackState == Player.STATE_ENDED }
            ended
        }
        rule.runOnUiThread { player.seekTo(player.duration * 3 / 4); player.play() }
        rule.waitUntil(5000) {
            var ready = false
            rule.runOnUiThread { ready = player.playbackState == Player.STATE_READY }
            ready
        }
        assertEquals("Cached playback and seek must not download video again", downloaded,
            runBlocking { model.api.obj("/qa/state").getLong("requests") })
        rule.onNodeWithContentDescription("关闭").performClick()
        rule.waitUntil(5000) { model.viewing == null }
    }
    @Test fun closingAndBackgroundingCancelPreparationAndAllowRetry() {
        val model = rule.activity.model
        rule.mainClock.advanceTimeBy(5100)
        rule.runOnUiThread { model.vault.url = "https://10.0.2.2:18449" }
        runBlocking { model.api.obj("/qa/reset", "POST") }
        val invite = runBlocking { model.api.obj("/qa/invite").getString("invite") }
        rule.runOnUiThread { model.bind("https://10.0.2.2:18449", invite, "原片取消回归") }
        rule.waitUntil(20000) { model.ready && !model.busy && model.albums.isNotEmpty() }
        rule.runOnUiThread { model.openAlbum(model.albums.first()) }
        rule.waitUntil(20000) { !model.mediaLoading && model.media.isNotEmpty() }
        val item = model.media.single { it.video }
        runBlocking { model.api.obj("/qa/stream?bytes_per_second=32768", "POST") }
        try {
            repeat(4) { turn ->
                val before = runBlocking { model.api.obj("/qa/state").getLong("sent") }
                rule.runOnUiThread { model.viewing = item }
                rule.waitUntil(15000) { runBlocking { model.api.obj("/qa/state").getLong("sent") } >= before + 65536 }
                rule.onNodeWithTag("video-frame").assertDoesNotExist()
                rule.onNodeWithText("保留原画质", substring = true).assertDoesNotExist()
                when (turn) {
                    0 -> {
                        rule.onNodeWithTag("media-pager").performTouchInput { swipeLeft() }
                        rule.waitUntil(5000) { model.viewing?.video == false }
                    }
                    1 -> rule.onNodeWithContentDescription("关闭").performClick()
                    2 -> rule.runOnUiThread { model.background() }
                    else -> {
                        val nextInvite = runBlocking { model.api.obj("/qa/invite").getString("invite") }
                        rule.runOnUiThread { model.bind("https://10.0.2.2:18449", nextInvite, "原片切换绑定回归") }
                    }
                }
                rule.waitForIdle()
                if (turn != 0) rule.waitUntil(5000) { rule.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
                Thread.sleep(1500)
                val stopped = runBlocking { model.api.obj("/qa/state").getLong("sent") }
                Thread.sleep(1000)
                assertEquals("Preparation kept downloading after exit action $turn", stopped,
                    runBlocking { model.api.obj("/qa/state").getLong("sent") })
                if (turn == 0) rule.onNodeWithContentDescription("关闭").performClick()
                rule.waitUntil(5000) { rule.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
                if (turn >= 2) {
                    if (turn == 2) rule.runOnUiThread { model.resume() }
                    rule.waitUntil(20000) { model.ready && !model.busy && model.albums.isNotEmpty() }
                    rule.runOnUiThread { model.openAlbum(model.albums.first()) }
                    rule.waitUntil(20000) { !model.mediaLoading && model.media.isNotEmpty() }
                }
            }
            runBlocking { model.api.obj("/qa/stream", "POST") }
            rule.runOnUiThread { model.viewing = item }
            rule.waitUntil(20000) { rule.onAllNodesWithTag("video-frame").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("视频加载失败", substring = true).assertDoesNotExist()
            rule.onNodeWithContentDescription("关闭").performClick()
        } finally { runBlocking { model.api.obj("/qa/stream", "POST") } }
    }

}
