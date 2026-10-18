package com.sakura.romance

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AlbumActionsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun standaloneQueueAndVisibleBatchActionsOnCompactScreens() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val model = rule.activity.model
        val originalFont = device.executeShellCommand("settings get system font_scale").trim()
        val originalSize = device.executeShellCommand("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val sourceName = "布局验收源相册"
        val destinationName = "布局验收目标相册"
        var source = ""
        var destination = ""
        try {
            rule.waitUntil(15000) { rule.onAllNodesWithText("跳过").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("跳过").performClick()
            device.executeShellCommand("pm grant ${rule.activity.packageName} android.permission.POST_NOTIFICATIONS")
            device.executeShellCommand("pm grant ${rule.activity.packageName} android.permission.ACCESS_COARSE_LOCATION")
            device.executeShellCommand("appops set ${rule.activity.packageName} SCHEDULE_EXACT_ALARM allow")
            rule.runOnUiThread { model.bind("https://10.0.2.2:18444", requireNotNull(InstrumentationRegistry.getArguments().getString("invite")), "布局验收") }
            rule.waitUntil(20000) { model.ready && !model.busy }
            runBlocking {
                source = model.api.obj("/api/albums", "POST", json("name" to sourceName)).getString("id")
                destination = model.api.obj("/api/albums", "POST", json("name" to destinationName)).getString("id")
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(240, 175, 200)) }
                val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
                bitmap.recycle()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                repeat(21) { index ->
                    val id = model.api.obj("/api/uploads", "POST", json("request_id" to UUID.randomUUID().toString(), "album_id" to source,
                        "name" to "layout-$index.png", "mime" to "image/png", "size" to bytes.size, "sha256" to hash)).getString("id")
                    model.api.request("/api/uploads/$id/chunks?offset=0", "PUT", bytes = bytes)
                    var result = model.api.obj("/api/uploads/$id/complete", "POST")
                    while (result.optString("status") == "saving") { kotlinx.coroutines.delay(50); result = model.api.obj("/api/uploads/$id") }
                }
            }
            rule.runOnUiThread { model.refresh() }
            rule.waitUntil(20000) { !model.busy && model.albums.any { it.id == source } }
            rule.runOnUiThread { model.openAlbum(model.albums.first { it.id == source }) }
            rule.waitUntil(20000) { !model.mediaLoading && model.media.size == 21 }
            rule.onNodeWithContentDescription("上传队列").assertIsDisplayed().performClick()
            rule.onNodeWithText("上传回忆").assertIsDisplayed()
            rule.onNodeWithText("收起").performClick()
            rule.onNodeWithContentDescription("相册操作").performClick()
            rule.onNodeWithText("编辑相册").assertIsDisplayed()
            rule.onNodeWithText("上传队列").assertDoesNotExist()
            rule.onNodeWithText("删除相册").assertDoesNotExist()
            device.pressBack()
            rule.onNodeWithText("多选").performClick()
            for (item in model.media.take(4)) rule.onNodeWithContentDescription(item.name).performClick()
            rule.onNodeWithText("已选 4 项").assertIsDisplayed()
            rule.onNodeWithText("移动到相册").assertIsDisplayed().assertIsEnabled()
            rule.onNodeWithText("删除所选").assertIsDisplayed().assertIsEnabled()
            capture("album-selection")
            rule.onNodeWithText("移动到相册").performClick()
            rule.onNodeWithTag("move-album-$destination").performClick()
            capture("move-album")
            rule.onNodeWithText("取消").performClick()

            // A short 320dp-wide viewport reproduces cropped desktop Android windows.
            device.executeShellCommand("wm size 840x1400")
            device.executeShellCommand("settings put system font_scale 1.3")
            rule.activityRule.scenario.recreate()
            rule.waitUntil(20000) { model.ready && !model.mediaLoading }
            if (!model.selecting) rule.runOnUiThread { model.beginSelection(); model.media.take(4).forEach { model.toggleSelection(it.id) } }
            rule.onNodeWithText("已选 4 项").assertIsDisplayed()
            val move = rule.onNodeWithText("移动到相册").assertIsDisplayed()
            val delete = rule.onNodeWithText("删除所选").assertIsDisplayed()
            val count = rule.onNodeWithText("已选 4 项").fetchSemanticsNode().boundsInRoot
            assertTrue(move.fetchSemanticsNode().boundsInRoot.top >= count.top)
            assertTrue(delete.fetchSemanticsNode().boundsInRoot.bottom < 1400)
            val pixels = rule.onNodeWithTag("selection-actions").captureToImage().toPixelMap()
            val background = pixels[4, pixels.height / 2]
            assertTrue("Action bar must cover the underlying photos", kotlin.math.abs(background.red - WarmWhite.red) < .03f &&
                kotlin.math.abs(background.green - WarmWhite.green) < .03f && kotlin.math.abs(background.blue - WarmWhite.blue) < .03f)
            rule.onNodeWithContentDescription("上传队列").assertIsDisplayed().performClick()
            rule.onNodeWithText("收起").performClick()
            rule.onNodeWithText("已选 4 项").assertIsDisplayed()
            capture("album-selection-compact")
            rule.onNodeWithText("移动到相册").performClick()
            rule.onNodeWithText("确认移动").assertIsNotEnabled()
            rule.onNodeWithTag("move-album-$destination").performClick()
            rule.onNodeWithTag("move-album-$destination").assertIsSelected()
            assertEquals(0, mediaIds(model, destination).size)
            rule.onNodeWithText("确认移动").assertIsEnabled()
            capture("move-album-compact")
            rule.onNodeWithText("确认移动").performClick()
            rule.waitUntil(20000) { !model.managingMedia && model.media.size == 17 }
            assertEquals(4, mediaIds(model, destination).size)
            rule.runOnUiThread { model.openAlbum(model.albums.first { it.id == destination }) }
            rule.waitUntil(20000) { !model.mediaLoading && model.media.size == 4 }
            rule.onNodeWithText("多选").performClick()
            rule.onNodeWithText("全选已加载").performClick()
            rule.onNodeWithText("删除所选").performClick()
            rule.onNodeWithText("确认删除").performClick()
            rule.waitUntil(20000) { !model.managingMedia && !model.mediaLoading && model.media.isEmpty() }
            assertEquals(0, mediaIds(model, destination).size)
            rule.onNodeWithContentDescription("相册操作").performClick()
            rule.onNodeWithText("删除相册").assertIsDisplayed().performClick()
            rule.onNodeWithText("删除这个相册？").assertIsDisplayed()
            rule.onNodeWithText("取消").performClick()
        } finally {
            try { runBlocking {
                for (album in listOf(source, destination).filter { it.isNotEmpty() }) {
                    for (id in mediaIds(model, album)) model.api.request("/api/media/$id", "DELETE")
                    model.api.request("/api/albums/$album", "DELETE")
                }
            } } finally {
                device.executeShellCommand("settings put system font_scale " + if (originalFont == "null") "1.0" else originalFont)
                device.executeShellCommand("wm size " + (originalSize ?: "reset"))
            }
        }
    }

    private fun mediaIds(model: AppModel, album: String) = runBlocking {
        val list = model.api.array("/api/albums/$album/media?limit=100")
        (0 until list.length()).map { list.getJSONObject(it).getString("id") }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        android.os.SystemClock.sleep(500)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(rule.activity.filesDir, "qa-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
