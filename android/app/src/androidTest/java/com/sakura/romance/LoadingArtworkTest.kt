package com.sakura.romance

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airbnb.lottie.LottieCompositionFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LoadingArtworkTest {
    @get:Rule val rule = createComposeRule()
    private val themes = listOf("video", "image", "queue", "app")

    @Before fun preloadActualAssets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (theme in themes) {
            val result = LottieCompositionFactory.fromAssetSync(context, "loading/$theme.json")
            assertNull("$theme asset must decode", result.exception)
            assertNotNull(result.value)
        }
    }

    @Test fun steppedMasksReachEveryItemAtOneHundredPercent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((theme, right) in listOf("queue" to 824f, "image" to 826f)) {
            val composition = LottieCompositionFactory.fromAssetSync(context, "loading/$theme.json").value!!
            val layer = composition.layers.first { it.name == "progress-fill" }
            val field = layer.javaClass.getDeclaredField("masks").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val masks = field.get(layer) as List<com.airbnb.lottie.model.content.Mask>
            val mask = masks.first().maskPath.createAnimation()
            mask.progress = 50f / composition.endFrame
            mask.value.computeBounds(android.graphics.RectF(), true)
            mask.progress = 100f / composition.endFrame
            val bounds = android.graphics.RectF()
            mask.value.computeBounds(bounds, true)
            val scale = composition.bounds.width() / 960f
            assertEquals("$theme mask should reveal all items", right * scale, bounds.right, 2f)
        }
    }

    @Test fun fourThemesFitCompactAndDarkSurfaces() {
        var dark by mutableStateOf(false)
        var progress by mutableFloatStateOf(.5f)
        rule.setContent {
            SakuraTheme {
                Column(Modifier.width(272.dp).background(if (dark) Color(0xFF18151B) else WarmWhite)) {
                    themes.forEach { theme ->
                        LoadingBackdrop(Modifier.testTag("surface-$theme"), darkSurface = dark) {
                            SakuraLoading(theme, progress, false, theme)
                        }
                    }
                }
            }
        }
        for (theme in themes) {
            val bounds = rule.onNodeWithTag("loading-$theme").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(3.2f, bounds.width / bounds.height, .03f)
            capture(theme, "light")
        }
        rule.runOnIdle { dark = true; progress = 1f }
        for (theme in themes) {
            rule.onNodeWithTag("loading-$theme").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "加载中"))
            capture(theme, "dark-waiting")
        }
    }

    @Test fun realProgressMovesContinuouslyAndHoldsWhileInactive() {
        var progress by mutableFloatStateOf(.2f)
        var active by mutableStateOf(true)
        rule.setContent { SakuraLoading("queue", progress, false, "queue", isActive = active) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        fun displayed() = rule.onNodeWithTag("loading-queue").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
        rule.runOnIdle { progress = .8f }
        rule.mainClock.advanceTimeBy(112)
        assertTrue(displayed() > .2f && displayed() < .8f)
        rule.mainClock.advanceTimeBy(200)
        assertEquals(.8f, displayed(), .001f)
        rule.runOnIdle { active = false; progress = 1f }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(.8f, displayed(), .001f)
        rule.runOnIdle { active = true }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(1f, displayed(), .001f)
        rule.onNodeWithTag("loading-queue").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "加载中"))
    }

    @Test fun fourThemesUseAvailableWidthWithoutStretching() {
        var width by mutableIntStateOf(272)
        rule.setContent {
            Column(Modifier.width(width.dp)) {
                themes.forEach { theme ->
                    LoadingBackdrop(Modifier.testTag("surface-$theme")) { SakuraLoading(theme, .5f, false, theme) }
                }
            }
        }
        val screenWidth = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp
        for (availableWidth in listOf(272, 360, 560)) {
            rule.runOnIdle { width = availableWidth }
            for (theme in themes) {
                val node = rule.onNodeWithTag("loading-$theme").assertIsDisplayed()
                node.assertWidthIsEqualTo((minOf(availableWidth, screenWidth, LoadingMaxWidth + 24) - 24).dp)
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertEquals(LoadingAspectRatio, bounds.width / bounds.height, .03f)
                capture(theme, "width-$availableWidth")
            }
        }
    }

    @Test fun artworkUsesReportedProgressAndUnknownLoadsShowWaitingIndicator() {
        var progress by mutableStateOf<Float?>(null)
        rule.setContent {
            LoadingArtwork("app", true, rememberLoadingPresentation(true, false), "waiting", progress = progress)
        }
        rule.onNodeWithTag("loading-indeterminate-app").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { progress = .4f }
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("loading-indeterminate-app").assertDoesNotExist()
        val displayed = rule.onNodeWithTag("loading-app").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(.4f, displayed, .001f)
        rule.mainClock.advanceTimeBy(5000)
        assertEquals("Elapsed time must not advance reported loading", .4f,
            rule.onNodeWithTag("loading-app").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current, .001f)
    }

    @Test fun unknownProgressTraversesMiddleFramesBeforeSuccess() {
        var completed by mutableStateOf(false)
        var calls = 0
        rule.setContent { LoadingBackdrop(Modifier.testTag("surface-app")) { SakuraLoading("app", null, completed, "operation", onFinished = { calls++ }) } }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { completed = true }
        rule.mainClock.advanceTimeBy(176)
        val progress = rule.onNodeWithTag("loading-app").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue("Success should visibly pass through the middle, actual=$progress", progress > .1f && progress < .9f)
        assertEquals(0, calls)
        capture("app", "success-middle")
        rule.mainClock.advanceTimeBy(1300)
        assertEquals(1, calls)
    }

    @Test fun successTravelsThenWaitsEightHundredMillisecondsAndPausesWhenHidden() {
        var completed by mutableStateOf(false)
        var active by mutableStateOf(true)
        var calls = 0
        rule.setContent { SakuraLoading("video", null, completed, "operation", isActive = active, onFinished = { calls++ }) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { completed = true }
        rule.mainClock.advanceTimeBy(400)
        assertEquals(0, calls)
        rule.runOnIdle { active = false }
        rule.mainClock.advanceTimeBy(32)
        rule.mainClock.advanceTimeBy(1600)
        assertEquals(0, calls)
        rule.runOnIdle { active = true }
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(1, calls)
        rule.runOnIdle { active = false }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { active = true }
        rule.mainClock.advanceTimeBy(1600)
        assertEquals(1, calls)
    }

    @Test fun failureRetryAndNewOperationDoNotRevealContentEarly() {
        var loading by mutableStateOf(true)
        var failed by mutableStateOf(false)
        rule.setContent {
            val presentation = rememberLoadingPresentation(loading, failed)
            when {
                failed -> Text("error")
                presentation.visible -> LoadingArtwork("image", loading, presentation, "waiting")
                else -> Text("content")
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { loading = false }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("content").assertDoesNotExist()
        rule.runOnIdle { failed = true; loading = true }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithText("error").assertIsDisplayed()
        rule.onNodeWithTag("loading-image").assertDoesNotExist()
        rule.runOnIdle { failed = false; loading = true }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { loading = false }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { loading = true }
        rule.mainClock.advanceTimeBy(1300)
        rule.onNodeWithText("content").assertDoesNotExist()
        rule.runOnIdle { loading = false }
        rule.mainClock.advanceTimeBy(1300)
        rule.onNodeWithText("content").assertIsDisplayed()
    }

    @Test fun fastMediaSkipsLoadingButSlowMediaKeepsCompletion() {
        var loading by mutableStateOf(true)
        var active by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Column {
                for (theme in listOf("image", "video")) {
                    val presentation = rememberMediaLoadingPresentation(loading && active, !active)
                    if (presentation.visible) LoadingArtwork(theme, loading, presentation, "waiting")
                    else if (!loading) Text("ready-$theme")
                }
            }
        }
        rule.mainClock.advanceTimeBy(64)
        for (theme in listOf("image", "video")) rule.onNodeWithTag("loading-$theme").assertDoesNotExist()
        rule.runOnIdle { loading = false }
        rule.mainClock.advanceTimeBy(32)
        for (theme in listOf("image", "video")) rule.onNodeWithText("ready-$theme").assertIsDisplayed()
        rule.runOnIdle { loading = true }
        rule.mainClock.advanceTimeBy(256)
        for (theme in listOf("image", "video")) rule.onNodeWithTag("loading-$theme").assertIsDisplayed()
        rule.runOnIdle { loading = false }
        rule.mainClock.advanceTimeBy(400)
        for (theme in listOf("image", "video")) rule.onNodeWithText("ready-$theme").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(1000)
        for (theme in listOf("image", "video")) {
            rule.onNodeWithTag("loading-$theme").assertDoesNotExist()
            rule.onNodeWithText("ready-$theme").assertIsDisplayed()
        }
        // An adjacent image that finished preloading must not queue an unseen animation.
        rule.runOnIdle { active = false; loading = true }
        rule.mainClock.advanceTimeBy(400)
        for (theme in listOf("image", "video")) rule.onNodeWithTag("loading-$theme").assertDoesNotExist()
        rule.runOnIdle { loading = false; active = true }
        rule.mainClock.advanceTimeBy(32)
        for (theme in listOf("image", "video")) rule.onNodeWithText("ready-$theme").assertIsDisplayed()
    }

    private fun capture(theme: String, state: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = rule.onNodeWithTag("surface-$theme").captureToImage().asAndroidBitmap()
        File(context.filesDir, "qa-loading-$theme-$state.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
