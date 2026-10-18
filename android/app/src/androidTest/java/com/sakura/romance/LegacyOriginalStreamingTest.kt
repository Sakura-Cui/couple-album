package com.sakura.romance

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduces the previous streaming strategy against the local limited-bandwidth fixture. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LegacyOriginalStreamingTest {
    @get:Rule val rule = createComposeRule()
    @Test fun startsThenRebuffersBeforeEnd() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = Vault(context).apply { url = "https://10.0.2.2:18449"; token = "" }
        val api = Api(vault)
        api.obj("/qa/reset", "POST")
        val invite = api.obj("/qa/invite").getString("invite")
        vault.token = api.obj("/api/bind", "POST", json("invite" to invite, "name" to "旧策略限速复现")).getString("token")
        val item = Media.parse(api.array("/api/albums/${"a".repeat(32)}/media").getJSONObject(0))
        api.obj("/qa/stream?bytes_per_second=16384", "POST")
        lateinit var player: ExoPlayer
        var frame = false
        var stalled = false
        var error: PlaybackException? = null
        try {
            rule.runOnUiThread {
                player = ExoPlayer.Builder(context)
                    .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(50000, 60000, 8000, 5000).build())
                    .setMediaSourceFactory(DefaultMediaSourceFactory(StreamCache.factory(context, vault, OkHttpDataSource.Factory(api.client))))
                    .build().apply {
                        addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { frame = true }
                            override fun onPlaybackStateChanged(state: Int) {
                                if (frame && state == Player.STATE_BUFFERING && currentPosition > 0 && currentPosition < duration * 9 / 10) stalled = true
                            }
                            override fun onPlayerError(failure: PlaybackException) { error = failure }
                        })
                        setMediaItem(MediaItem.fromUri(api.mediaUrl(item.id)))
                        prepare(); play()
                    }
            }
            rule.setContent {
                DisposableEffect(player) { onDispose { player.release() } }
                AndroidView(factory = { PlayerView(it).apply { this.player = player } })
            }
            rule.waitUntil(90000) { stalled || error != null }
            assertNull(error)
            assertTrue("Limited bandwidth must reproduce buffering after the first frame", stalled)
        } finally { api.obj("/qa/stream", "POST") }
    }
}
