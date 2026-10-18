package com.sakura.romance

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import java.io.File
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose screens and local APIs. All media and messages are demo fixtures. */
@RunWith(AndroidJUnit4::class)
class ShowcaseFlowTest {
    private val activity = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules = withSkippedSplash(activity)
    private val model get() = activity.activity.model
    private val baseUrl get() = InstrumentationRegistry.getArguments().getString("url") ?: "https://10.0.2.2:18444"

    @Test fun capturePublicShowcase() {
        val invite = requireNotNull(InstrumentationRegistry.getArguments().getString("invite"))
        activity.runOnUiThread { model.bind(baseUrl, invite, "公开演示设备") }
        activity.waitUntil(30000) { model.ready && !model.albumsLoading && model.albums.size == 4 }
        activity.waitUntil(10000) { activity.onAllNodesWithText("跳过").fetchSemanticsNodes().isEmpty() }
        capture("01-albums")
        activity.onNodeWithContentDescription("新建相册").performClick()
        capture("01b-create-album")
        activity.onNodeWithText("取消").performClick()

        activity.runOnUiThread { model.openAlbum(model.albums.first { it.protected }) }
        activity.onNodeWithText("解锁加密相册").assertIsDisplayed()
        capture("02-album-unlock")
        activity.onNodeWithText("取消").performClick()

        activity.runOnUiThread { model.openAlbum(model.albums.first { it.name == "甜蜜日常" }) }
        activity.waitUntil(30000) { !model.mediaLoading && model.media.size == 3 }
        capture("03-media-grid", 2500)
        activity.runOnUiThread { model.beginSelection(); model.toggleSelection(model.media.first().id) }
        capture("03b-batch-actions")
        activity.runOnUiThread { model.clearSelection() }

        localPost("/showcase/delay?seconds=3")
        activity.runOnUiThread { model.viewing = model.media.first { !it.video } }
        activity.waitForIdle()
        android.os.SystemClock.sleep(450)
        capture("04-image-loading", 0)
        localPost("/showcase/delay?seconds=0")
        capture("05-image-viewer", 4500)
        activity.runOnUiThread { model.viewing = null }
        activity.waitForIdle()

        activity.runOnUiThread { model.viewing = model.media.first { it.video } }
        activity.waitUntil(30000) { activity.onAllNodesWithTag("video-frame").fetchSemanticsNodes().isNotEmpty() }
        android.os.SystemClock.sleep(1500)
        onView(isAssignableFrom(androidx.media3.ui.PlayerView::class.java)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(androidx.media3.ui.PlayerView::class.java)
            override fun getDescription() = "Pause the demo and show native video controls"
            override fun perform(controller: UiController, view: android.view.View) {
                (view as androidx.media3.ui.PlayerView).apply { player?.pause(); showController() }
                controller.loopMainThreadUntilIdle()
            }
        })
        capture("06-video-player", 500)
        activity.onNodeWithTag("video-quality").performClick()
        capture("06b-video-quality", 500)
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        activity.runOnUiThread { model.viewing = null; model.closeAlbum() }
        activity.waitUntil(20000) { !model.albumsLoading }

        // Fixed paused/finished transfer fixtures exercise the actual queue renderer.
        // They never send uploads; uploaded-byte counters here are presentation fixtures.
        activity.runOnUiThread {
            val field = AppModel::class.java.getDeclaredField("tasks\$delegate").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = field.get(model) as MutableState<List<UploadTask>>
            val aid = model.albums.first { !it.protected }.id
            state.value = listOf(
                UploadTask("showcase-paused", "", aid, "一起出发的视频.mp4", "video/mp4", 100_000_000,
                    offset = 45_000_000, status = "已暂停"),
                UploadTask("showcase-complete", "", aid, "今天的心动.png", "image/png", 2_000_000,
                    offset = 2_000_000, status = "已完成", completedAt = 1_800_000_000_000))
        }
        activity.onNodeWithContentDescription("上传队列").performClick()
        capture("07-upload-queue")
        activity.onNodeWithText("收起").performClick()
        activity.onNodeWithText("我们的空间").performClick()
        capture("08-settings")

        localPost("/showcase/letter")
        activity.runOnUiThread { model.openInbox() }
        activity.waitUntil(20000) { !model.lettersLoading && model.letters.isNotEmpty() }
        capture("09-inbox")
        activity.runOnUiThread { model.openLetter(model.letters.first().id) }
        activity.waitUntil(20000) { model.letterDetail != null }
        capture("09b-letter-detail")
    }

    private fun localPost(path: String) {
        model.api.client.newCall(Request.Builder().url("$baseUrl$path")
            .post("{}".toRequestBody()).build()).execute().use { check(it.isSuccessful) }
    }

    private fun capture(name: String, delay: Long = 1200) {
        activity.waitForIdle()
        android.os.SystemClock.sleep(delay)
        activity.mainClock.advanceTimeBy(800)
        activity.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir("showcase"), "showcase-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
