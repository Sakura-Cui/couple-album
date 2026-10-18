package com.sakura.romance

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AdaptiveVideoFlowTest {
    private val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val launchRule = withSkippedSplash(rule)
    private lateinit var resultDirectory: File
    private fun open(rate: Int, delay: Int = 0, preview: Boolean = false): Pair<AppModel, Long> {
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        resultDirectory = rule.activity.filesDir
        val model = rule.activity.model
        device.executeShellCommand("pm grant ${rule.activity.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("pm grant ${rule.activity.packageName} android.permission.ACCESS_COARSE_LOCATION")
        device.executeShellCommand("appops set ${rule.activity.packageName} SCHEDULE_EXACT_ALARM allow")
        rule.runOnUiThread { model.vault.url = "https://10.0.2.2:18450" }
        val invite = runBlocking { model.api.obj("/qa/invite").getString("invite") }
        rule.runOnUiThread { model.bind("https://10.0.2.2:18450", invite, "多清晰度验收") }
        rule.waitUntil(20000) { model.ready && !model.busy && model.albums.isNotEmpty() }
        rule.runOnUiThread { model.openAlbum(model.albums.first()) }
        rule.waitUntil(20000) { !model.mediaLoading && model.media.isNotEmpty() }
        runBlocking { model.api.obj("/qa/stream?bytes_per_second=$rate", "POST") }
        runBlocking { model.api.obj("/qa/delay?seconds=$delay", "POST") }
        runBlocking { model.api.obj("/qa/preview?enabled=$preview", "POST") }
        val started = SystemClock.elapsedRealtime()
        rule.runOnUiThread { model.viewing = model.media.single() }
        return model to started
    }
    private fun frame(timeout: Long = 15000) {
        try {
            rule.waitUntil(timeout) { rule.onAllNodesWithTag("video-frame").fetchSemanticsNodes().isNotEmpty() && rule.onAllNodesWithTag("loading-video").fetchSemanticsNodes().isEmpty() }
        } catch (failure: Exception) {
            println(rule.onRoot(useUnmergedTree = true).printToString())
            val p = player()
            rule.runOnUiThread { println("QA player state=${p.playbackState} playing=${p.playWhenReady} position=${p.currentPosition} buffered=${p.totalBufferedDuration} uri=${p.currentMediaItem?.localConfiguration?.uri}") }
            throw failure
        }
        rule.onNodeWithText("视频加载失败", substring = true).assertDoesNotExist()
    }
    private fun player(): ExoPlayer {
        lateinit var result: ExoPlayer
        rule.runOnUiThread {
            fun find(view: android.view.View): PlayerView? {
                if (view is PlayerView) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            result = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOf { find(it)?.player as? ExoPlayer }
        }
        return result
    }
    private fun select(id: String) {
        rule.onNodeWithTag("video-quality").performClick()
        rule.waitUntil(3000) { rule.onAllNodesWithTag("video-quality-$id").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("video-quality-$id").performClick()
    }
    private fun position(player: Player): Long {
        var result = 0L
        rule.runOnUiThread { result = player.currentPosition }
        return result
    }
    @Test fun switchesAllQualitiesAtCurrentPositionAndPlaysItem55ToEnd() {
        val (model, started) = open(500000)
        frame()
        val startup = SystemClock.elapsedRealtime() - started
        assertTrue("4Mbps startup took $startup ms", startup < 12000)
        val state = runBlocking { model.api.obj("/qa/state") }
        val paths = state.getJSONArray("paths")
        assertFalse((0 until paths.length()).any { paths.getString(it).endsWith("/content") })
        assertTrue(state.getLong("sent") < model.media.single().size)
        var p = player()
        for (quality in listOf("720", "1080", "480")) {
            val before = position(p)
            select(quality)
            rule.waitUntil(12000) {
                var matched = false
                rule.runOnUiThread {
                    val format = p.videoFormat
                    matched = p.playbackState == Player.STATE_READY && format != null && minOf(format.width, format.height) == quality.toInt()
                }
                matched
            }
            assertTrue("Quality switch reset the position", position(p) >= before - 400)
        }
        select("auto")
        frame()
        val beforeOriginal = position(p)
        select("original")
        rule.waitUntil(5000) { rule.onAllNodesWithText("正在缓存原片", substring = true).fetchSemanticsNodes().isNotEmpty() }
        select("auto")
        frame()
        p = player()
        assertTrue("Cancelling original preparation lost the position", position(p) >= beforeOriginal - 400)
        runBlocking { model.api.obj("/qa/stream?bytes_per_second=0", "POST") }
        val beforeCompleteOriginal = position(p)
        select("original")
        frame(45000)
        p = player()
        rule.runOnUiThread { assertTrue(p.currentMediaItem!!.localConfiguration!!.uri.toString().endsWith("/content")) }
        assertTrue("Switching to the complete original lost the position", position(p) >= beforeCompleteOriginal - 400)
        select("720")
        frame()
        p = player()
        runBlocking { model.api.obj("/qa/stream?bytes_per_second=500000", "POST") }
        var ended = false
        rule.waitUntil(150000) { rule.runOnUiThread { ended = p.playbackState == Player.STATE_ENDED }; ended }
        rule.runOnUiThread { p.seekTo(p.duration * 3 / 4); p.play() }
        rule.waitUntil(10000) { var ready = false; rule.runOnUiThread { ready = p.playbackState == Player.STATE_READY }; ready }
        File(resultDirectory, "adaptive-full-result.json").writeText(JSONObject().put("startup_ms",startup).put("full_playback",true).put("seek",true).put("all_quality_switches",true).toString())
        rule.onNodeWithContentDescription("关闭").performClick()
    }
    @Test fun existingCopyStartsWhileQualitiesPrepareThenSwitchesAtCurrentPosition() {
        val (model, started) = open(500000, 3600)
        try {
            frame()
            assertTrue("Preparing qualities blocked the existing copy", SystemClock.elapsedRealtime() - started < 12000)
            var p = player()
            rule.runOnUiThread { assertTrue(p.currentMediaItem!!.localConfiguration!!.uri.toString().endsWith("/playback/content")) }
            rule.waitUntil(10000) { position(p) >= 3000 }
            runBlocking { model.api.obj("/qa/delay", "POST") }
            rule.waitUntil(15000) {
                rule.mainClock.advanceTimeBy(2000)
                rule.onAllNodesWithTag("video-quality").fetchSemanticsNodes().isNotEmpty()
            }
            frame()
            p = player()
            rule.runOnUiThread { assertTrue(p.currentMediaItem!!.localConfiguration!!.uri.toString().endsWith("master.m3u8")) }
            assertTrue("Finishing background preparation reset the playback position", position(p) >= 3000)
            rule.onNodeWithContentDescription("关闭").performClick()
        } finally { runBlocking { model.api.obj("/qa/delay", "POST") } }
    }
    @Test fun autoStartsAndKeepsPlayingOn1760KbpsWithoutDownloadingOriginal() {
        val (model, started) = open(220000)
        frame()
        val startup = SystemClock.elapsedRealtime() - started
        val p = player()
        rule.waitUntil(30000) { position(p) >= 12000 }
        rule.runOnUiThread {
            assertEquals(Player.STATE_READY,p.playbackState)
            assertEquals(480,minOf(p.videoFormat!!.width,p.videoFormat!!.height))
        }
        val paths=runBlocking { model.api.obj("/qa/state").getJSONArray("paths") }
        assertFalse((0 until paths.length()).any { paths.getString(it).endsWith("/content") })
        File(resultDirectory,"adaptive-weak-result.json").writeText(JSONObject().put("startup_ms",startup).put("playing_position_ms",position(p)).put("quality",480).put("requests",paths.length()).toString())
        rule.onNodeWithContentDescription("关闭").performClick()
    }
    @Test fun startsFromFirstSegmentsAndReloadsGrowingPlaylistBeforeHigherQualitiesFinish() {
        val (model, started) = open(500000, preview = true)
        try {
            frame()
            val startup = SystemClock.elapsedRealtime() - started
            var p = player()
            rule.runOnUiThread { assertTrue(p.currentMediaItem!!.localConfiguration!!.uri.toString().contains("preview=1")) }
            assertEquals("processing",runBlocking { model.api.obj("/api/media/${model.media.single().id}/playback/adaptive").getString("status") })
            rule.waitUntil(10000) { position(p) >= 3000 }
            runBlocking { model.api.obj("/qa/preview?enabled=true&segments=6", "POST") }
            rule.waitUntil(25000) { position(p) >= 12000 }
            assertTrue(runBlocking { model.api.obj("/qa/state").getInt("playlist_requests") } >= 2)
            val before = position(p)
            runBlocking { model.api.obj("/qa/preview", "POST") }
            rule.waitUntil(15000) {
                rule.mainClock.advanceTimeBy(2000)
                var final = false
                p = player()
                rule.runOnUiThread { final = p.currentMediaItem?.localConfiguration?.uri.toString().endsWith("master.m3u8") }
                final
            }
            frame()
            assertTrue("Publishing higher qualities reset position",position(p) >= before - 400)
            select("720")
            frame()
            rule.waitUntil(12000) {
                var matched = false
                rule.runOnUiThread { matched = p.videoFormat?.let { minOf(it.width,it.height)==720 } == true }
                matched
            }
            File(resultDirectory,"progressive-result.json").writeText(JSONObject().put("startup_ms",startup).put("playlist_reloaded",true).put("background_quality_switch",true).put("position_ms",position(p)).toString())
            rule.onNodeWithContentDescription("关闭").performClick()
        } finally { runBlocking { model.api.obj("/qa/preview", "POST") } }
    }

    @Test fun backgroundClosesViewerAndReopeningUsesFinishedQualities() {
        val (model, _) = open(500000, preview = true)
        try {
            frame()
            rule.runOnUiThread { model.background() }
            rule.waitUntil(5000) { rule.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
            assertNull(model.viewing)
            runBlocking { model.api.obj("/qa/preview", "POST") }
            rule.runOnUiThread { model.resume() }
            rule.waitUntil(20000) { model.ready && !model.busy && model.albums.isNotEmpty() }
            rule.runOnUiThread { model.openAlbum(model.albums.first()) }
            rule.waitUntil(20000) { !model.mediaLoading && model.media.isNotEmpty() }
            rule.runOnUiThread { model.viewing = model.media.single() }
            frame()
            val p = player()
            rule.runOnUiThread { assertTrue(p.currentMediaItem!!.localConfiguration!!.uri.toString().endsWith("master.m3u8")) }
            select("720")
            frame()
            rule.waitUntil(12000) {
                var matched = false
                rule.runOnUiThread { matched = p.videoFormat?.let { minOf(it.width,it.height)==720 } == true }
                matched
            }
            rule.onNodeWithContentDescription("关闭").performClick()
        } finally { runBlocking { model.api.obj("/qa/preview", "POST") } }
    }

}
