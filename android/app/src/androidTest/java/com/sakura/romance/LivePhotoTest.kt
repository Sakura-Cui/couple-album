package com.sakura.romance

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the isolated HTTPS qa_live_server.py, never production media. */
@RunWith(AndroidJUnit4::class)
class LivePhotoTest {
    @get:Rule val rule = createComposeRule()
    private fun show(id: String, active: State<Boolean> = mutableStateOf(true), incompatible: Boolean? = null): AppModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = AppModel(context.applicationContext as Application)
        model.vault.url = "https://10.0.2.2:18448"
        model.vault.token = "qa-live-only"
        run {
            val factory = StreamCache.factory(context, model.vault, androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(model.api.client))
            val cache = factory.createDataSource().cache
            for (url in listOf(model.api.mediaUrl("2".repeat(32)), model.api.playbackUrl("2".repeat(32))+"?live=true")) {
                val spec = androidx.media3.datasource.DataSpec.Builder().setUri(url).build()
                cache.removeResource(factory.cacheKeyFactory.buildCacheKey(spec))
            }
            runBlocking { model.api.obj("/qa/live/mode?incompatible=${incompatible ?: false}", "POST") }
        }
        val item = Media(id.repeat(32), "a".repeat(32), "capture.JPG", "image/jpeg", 1, 1000, true)
        rule.setContent { Box(Modifier.fillMaxSize()) { LivePhotoOverlay(model, item, active.value) } }
        return model
    }
    private fun waitForTag(tag: String) = rule.waitUntil(15000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun missingMotionShowsOnlyTheStillPhoto() {
        show("3")
        Thread.sleep(1500)
        rule.onNodeWithTag("live-photo-control").assertDoesNotExist()
        rule.onNodeWithText("实况照片 · 缺少动态部分").assertDoesNotExist()
        rule.onNodeWithTag("live-photo-motion").assertDoesNotExist()
    }

    @Test fun holdPlaysRealFramesAndReleaseOrPageChangeStopsMotion() {
        val active = mutableStateOf(true)
        show("1", active)
        waitForTag("live-photo-control")
        rule.onNodeWithTag("live-photo-control").performTouchInput { down(center) }
        waitForTag("live-photo-frame")
        rule.runOnIdle { active.value = false }
        rule.onNodeWithTag("live-photo-motion").assertDoesNotExist()
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        rule.runOnIdle { active.value = true }
        waitForTag("live-photo-control")
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        rule.onNodeWithTag("live-photo-control").performTouchInput { up(); down(center) }
        waitForTag("live-photo-frame")
        rule.onNodeWithTag("live-photo-control").performTouchInput { up() }
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
    }

    @Test fun ordinaryPhotoHasNoLiveControl() {
        show("4")
        Thread.sleep(1000)
        rule.onNodeWithTag("live-photo-control").assertDoesNotExist()
    }

    private fun player(): Player {
        lateinit var result: Player
        onView(isAssignableFrom(PlayerView::class.java)).check { view, missing ->
            if (missing != null) throw missing
            result = (view as PlayerView).player!!
        }
        return result
    }

    @Test fun originalIsPreloadedAtFullResolutionAndReusedAcrossHolds() {
        val model = show("1", incompatible = false)
        waitForTag("live-photo-preload")
        rule.waitUntil(30000) {
            var ready = false
            val p=player()
            rule.runOnIdle { ready=p.playbackState==Player.STATE_READY && p.duration>0 && p.bufferedPosition>=p.duration-50 }
            ready
        }
        val original = player()
        rule.runOnIdle {
            assertFalse(original.playWhenReady)
            assertEquals(model.api.mediaUrl("2".repeat(32)), original.currentMediaItem!!.localConfiguration!!.uri.toString())
            assertEquals(1440, minOf(original.videoSize.width, original.videoSize.height))
        }
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.takeScreenshot(java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"live-preloaded.png"))
        rule.onNodeWithTag("live-photo-control").performTouchInput { down(center) }
        waitForTag("live-photo-frame")
        Thread.sleep(1200)
        rule.runOnIdle {
            assertTrue(original.currentPosition>300)
            val counters=(original as androidx.media3.exoplayer.ExoPlayer).videoDecoderCounters!!
            counters.ensureUpdated()
            java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"live-decoder-metrics.txt").writeText(
                "width=${original.videoSize.width},height=${original.videoSize.height},rendered=${counters.renderedOutputBufferCount},dropped=${counters.droppedBufferCount},maxConsecutiveDropped=${counters.maxConsecutiveDroppedBufferCount}")
        }
        device.takeScreenshot(java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"live-original-playing.png"))
        rule.onNodeWithTag("live-photo-control").performTouchInput { up() }
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        assertSame(original,player())
        rule.runOnIdle { assertFalse(original.playWhenReady) }
        val before=runBlocking { model.api.obj("/qa/live/stats").getInt("original_reads") }
        rule.onNodeWithTag("live-photo-control").performTouchInput { down(center) }
        waitForTag("live-photo-frame")
        rule.onNodeWithTag("live-photo-control").performTouchInput { up() }
        assertSame(original,player())
        val stats=runBlocking { model.api.obj("/qa/live/stats") }
        assertEquals(before,stats.getInt("original_reads"))
        assertEquals(0,stats.getInt("legacy_reads"))
        assertEquals(0,stats.getInt("hd_requests"))
    }

    @Test fun unsupportedOriginalFallsBackToDedicatedHdCopy() {
        val model=show("1", incompatible = true)
        try {
            waitForTag("live-photo-control")
            rule.onNodeWithTag("live-photo-control").performTouchInput { down(center) }
            waitForTag("live-photo-frame")
            val p=player()
            rule.runOnIdle {
                assertTrue(p.currentMediaItem!!.localConfiguration!!.uri.toString().endsWith("/playback/content?live=true"))
                assertEquals(1440,minOf(p.videoSize.width,p.videoSize.height))
            }
            val stats=runBlocking { model.api.obj("/qa/live/stats") }
            assertEquals(0,stats.getInt("legacy_reads"))
            assertTrue(stats.getInt("hd_reads")>0)
            assertEquals(1,stats.getInt("hd_requests"))
            rule.onNodeWithTag("live-photo-control").performTouchInput { up() }
            rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
        } finally { runBlocking { model.api.obj("/qa/live/mode?incompatible=false", "POST") } }
    }

}
