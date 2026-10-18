package com.sakura.romance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal data class LivePhotoPreparation(val text: String = "正在准备实况", val progress: Float? = null, val ready: Boolean = false)

@Composable
internal fun LivePhotoOverlay(model: AppModel, item: Media, pageActive: Boolean) {
    val active = loadingIsActive() && pageActive
    var live by remember(item.id) { mutableStateOf(item.live) }
    var motion by remember(item.id) { mutableStateOf(item.motionId) }
    var motionResolved by remember(item.id) { mutableStateOf(false) }
    var mirrored by remember(item.id) { mutableStateOf(false) }
    var duration by remember(item.id) { mutableStateOf<Long?>(null) }
    var pressed by remember(item.id) { mutableStateOf(false) }
    var failure by remember(item.id) { mutableStateOf<String?>(null) }
    var preparation by remember(item.id) { mutableStateOf(LivePhotoPreparation()) }
    var attempt by remember(item.id) { mutableIntStateOf(0) }
    LaunchedEffect(item.id, active, attempt) {
        pressed = false
        if (active) {
            motionResolved = false
            preparation = LivePhotoPreparation()
            try {
                val result = withTimeout(30000) { model.api.obj("/api/media/${item.id}/live") }
                motion = result.optString("motion_id").takeUnless { it.isBlank() || it == "null" }
                live = result.getBoolean("live") && motion != null
                mirrored = result.optBoolean("motion_mirrored", false)
                duration = result.optLong("motion_duration_ms").takeIf { it > 0 }
                failure = null
            } catch (_: TimeoutCancellationException) {
                failure = "实况准备超时"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = "实况加载失败"
            } finally { motionResolved = true }
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (active && motionResolved && motion != null && failure == null) {
            LivePhotoMotion(model, motion!!, mirrored, duration, pressed, { preparation = it }) {
                failure = it; pressed = false
            }
        }
        if (active && ((live && motion != null) || failure != null)) {
            val gesture = if (failure == null) Modifier.pointerInput(item.id, motion) {
                detectTapGestures(onPress = {
                    pressed = true
                    try { tryAwaitRelease() } finally { pressed = false }
                })
            } else Modifier.clickable { failure = null; attempt++ }
            val loading = failure == null && !preparation.ready
            val text = failure?.let { "$it · 点击重试" } ?: if (preparation.ready) "实况 · 按住播放"
                else preparation.text + (preparation.progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
            Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(min = 200.dp, max = 320.dp)
                .background(ViewerSurface.copy(alpha = .94f), RoundedCornerShape(24.dp))
                .border(1.dp, SakuraPink.copy(alpha = .28f), RoundedCornerShape(24.dp))
                .then(gesture).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("live-photo-control").semantics { stateDescription = text; liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (loading) {
                    val indicator = Modifier.size(18.dp).testTag("live-photo-loading")
                    val progress = preparation.progress
                    if (progress == null) CircularProgressIndicator(indicator, color = SakuraPink, strokeWidth = 2.dp, trackColor = SakuraPink.copy(alpha = .18f))
                    else CircularProgressIndicator(progress = { progress }, modifier = indicator, color = SakuraPink, strokeWidth = 2.dp, trackColor = SakuraPink.copy(alpha = .18f))
                    Spacer(Modifier.width(8.dp))
                }
                Text(text, color = PureWhite, fontSize = 13.sp)
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LivePhotoMotion(model: AppModel, motionId: String, mirrored: Boolean, durationMs: Long?, pressed: Boolean,
                            status: (LivePhotoPreparation) -> Unit, failed: (String) -> Unit) {
    val context = LocalContext.current
    val onFailed by rememberUpdatedState(failed)
    var compatibility by remember(motionId, mirrored) { mutableStateOf(mirrored) }
    var firstFrame by remember(motionId, compatibility) { mutableStateOf(false) }
    var playbackState by remember(motionId, compatibility) { mutableIntStateOf(Player.STATE_IDLE) }
    var complete by remember(motionId, compatibility) { mutableStateOf(false) }
    var decoded by remember(motionId, compatibility) { mutableStateOf(false) }
    var preparing by remember(motionId, compatibility) { mutableStateOf(LivePhotoPreparation()) }
    var download by remember(motionId, compatibility) { mutableStateOf<OriginalVideoDownload?>(null) }
    var holdAllowed by remember(motionId, compatibility, pressed) { mutableStateOf(false) }
    var holdStarted by remember(motionId, compatibility, pressed) { mutableStateOf(false) }
    val player = remember(motionId, compatibility) {
        ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000, 15000, 250, 500).build())
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { playbackState = state }
                    override fun onRenderedFirstFrame() { firstFrame = true }
                    override fun onPlayerError(error: PlaybackException) {
                        val unsupported = error.errorCode in listOf(
                            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                            PlaybackException.ERROR_CODE_DECODING_FAILED,
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
                        if (!compatibility && unsupported) compatibility = true else onFailed("实况播放失败")
                    }
                })
                playWhenReady = false
            }
    }
    LaunchedEffect(player) {
        withContext(Dispatchers.Main.immediate) {
            try {
                withTimeout(120000) {
                    requireShortLivePhoto(durationMs)
                    val source = if (compatibility) {
                        preparing = LivePhotoPreparation("正在准备动态画面")
                        val base = "/api/media/$motionId/playback"
                        var state = model.api.obj("$base/retry?live=true", "POST").getString("status")
                        while (state in listOf("pending", "processing")) {
                            delay(500)
                            state = model.api.obj("$base?live=true").getString("status")
                        }
                        check(state == "ready")
                        model.api.playbackUrl(motionId) + "?live=true"
                    } else model.api.mediaUrl(motionId)
                    preparing = LivePhotoPreparation("正在加载实况")
                    val size = livePhotoSourceSize(model.api, source)
                    val item = Media(motionId, "", "", "video/mp4", size, 0, false)
                    val selected = OriginalVideoDownload(context, model.vault, model.api, item, source)
                    download = selected
                    selected.prepare(model.vault) { bytes ->
                        launch(Dispatchers.Main.immediate) {
                            preparing = if (bytes == size) LivePhotoPreparation("正在准备画面")
                                else LivePhotoPreparation("正在加载实况", (bytes.toFloat() / size).coerceIn(0f, 1f))
                        }
                    }
                    complete = true
                    preparing = LivePhotoPreparation("正在准备画面")
                    // All playback reads use the same encrypted cache key, with no network fallback.
                    player.setMediaSource(ProgressiveMediaSource.Factory(selected.cacheOnlySourceFactory()).createMediaSource(MediaItem.fromUri(source)))
                    player.prepare()
                    withTimeout(20000) {
                        while (playbackState != Player.STATE_READY || !firstFrame) delay(50)
                    }
                    requireShortLivePhoto(player.duration)
                    decoded = true
                }
            } catch (_: TimeoutCancellationException) { onFailed("实况准备超时") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onFailed(when (error) {
                is LivePhotoPreparationException -> error.message ?: "实况信息不完整"
                is OriginalVideoSpaceException -> "手机空间不足，无法准备实况"
                else -> "实况加载失败"
            }) }
        }
    }
    val ready = complete && decoded && firstFrame && playbackState in listOf(Player.STATE_READY, Player.STATE_ENDED)
    val preparation = when {
        !complete -> preparing
        !ready -> LivePhotoPreparation(if (holdStarted) "正在缓冲实况" else "正在准备画面")
        pressed && !holdAllowed -> LivePhotoPreparation("正在准备播放")
        else -> LivePhotoPreparation(ready = true)
    }
    SideEffect { status(preparation) }
    LaunchedEffect(player, pressed) {
        if (pressed) {
            try {
                // Even a fully cached clip must respect current device and album access.
                withTimeout(30000) { model.api.obj("/api/media/$motionId/playback?live=true") }
                holdAllowed = true
            } catch (_: TimeoutCancellationException) { onFailed("实况访问超时") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailed("实况访问失败") }
        } else withContext(Dispatchers.Main.immediate) { player.pause(); player.seekTo(0) }
    }
    LaunchedEffect(player, pressed, ready, holdAllowed) {
        if (pressed && ready && holdAllowed && !holdStarted) {
            holdStarted = true
            withContext(Dispatchers.Main.immediate) { player.seekTo(0); player.play() }
        }
    }
    DisposableEffect(player) { onDispose { download?.close(); player.release() } }
    Box(Modifier.fillMaxSize()) {
        val visible = pressed && holdStarted && ready
        AndroidView(factory = { android.view.LayoutInflater.from(it).inflate(R.layout.live_photo_player, null) as PlayerView },
            update = { it.player = player; it.alpha = if (visible) 1f else 0f },
            modifier = Modifier.fillMaxSize().testTag(if (!pressed) "live-photo-preload" else if (visible) "live-photo-frame" else "live-photo-motion"))
    }
}
