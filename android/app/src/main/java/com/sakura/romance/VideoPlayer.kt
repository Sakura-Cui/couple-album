package com.sakura.romance

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.testTag
import kotlin.math.abs
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun Video(model: AppModel, item: Media, pageActive: Boolean, swipe: (Int) -> Unit) {
    val context = LocalContext.current
    var source by remember(item.id) { mutableStateOf<String?>(null) }
    var status by remember(item.id) { mutableStateOf("checking") }
    var attempt by remember(item.id) { mutableIntStateOf(0) }
    var regenerate by remember(item.id) { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var playbackState by remember(item.id) { mutableIntStateOf(Player.STATE_IDLE) }
    var firstFrame by remember(item.id) { mutableStateOf(false) }
    var holdingPlayback by remember(item.id) { mutableStateOf(false) }
    var resumePlayback by remember(item.id) { mutableStateOf(true) }
    var wasActive by remember(item.id) { mutableStateOf(false) }
    var playbackStarted by remember(item.id, attempt) { mutableStateOf(false) }
    var originalDownload by remember(item.id, attempt) { mutableStateOf<OriginalVideoDownload?>(null) }
    var downloadedBytes by remember(item.id, attempt) { mutableLongStateOf(0L) }
    var adaptive by remember(item.id) { mutableStateOf(false) }
    var qualities by remember(item.id) { mutableStateOf(emptyList<VideoQuality>()) }
    var originalCompatible by remember(item.id) { mutableStateOf(false) }
    var quality by remember(item.id) { mutableStateOf("auto") }
    var qualityMenu by remember(item.id) { mutableStateOf(false) }
    var switchPosition by remember(item.id) { mutableLongStateOf(0L) }
    var tracksVersion by remember(item.id) { mutableIntStateOf(0) }
    val original = quality == "original" || (!adaptive && status == "original")
    val active = loadingIsActive() && pageActive
    val loading = source == null || !firstFrame || playbackState == Player.STATE_IDLE ||
        (!playbackStarted && playbackState == Player.STATE_BUFFERING)
    val buffering = firstFrame && playbackState == Player.STATE_BUFFERING
    val presentation = rememberMediaLoadingPresentation(loading && active, error != null || !active, item.id)
    val player = remember(item.id, attempt, original, adaptive) {
        val upstream = OkHttpDataSource.Factory(model.api.client)
        val mediaSource = StreamCache.factory(context, model.vault, upstream)
        if (original) mediaSource.setUpstreamDataSourceFactory(null)
        ExoPlayer.Builder(context)
            .setLoadControl(if (adaptive && !original) DefaultLoadControl.Builder().setBufferDurationsMs(20000, 30000, 1500, 2500).build()
                else DefaultLoadControl.Builder().setBufferDurationsMs(50000, 60000, 8000, 5000).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(if (adaptive && !original) PlaylistDataSourceFactory(upstream, mediaSource) else mediaSource))
            .build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = state
                }
                override fun onRenderedFirstFrame() { firstFrame = true }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { tracksVersion++ }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (holdingPlayback && playWhenReady) {
                        resumePlayback = true
                        pause()
                    }
                }
                override fun onPlayerError(failure: PlaybackException) {
                    error = "视频加载失败，请检查网络或相册授权后重试。"
                }
            })
        }
    }
    val currentPlayer by rememberUpdatedState(player)
    LaunchedEffect(item.id, active, attempt) {
        var preparing: OriginalVideoDownload? = null
        if (active && error == null && (source == null || (!adaptive && status == "ready") || source?.endsWith("?preview=1") == true)) {
            try {
                if (quality == "original") status = "original"
                else {
                    var details: org.json.JSONObject? = null
                    try {
                        val base = "/api/media/${item.id}/playback/adaptive"
                        details = model.api.obj("$base?retry=$regenerate", "POST")
                        if (details.getString("status") != "unsupported") regenerate = false
                        if (source == null && details.getString("status") in listOf("pending", "processing", "failed")) {
                            val fallback = model.api.obj("/api/media/${item.id}/playback")
                            if (fallback.getString("status") == "ready") {
                                status = "ready"
                                source = model.api.playbackUrl(item.id)
                            }
                        }
                        while (details!!.getString("status") in listOf("pending", "processing")) {
                            if (details.optBoolean("preview_ready") && source?.contains("/playback/hls/") != true && quality != "original") {
                                if (source != null) withContext(Dispatchers.Main.immediate) {
                                    switchPosition = currentPlayer.currentPosition.coerceAtLeast(0)
                                    if (!holdingPlayback) resumePlayback = currentPlayer.playWhenReady
                                    currentPlayer.pause()
                                    firstFrame = false
                                    playbackState = Player.STATE_IDLE
                                    playbackStarted = false
                                    holdingPlayback = true
                                }
                                adaptive = true
                                qualities = details.videoQualities()
                                originalCompatible = details.optBoolean("original_compatible")
                                status = "ready"
                                source = model.api.adaptiveUrl(item.id) + "?preview=1"
                            }
                            if (source == null) status = details.getString("status")
                            delay(2000)
                            details = model.api.obj(base)
                        }
                    } catch (failure: ApiError) {
                        if (failure.status != 404) throw failure
                    }
                    if (details != null && details.getString("status") != "unsupported") {
                        if (details.getString("status") == "failed" && source != null) return@LaunchedEffect
                        if (quality == "original") {
                            qualities = details.videoQualities()
                            return@LaunchedEffect
                        }
                        if (source != null) withContext(Dispatchers.Main.immediate) {
                            switchPosition = currentPlayer.currentPosition.coerceAtLeast(0)
                            if (!holdingPlayback) resumePlayback = currentPlayer.playWhenReady
                            currentPlayer.pause()
                            firstFrame = false
                            playbackState = Player.STATE_IDLE
                            playbackStarted = false
                            holdingPlayback = true
                        }
                        adaptive = true
                        status = details.getString("status")
                        if (status == "ready") {
                            qualities = details.videoQualities()
                            originalCompatible = details.optBoolean("original_compatible")
                        }
                    } else {
                        try {
                            if (regenerate) {
                                model.api.obj("/api/media/${item.id}/playback/retry", "POST")
                                regenerate = false
                            }
                            do {
                                status = model.api.obj("/api/media/${item.id}/playback").getString("status")
                                if (status in listOf("pending", "processing")) delay(5000)
                            } while (status in listOf("pending", "processing"))
                        } catch (failure: ApiError) {
                            if (failure.status == 404) status = "original" else throw failure
                        }
                    }
                }
                when (status) {
                    "ready" -> source = if (adaptive) model.api.adaptiveUrl(item.id) else model.api.playbackUrl(item.id)
                    "original" -> {
                        val download = OriginalVideoDownload(context, model.vault, model.api, item)
                        preparing = download
                        originalDownload = download
                        download.prepare(model.vault) { downloadedBytes = it }
                        source = model.api.mediaUrl(item.id)
                    }
                    "failed" -> error = if (adaptive) "视频暂时准备失败，请稍后重试。" else "视频暂时准备失败，请稍后重试。"
                }
            } catch (cancelled: CancellationException) {
                preparing?.close()
                throw cancelled
            } catch (failure: Exception) {
                preparing?.close()
                originalDownload = null
                error = if (failure is OriginalVideoSpaceException) failure.message
                    else "视频加载失败，请检查网络或相册授权后重试。"
            }
        }
    }
    LaunchedEffect(player, source) {
        source?.let { player.setMediaItem(MediaItem.fromUri(it), switchPosition); player.prepare() }
    }
    LaunchedEffect(player, quality, tracksVersion) {
        if (adaptive && !original) player.trackSelectionParameters = videoQualityParameters(
            player.currentTracks, player.trackSelectionParameters, qualities.firstOrNull { it.id == quality })
    }
    LaunchedEffect(active, player) {
        if (!active && original && source == null) {
            player.stop()
            source = null
            firstFrame = false
            playbackState = Player.STATE_IDLE
            playbackStarted = false
            originalDownload?.close()
            originalDownload = null
        }
    }
    DisposableEffect(originalDownload) {
        val download = originalDownload
        onDispose { download?.close() }
    }
    LaunchedEffect(active, player, loading, presentation.visible, error) {
        if ((loading || presentation.visible) && !holdingPlayback) {
            if (wasActive) resumePlayback = player.playWhenReady
            holdingPlayback = true
            player.pause()
        }
        if (!active || error != null) {
            if (wasActive && !holdingPlayback) resumePlayback = player.playWhenReady
            player.pause()
        } else if (!loading && !presentation.visible) {
            holdingPlayback = false
            playbackStarted = true
            player.playWhenReady = resumePlayback
        }
        wasActive = active
    }
    var bufferedMs by remember(item.id, attempt) { mutableLongStateOf(0L) }
    var bufferTargetMs by remember(item.id, attempt) { mutableLongStateOf(8000L) }
    LaunchedEffect(player, active, loading, source) {
        if (active && loading && source != null) {
            while (true) {
                bufferedMs = player.totalBufferedDuration.coerceAtLeast(0)
                bufferTargetMs = videoStartBufferMs(player.duration, player.currentPosition).let { if (adaptive && !original) it.coerceAtMost(1500L) else it }
                delay(150)
            }
        }
    }
    val bufferProgress = when {
        original && source == null -> (downloadedBytes.toFloat() / item.size).coerceIn(0f, 1f)
        source == null -> null
        else -> (bufferedMs.toFloat() / bufferTargetMs).coerceIn(0f, 1f)
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { SwipePlayerView(it).apply { useController = false; this.player = player } },
            update = { it.player = player; it.swipe = swipe; it.useController = active && firstFrame && !loading && !presentation.visible && error == null },
            modifier = Modifier.fillMaxSize().testTag(if (firstFrame) "video-frame" else "video-player"))
        if ((!firstFrame || presentation.visible) && error == null) {
            AsyncImage(model.api.mediaUrl(item.id, true), null, model.images,
                Modifier.fillMaxSize().background(ViewerSurface).testTag("video-poster"), contentScale = ContentScale.Fit)
        }
        if (error == null && presentation.visible) {
            LoadingArtwork("video", loading, presentation,
                when {
                    status in listOf("pending", "processing") -> "正在准备视频，稍后会自动播放…"
                    original && source == null -> "正在缓存原片… ${String.format(Locale.ROOT, "%.1f", downloadedBytes / 1048576f)} / ${String.format(Locale.ROOT, "%.1f", item.size / 1048576f)} MB"
                    source == null -> "正在检查视频…"
                    bufferProgress == 1f -> "缓冲就绪，正在准备画面…"
                    else -> "正在缓冲视频… ${String.format(Locale.ROOT, "%.1f", bufferedMs.coerceAtMost(bufferTargetMs) / 1000f)} / ${String.format(Locale.ROOT, "%.1f", bufferTargetMs / 1000f)} 秒"
                },
                Modifier.fillMaxSize().background(ViewerSurface).padding(horizontal = 24.dp), ViewerSecondary, darkSurface = true, isActive = active, progress = bufferProgress)
        }
        if (buffering && active && !presentation.visible && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp).testTag("video-buffering"), color = SakuraPink)
        }
        if (adaptive && qualities.isNotEmpty() && active) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                val label = if (quality == "original") "原片" else qualities.firstOrNull { it.id == quality }?.label ?: "自动"
                TextButton({ qualityMenu = true }, Modifier.background(ViewerSurface.copy(alpha = .85f), RoundedCornerShape(12.dp)).testTag("video-quality")) {
                    Text(label, color = PureWhite)
                    Icon(Icons.Outlined.ArrowDropDown, null, tint = PureWhite)
                }
                DropdownMenu(qualityMenu, { qualityMenu = false }) {
                    val choices = listOf("auto" to "自动") + qualities.map { it.id to "${it.label} ${minOf(it.width, it.height)}P" } +
                        if (originalCompatible) listOf("original" to "原片（需完整缓存）") else emptyList()
                    choices.forEach { (id, text) ->
                        DropdownMenuItem(text = { Text(if (id == quality) "✓ $text" else text) }, modifier = Modifier.testTag("video-quality-$id"), onClick = {
                            qualityMenu = false
                            if (id != quality) {
                                if (id == "original" || quality == "original") {
                                    switchPosition = if (source != null) player.currentPosition.coerceAtLeast(0) else switchPosition
                                    if (!holdingPlayback) resumePlayback = player.playWhenReady
                                    player.pause()
                                    originalDownload?.close()
                                    originalDownload = null
                                    source = null
                                    error = null
                                    firstFrame = false
                                    playbackState = Player.STATE_IDLE
                                    playbackStarted = false
                                    holdingPlayback = true
                                    status = if (id == "original") "original" else "ready"
                                    attempt++
                                } else if (source != null) {
                                    player.trackSelectionParameters = videoQualityParameters(player.currentTracks,
                                        player.trackSelectionParameters, qualities.firstOrNull { it.id == id })
                                    player.setMediaItem(MediaItem.fromUri(source!!), player.currentPosition.coerceAtLeast(0))
                                    player.prepare()
                                }
                                quality = id
                            }
                        })
                    }
                }
            }
        }
        error?.let { text -> Column(Modifier.align(Alignment.Center).background(ViewerSurface.copy(alpha = .94f)).padding(24.dp)) {
            Text(text, color = PureWhite)
            TextButton({
                regenerate = status == "failed"
                error = null
                source = null
                status = "checking"
                holdingPlayback = true
                resumePlayback = true
                firstFrame = false
                player.pause()
                playbackState = Player.STATE_IDLE
                attempt++
            }) { Text("重试") }
        } }
    }
}

// PlayerView consumes touch events before Compose's pager can see a drag.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class SwipePlayerView(context: android.content.Context) : PlayerView(context) {
    var swipe: (Int) -> Unit = {}
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var controlTouch = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun hitsControl(view: View, x: Float, y: Float): Boolean {
        if (!view.isShown) return false
        val bounds = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(x.toInt(), y.toInt())) return false
        if (view !== this && (view.isClickable || view is androidx.media3.ui.TimeBar)) return true
        return view is ViewGroup && (0 until view.childCount).any { hitsControl(view.getChildAt(it), x, y) }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y; dragging = false
                controlTouch = hitsControl(this, event.rawX, event.rawY)
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> controlTouch = true
            MotionEvent.ACTION_MOVE -> if (!controlTouch && !dragging && abs(event.x - startX) > slop && abs(event.x - startX) > abs(event.y - startY) * 1.5f) {
                dragging = true
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    if (abs(event.x - startX) > width * .15f) swipe(if (event.x < startX) 1 else -1)
                    dragging = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; parent.requestDisallowInterceptTouchEvent(false) }
        }
        return if (dragging) true else super.dispatchTouchEvent(event)
    }
}
