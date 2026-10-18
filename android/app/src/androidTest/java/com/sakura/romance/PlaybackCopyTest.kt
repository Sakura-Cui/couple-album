package com.sakura.romance

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackCopyTest {
    @get:Rule val rule = createComposeRule()
    @Test fun same4kCaptureCopyPlaysAt30fpsAndSeeksWithStandardDecoder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = AppModel(context.applicationContext as Application)
        model.vault.url = "https://10.0.2.2:18448"
        model.vault.token = "qa-live-only"
        lateinit var player: ExoPlayer
        var error: PlaybackException? = null
        rule.setContent {
            val p = remember {
                ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(model.api.client))).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(failure: PlaybackException) { error = failure }
                    })
                    setMediaItem(MediaItem.fromUri(model.api.playbackUrl("5".repeat(32))))
                    playWhenReady = true
                    prepare()
                }.also { player = it }
            }
            DisposableEffect(p) { onDispose { p.release() } }
            AndroidView(factory = { PlayerView(it).apply { this.player = p } })
        }
        rule.waitUntil(20000) {
            var ready = false
            rule.runOnIdle { ready = player.videoDecoderCounters?.renderedOutputBufferCount?.let { it > 0 } == true || error != null }
            ready
        }
        assertNull(error)
        var initial = 0
        rule.runOnIdle { initial = player.videoDecoderCounters!!.renderedOutputBufferCount }
        Thread.sleep(8000)
        rule.runOnIdle {
            val counters = player.videoDecoderCounters!!
            counters.ensureUpdated()
            val rendered = counters.renderedOutputBufferCount - initial
            val report = "rendered_in_8_seconds=$rendered dropped=${counters.droppedBufferCount}"
            File(context.filesDir, "playback-copy-performance.txt").writeText(report)
            assertTrue(report, rendered >= 210)
            assertTrue(report, counters.droppedBufferCount <= 8)
        }
        rule.runOnIdle { player.pause(); player.seekTo(2000) }
        rule.waitUntil(5000) {
            var ready = false
            rule.runOnIdle { ready = player.playbackState == Player.STATE_READY && player.currentPosition in 1900..2200 }
            ready
        }
        assertNull(error)
    }
}
