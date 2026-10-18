package com.sakura.romance

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import kotlinx.coroutines.delay
import com.airbnb.lottie.compose.rememberLottieComposition

/** Keeps an observed loading screen mounted until its success animation finishes. */
@Stable
internal class LoadingPresentation(initialLoading: Boolean) {
    var visible by mutableStateOf(initialLoading); private set
    var operation by mutableIntStateOf(0); private set
    private var wasLoading = initialLoading

    fun update(loading: Boolean, failed: Boolean) {
        val pending = loading && !failed
        if (pending && !wasLoading) {
            operation++
            visible = true
        }
        if (failed) visible = false
        wasLoading = pending
    }

    fun finish(operation: Int) {
        if (this.operation == operation && !wasLoading) visible = false
    }
}

@Composable
internal fun rememberLoadingPresentation(loading: Boolean, failed: Boolean, key: Any = Unit): LoadingPresentation {
    val state = remember(key) { LoadingPresentation(loading) }
    state.update(loading, failed)
    return state
}

/** Fast cache hits skip the animation; a visible load still completes its success segment. */
@Composable
internal fun rememberMediaLoadingPresentation(loading: Boolean, failed: Boolean, key: Any = Unit): LoadingPresentation {
    val state = remember(key) { LoadingPresentation(false) }
    var waiting by remember(key, loading, failed) { mutableStateOf(false) }
    LaunchedEffect(key, loading, failed) {
        if (loading && !failed) {
            delay(200)
            waiting = true
        }
    }
    state.update(loading && (waiting || state.visible), failed)
    return state
}

@Composable
internal fun loadingIsActive(): Boolean {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return lifecycle.isAtLeast(Lifecycle.State.STARTED)
}

internal const val LoadingMaxWidth = 480
internal const val LoadingAspectRatio = 3.2f

/** v3 frames 0..100 follow real progress; 100..124 are the 800 ms success segment. */
@Composable
internal fun SakuraLoading(
    theme: String,
    progress: Float?,
    completed: Boolean,
    operationKey: Any,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    initiallyCompleted: Boolean = false,
    onFinished: () -> Unit = {},
) {
    val result = rememberLottieComposition(LottieCompositionSpec.Asset("loading/$theme.json"))
    val composition = result.value
    val completion = remember(theme, operationKey) { Animatable(if (initiallyCompleted) 1f else 0f) }
    val displayedProgress = remember(theme, operationKey) {
        Animatable(if (initiallyCompleted) 1f else progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f)
    }
    val finished by rememberUpdatedState(onFinished)
    var delivered by remember(theme, operationKey) { mutableStateOf(false) }
    val active = loadingIsActive() && isActive
    var lastProgress by remember(theme, operationKey) { mutableFloatStateOf(0f) }
    if (progress != null && progress.isFinite()) lastProgress = progress.coerceIn(0f, 1f)
    LaunchedEffect(theme, operationKey, completed, composition, result.isFailure, active, lastProgress) {
        if (!completed) {
            completion.snapTo(0f)
            delivered = false
            if (active) displayedProgress.animateTo(lastProgress, tween(220, easing = LinearEasing))
        } else if (active && (composition != null || result.isFailure)) {
            if (composition != null) {
                // Finish the visible travel before the kiss, even when byte progress was unknown.
                if (displayedProgress.value < 1f) {
                    displayedProgress.animateTo(1f, tween(((1f - displayedProgress.value) * 350).toInt().coerceAtLeast(1), easing = LinearEasing))
                }
                if (completion.value < 1f) {
                    completion.animateTo(1f, tween(((1f - completion.value) * 800).toInt().coerceAtLeast(1), easing = LinearEasing))
                }
            }
            if (!delivered) {
                delivered = true
                finished()
            }
        }
    }
    val breathing = if (progress == null && !completed && active) {
        val transition = rememberInfiniteTransition(label = "loading-breath")
        transition.animateFloat(0f, 1f,
            infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "loading-breath-pulse")
    } else null
    LottieAnimation(
        composition = composition,
        progress = {
            val frame = 100f * displayedProgress.value + if (completed) 24f * completion.value else 0f
            (frame / (composition?.endFrame ?: 125f)).coerceIn(0f, 1f)
        },
        modifier = modifier.widthIn(max = LoadingMaxWidth.dp).fillMaxWidth().aspectRatio(LoadingAspectRatio)
            .graphicsLayer { alpha = .94f + .06f * (breathing?.value ?: 1f) }
            .testTag("loading-$theme").semantics {
                stateDescription = if (completed) "加载完成" else "加载中"
                progressBarRangeInfo = if (progress == null && !completed) ProgressBarRangeInfo.Indeterminate
                    else ProgressBarRangeInfo(displayedProgress.value, 0f..1f)
            },
    )
}

@Composable
internal fun LoadingArtwork(
    theme: String,
    loading: Boolean,
    presentation: LoadingPresentation,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextSecondary,
    darkSurface: Boolean = false,
    isActive: Boolean = true,
    onFinished: () -> Unit = {},
    progress: Float? = null,
) {
    val operation = presentation.operation
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LoadingBackdrop(darkSurface = darkSurface) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SakuraLoading(theme, progress, !loading, operation, isActive = isActive, onFinished = {
                    presentation.finish(operation)
                    onFinished()
                })
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (loading && progress == null) {
                        CircularProgressIndicator(Modifier.size(16.dp).testTag("loading-indeterminate-$theme"), color = color, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (loading) text else "准备就绪", color = color, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
internal fun LoadingBackdrop(
    modifier: Modifier = Modifier,
    darkSurface: Boolean = false,
    verticalPadding: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier.widthIn(max = (LoadingMaxWidth + 24).dp).fillMaxWidth().drawBehind {
        val colors = if (darkSurface) listOf(SakuraPink.copy(alpha = .17f), RoseInk.copy(alpha = .08f), Color.Transparent)
            else listOf(PureWhite.copy(alpha = .58f), MistPink.copy(alpha = .20f), Color.Transparent)
        withTransform({ scale(1f, size.height / size.width, center) }) {
            drawCircle(Brush.radialGradient(colors, center, size.width / 2), radius = size.width / 2, center = center)
        }
    }.padding(horizontal = 12.dp, vertical = verticalPadding), contentAlignment = Alignment.Center) {
        content()
    }
}

internal data class QueueLoadingProgress(val progress: Float, val completed: Boolean)

internal fun queueLoadingProgress(tasks: List<UploadTask>): QueueLoadingProgress {
    val total = tasks.sumOf { it.size.coerceAtLeast(0).toDouble() }
    val sent = tasks.sumOf { if (it.status == "已完成") it.size.coerceAtLeast(0).toDouble() else it.displayOffset.toDouble() }
    val completed = tasks.isNotEmpty() && tasks.all { it.status == "已完成" }
    return QueueLoadingProgress(if (total > 0) (sent / total).toFloat().coerceIn(0f, 1f) else if (completed) 1f else 0f, completed)
}

/** Short clips only need their remaining duration; unknown duration uses the actual 8 s start threshold. */
internal fun videoStartBufferMs(durationMs: Long, positionMs: Long): Long =
    if (durationMs > 0) (durationMs - positionMs.coerceAtLeast(0)).coerceIn(1L, 8000L) else 8000L
