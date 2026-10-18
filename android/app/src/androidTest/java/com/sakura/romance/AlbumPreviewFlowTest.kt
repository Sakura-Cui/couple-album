package com.sakura.romance

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses scripts/qa_preview_server.py with isolated data on port 18448. */
@RunWith(AndroidJUnit4::class)
class AlbumPreviewFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = rule.activity.model
    private fun qa(path: String) = runBlocking { model.api.obj(path, "POST") }
    private fun count() = runBlocking { model.api.obj("/qa/preview-counts").getInt("total") }
    private fun waitFor(check: () -> Boolean) = rule.waitUntil(30000, check)
    private fun bind() {
        rule.mainClock.advanceTimeBy(5100)
        rule.runOnUiThread { model.vault.url = "https://10.0.2.2:18448" }
        val invite = qa("/qa/reset").getString("invite")
        rule.runOnUiThread { model.bind("https://10.0.2.2:18448", invite, "预览缓存回归") }
        waitFor { model.ready && !model.busy && model.albums.isNotEmpty() }
    }

    @Test fun firstScreenShowsRealProgressAndDoesNotWaitForOffscreenPreviews() {
        bind()
        try {
            rule.runOnUiThread { model.openAlbum(model.albums.single()) }
            waitFor { count() > 0 && model.media.isNotEmpty() }
            try {
                waitFor { runCatching { rule.onNodeWithTag("loading-app").assertIsDisplayed() }.isSuccess }
            } catch (failure: Throwable) {
                java.io.File(rule.activity.filesDir, "qa-pending-tree.txt").writeText(rule.onRoot(useUnmergedTree = true).printToString())
                java.io.File(rule.activity.filesDir, "qa-pending.png").outputStream().use {
                    rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                throw failure
            }
            qa("/qa/release-previews?limit=3")
            waitFor {
                val node = rule.onAllNodesWithTag("loading-app").fetchSemanticsNodes().singleOrNull()
                val progress = node?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
                progress != null && progress != androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate && progress.current > 0f && progress.current < 1f
            }
            rule.onNodeWithTag("loading-app").assertIsDisplayed()
            qa("/qa/release-previews?limit=24")
            waitFor { rule.onAllNodesWithTag("loading-app").fetchSemanticsNodes().isEmpty() && rule.onAllNodesWithTag("album-media-grid").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("album-media-grid").assertIsDisplayed()
            assertEquals("Metadata should expose the page without waiting for all its thumbnails", 30, model.media.size)
            assertFalse("Offscreen thumbnails are intentionally still blocked", runBlocking { model.api.obj("/qa/preview-counts").getBoolean("released") })
            qa("/qa/release-previews")
            rule.runOnUiThread { model.images.memoryCache?.clear() }
            rule.onNodeWithTag("album-media-grid").performScrollToIndex(25)
            waitFor { rule.onAllNodesWithTag("preview-ready-${model.media[25].id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("album-media-grid").performScrollToIndex(0)
            waitFor { rule.onAllNodesWithTag("preview-ready-${model.media.first().id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val loaded = count()
            rule.onNodeWithTag("album-media-grid").performScrollToIndex(25)
            rule.onNodeWithTag("album-media-grid").performScrollToIndex(0)
            rule.waitForIdle()
            assertEquals("Scroll back must use the local preview cache", loaded, count())
            rule.runOnUiThread { model.closeAlbum() }
            waitFor { !model.busy }
            rule.runOnUiThread { model.openAlbum(model.albums.single()) }
            waitFor { rule.onAllNodesWithTag("loading-app").fetchSemanticsNodes().isEmpty() && rule.onAllNodesWithTag("album-media-grid").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Re-entering must not download the same thumbnails", loaded, count())
            rule.runOnUiThread { model.background(); model.resume() }
            waitFor { model.ready && !model.mediaLoading && model.media.size == 30 }
            assertEquals("Clearing memory on background must retain encrypted disk previews", loaded, count())
        } finally { qa("/qa/release-previews") }
    }

    @Test fun pendingFirstScreenTracksNewMediaInsteadOfWaitingForRemovedTiles() {
        bind()
        try {
            rule.runOnUiThread { model.openAlbum(model.albums.single()) }
            waitFor { model.media.size == 30 }
            qa("/qa/release-previews?limit=3")
            val first = qa("/qa/prepend-media").getString("first")
            rule.runOnUiThread { model.refresh() }
            waitFor { !model.mediaLoading && model.media.firstOrNull()?.id == first }
            qa("/qa/release-previews?limit=18")
            waitFor { rule.onAllNodesWithTag("loading-app").fetchSemanticsNodes().isEmpty() && runCatching { rule.onNodeWithTag("album-media-grid").assertIsDisplayed() }.isSuccess }
            rule.onNodeWithTag("album-media-grid").assertIsDisplayed()
            rule.onNodeWithTag("preview-ready-$first", useUnmergedTree = true).assertExists()
            assertFalse(runBlocking { model.api.obj("/qa/preview-counts").getBoolean("released") })
        } finally { qa("/qa/release-previews") }
    }

    @Test fun fullPhotoProgressFollowsStreamedBytesAndWaitsForDecode() {
        bind()
        qa("/qa/release-previews")
        rule.runOnUiThread { model.openAlbum(model.albums.single()) }
        waitFor { rule.onAllNodesWithTag("loading-app").fetchSemanticsNodes().isEmpty() && model.media.isNotEmpty() }
        qa("/qa/stream-images")
        try {
            rule.runOnUiThread { model.viewing = model.media.first() }
            waitFor {
                val node = rule.onAllNodesWithTag("loading-image").fetchSemanticsNodes().singleOrNull()
                val progress = node?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
                progress != null && progress.current > .05f && progress.current < .95f && progress != androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate
            }
            rule.onNodeWithTag("loading-image").assertIsDisplayed()
            waitFor { rule.onAllNodesWithTag("loading-image").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("图片加载失败").assertDoesNotExist()
        } finally { qa("/qa/stream-images?enabled=false") }
    }

    @Test fun reconnectStaysLoadingWhileTheRealSessionRequestIsPending() {
        bind()
        qa("/qa/hold-session")
        try {
            rule.runOnUiThread { model.background(); model.resume() }
            waitFor { model.checkingSession && !model.ready && rule.onAllNodesWithTag("loading-indeterminate-app").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("loading-app").assertIsDisplayed()
            rule.onNodeWithText("重新连接").assertDoesNotExist()
            qa("/qa/release-session")
            waitFor { model.ready && !model.checkingSession }
        } finally { qa("/qa/release-session"); qa("/qa/release-previews") }
    }

    @Test fun failedPreviewsShowRetryInsteadOfBlankTiles() {
        bind()
        qa("/qa/fail-previews")
        rule.runOnUiThread { model.openAlbum(model.albums.single()) }
        waitFor { !model.mediaLoading && model.media.isNotEmpty() }
        waitFor { rule.onAllNodesWithText("重试预览图").fetchSemanticsNodes().isNotEmpty() }
        qa("/qa/fail-previews?enabled=false")
        val beforeRetry = count()
        rule.onAllNodesWithText("重试预览图").onFirst().performClick()
        waitFor { count() > beforeRetry }
        waitFor { rule.onAllNodes(SemanticsMatcher("ready preview") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("preview-ready-") == true }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
