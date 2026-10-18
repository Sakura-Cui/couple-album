package com.sakura.romance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GroupedLivePhotoTest {
    private val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val launchRule = withSkippedSplash(rule)

    @Test fun pairedLiveAppearsOnceWithBadgeAndHoldPlaysFrames() {
        val model = rule.activity.model
        rule.mainClock.advanceTimeBy(5100)
        rule.runOnUiThread { model.vault.url = "https://10.0.2.2:18448" }
        val invite = runBlocking { model.api.obj("/qa/playback/invite").getString("invite") }
        rule.runOnUiThread { model.bind("https://10.0.2.2:18448", invite, "实况合并回归") }
        rule.waitUntil(20000) { model.ready && !model.busy && model.albums.isNotEmpty() }
        val album = model.albums.single { it.id == "a".repeat(32) }
        assertEquals(2, album.count)
        rule.runOnUiThread { model.openAlbum(album) }
        rule.waitUntil(20000) { !model.mediaLoading && model.media.size == 2 && rule.onAllNodesWithTag("loading-app").fetchSemanticsNodes().isEmpty() }
        assertFalse(model.media.any { it.id == "2".repeat(32) })
        val photo = model.media.single { it.id == "1".repeat(32) }
        assertTrue(photo.live)
        assertEquals("2".repeat(32), photo.motionId)
        try {
            rule.waitUntil(15000) { runCatching { rule.onNodeWithTag("live-photo-badge-${photo.id}", useUnmergedTree = true).assertIsDisplayed() }.isSuccess }
        } catch (error: Throwable) {
            File(rule.activity.filesDir, "qa-live-tree.txt").writeText(rule.onRoot(useUnmergedTree = true).printToString())
            File(rule.activity.filesDir, "qa-live-diagnostic.png").outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            throw error
        }
        File(rule.activity.filesDir, "qa-live-badge.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.runOnUiThread { model.viewing = photo }
        rule.waitUntil(15000) { rule.onAllNodesWithTag("live-photo-control").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("实况 · 按住播放").assertIsDisplayed()
        rule.onNodeWithTag("live-photo-control").performTouchInput { down(center) }
        rule.waitUntil(20000) { rule.onAllNodesWithTag("live-photo-frame").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("live-photo-control").performTouchInput { up() }
        rule.onNodeWithTag("live-photo-frame").assertDoesNotExist()
    }
}
