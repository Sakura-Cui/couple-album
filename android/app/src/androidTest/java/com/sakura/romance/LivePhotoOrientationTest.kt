package com.sakura.romance

import android.app.Application
import android.graphics.Color
import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** All fixtures are synthetic colored quadrants on isolated localhost HTTPS. */
@RunWith(Parameterized::class)
class LivePhotoOrientationTest(private val orientation: String, private val mirrored: Boolean, private val corners: String) {
    @get:Rule val rule = createComposeRule()
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases() = listOf(
            arrayOf<Any>("identity", false, "RGBY"), arrayOf<Any>("cw90", false, "BRYG"),
            arrayOf<Any>("180", false, "YBGR"), arrayOf<Any>("cw270", false, "GYRB"),
            arrayOf<Any>("mirror_x", true, "GRYB"), arrayOf<Any>("mirror_y", true, "BYRG"),
            arrayOf<Any>("transpose", true, "RBGY"), arrayOf<Any>("transverse", true, "YGBR"))
    }
    @Test fun correctDirectionAndSourceWithoutDoubleRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = AppModel(context.applicationContext as Application)
        model.vault.url = "https://10.0.2.2:18448"
        model.vault.token = "qa-live-only"
        val motion = "2".repeat(32)
        val factory = StreamCache.factory(context, model.vault, androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(model.api.client))
        val cache = factory.createDataSource().cache
        for (url in listOf(model.api.mediaUrl(motion), model.api.playbackUrl(motion)+"?live=true")) {
            val spec = androidx.media3.datasource.DataSpec.Builder().setUri(url).build()
            cache.removeResource(factory.cacheKeyFactory.buildCacheKey(spec))
        }
        runBlocking { model.api.obj("/qa/live/mode?orientation=$orientation", "POST") }
        // Reproduce the grouped album's already-known motion id before /live completes.
        val item = Media("1".repeat(32), "a".repeat(32), "capture.JPG", "image/jpeg", 1, 1, true, motionId = motion)
        rule.setContent { Box(Modifier.fillMaxSize()) { LivePhotoOverlay(model, item, true) } }
        Thread.sleep(150)
        val early = runBlocking { model.api.obj("/qa/live/stats") }
        assertEquals(0, early.getInt("original_reads"))
        rule.waitUntil(30000) { rule.onAllNodesWithTag("live-photo-preload").fetchSemanticsNodes().isNotEmpty() }
        var player: Player? = null
        var surface: android.view.View? = null
        onView(isAssignableFrom(PlayerView::class.java)).check { view, missing ->
            if (missing != null) throw missing
            player = (view as PlayerView).player
            surface = view.videoSurfaceView
        }
        rule.waitUntil(30000) {
            var ready = false
            rule.runOnIdle { ready = player!!.playbackState == Player.STATE_READY }
            ready
        }
        rule.runOnIdle {
            val uri = player!!.currentMediaItem!!.localConfiguration!!.uri.toString()
            assertEquals(if (mirrored) model.api.playbackUrl(motion)+"?live=true" else model.api.mediaUrl(motion), uri)
        }
        rule.onNodeWithTag("live-photo-control").performTouchInput { down(center) }
        rule.waitUntil(15000) { rule.onAllNodesWithTag("live-photo-frame").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(150)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val rect = Rect()
        rule.runOnIdle { assertTrue(surface!!.getGlobalVisibleRect(rect)) }
        val palette = mapOf('R' to Color.RED, 'G' to Color.GREEN, 'B' to Color.BLUE, 'Y' to Color.YELLOW)
        for ((index, point) in listOf(.25f to .25f, .75f to .25f, .25f to .75f, .75f to .75f).withIndex()) {
            val actual = screenshot.getPixel(rect.left+(rect.width()*point.first).toInt(), rect.top+(rect.height()*point.second).toInt())
            val expected = palette.getValue(corners[index])
            for (channel in listOf<(Int)->Int>(Color::red, Color::green, Color::blue))
                assertTrue("$orientation corner $index expected=$expected actual=$actual", kotlin.math.abs(channel(expected)-channel(actual))<25)
        }
        screenshot.recycle()
        val stats = runBlocking { model.api.obj("/qa/live/stats") }
        assertEquals(0, stats.getInt("legacy_reads"))
        assertEquals(if (mirrored) 1 else 0, stats.getInt("hd_requests"))
        if (mirrored) assertEquals(0, stats.getInt("original_reads"))
        rule.onNodeWithTag("live-photo-control").performTouchInput { up() }
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        rule.runOnIdle { assertFalse(player!!.playWhenReady) }
        runBlocking { model.api.obj("/qa/live/mode", "POST") }
    }
}
