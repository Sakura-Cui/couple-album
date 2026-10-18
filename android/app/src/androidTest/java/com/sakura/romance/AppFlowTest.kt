package com.sakura.romance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import android.graphics.Bitmap
import android.view.WindowManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.media3.ui.PlayerView

/** Exercises the installed debug app against the localhost-only QA backend. */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var testDevice: UiDevice
    private val sourceName = "大理的夏天-${System.currentTimeMillis()}"
    private val destinationName = "批量整理验收-${System.currentTimeMillis()}"
    private var originalAnimatorScale = "null"

    @Before fun enableAnimationsForTest() {
        testDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        testDevice.executeShellCommand("pm grant com.sakura.romance.debug android.permission.POST_NOTIFICATIONS")
        testDevice.executeShellCommand("pm grant com.sakura.romance.debug android.permission.ACCESS_COARSE_LOCATION")
        testDevice.executeShellCommand("appops set com.sakura.romance.debug SCHEDULE_EXACT_ALARM allow")
        originalAnimatorScale = testDevice.executeShellCommand("settings get global animator_duration_scale").trim().ifEmpty { "null" }
        testDevice.executeShellCommand("settings put global animator_duration_scale 1")
        android.os.SystemClock.sleep(100)
        rule.activityRule.scenario.recreate()
    }

    @After fun restoreAnimationsAfterTest() {
        if (originalAnimatorScale == "null") testDevice.executeShellCommand("settings delete global animator_duration_scale")
        else testDevice.executeShellCommand("settings put global animator_duration_scale $originalAnimatorScale")
    }

    @Test fun splashFinishesAutomaticallyAndStaysDismissedAfterRecreation() {
        rule.runOnUiThread { org.junit.Assert.assertEquals(0, rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
        waitText("跳过")
        rule.onNodeWithText("SakuYao²").assertIsDisplayed()
        org.junit.Assert.assertEquals("SakuYao²", rule.activity.applicationInfo.loadLabel(rule.activity.packageManager).toString())
        screenshot("brand-splash")
        rule.onNodeWithText("小樱").assertIsDisplayed()
        rule.onNodeWithText("小桃").assertIsDisplayed()
        assertSplashIsMoving()
        rule.mainClock.advanceTimeBy(5_100)
        rule.waitUntil(7000) { rule.onAllNodesWithText("跳过").fetchSemanticsNodes().isEmpty() }
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("跳过").assertDoesNotExist()
    }

    @Test fun reducedMotionShowsStaticPetals() {
        try {
            testDevice.executeShellCommand("settings put global animator_duration_scale 0")
            android.os.SystemClock.sleep(150)
            rule.activityRule.scenario.recreate()
            rule.runOnUiThread { org.junit.Assert.assertEquals(0, rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
            waitText("跳过")
            android.os.SystemClock.sleep(250)
            val changed = changedSplashPixels()
            assertTrue("Reduced-motion splash changed $changed sampled pixels", changed < 10)
        } finally {
            testDevice.executeShellCommand("settings put global animator_duration_scale 1")
        }
    }

    @Test fun tokenCacheFollowsReplacementAndRemoval() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : android.content.ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("qa_vault_cache", mode)
        }
        val prefs = context.getSharedPreferences("private_connection", 0)
        prefs.edit().clear().commit()
        try {
            val vault = Vault(context)
            vault.token = "first-test-token"
            val reloaded = Vault(context)
            org.junit.Assert.assertEquals("first-test-token", reloaded.token)
            // A cached read must not re-enter Keystore or read/decrypt the persisted value.
            prefs.edit().putString("token", "invalid-ciphertext").commit()
            org.junit.Assert.assertEquals("first-test-token", reloaded.token)
            reloaded.token = "replacement-test-token"
            org.junit.Assert.assertEquals("replacement-test-token", reloaded.token)
            org.junit.Assert.assertEquals("replacement-test-token", Vault(context).token)
            reloaded.token = ""
            org.junit.Assert.assertEquals("", reloaded.token)
            org.junit.Assert.assertEquals("", Vault(context).token)
        } finally { prefs.edit().clear().commit() }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun bindCreateRenameSharedPasswordAndResume() {
        rule.runOnUiThread { org.junit.Assert.assertEquals(0, rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
        waitText("跳过")
        rule.mainClock.autoAdvance = false
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).swipe(240, 960, 820, 1180, 12)
        android.os.SystemClock.sleep(1_800)
        screenshot("splash", waitForIdle = false)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("跳过").performClick()
        waitText("空间地址")
        rule.onNodeWithText("SakuYao²").assertIsDisplayed()
        screenshot("connect")
        val invitation = requireNotNull(InstrumentationRegistry.getArguments().getString("invite"))
        rule.onNodeWithText("空间地址").performTextInput("https://10.0.2.2:18444")
        rule.onNodeWithText("一次性邀请凭证").performTextInput(invitation)
        rule.onNodeWithText("这台手机的名字").performTextInput("验收手机")
        rule.onNodeWithText("进入我们的空间").performScrollTo().performClick()
        waitText("我们的相册")
        screenshot("home")
        rule.onNodeWithContentDescription("上传队列").performClick()
        waitText("还没有上传任务")
        rule.onNodeWithText("选好文件，解锁后自动上传。", substring = true).assertDoesNotExist()
        screenshot("queue-empty")
        rule.onNodeWithContentDescription("上传说明").performClick()
        rule.onNodeWithText("选好文件，解锁后自动上传。", substring = true).assertIsDisplayed()
        screenshot("queue-help")
        rule.onNodeWithContentDescription("上传说明").performClick()
        rule.onNodeWithText("选好文件，解锁后自动上传。", substring = true).assertDoesNotExist()
        rule.onNodeWithText("收起").performClick()
        rule.onNodeWithContentDescription("新建相册").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("云南旅行")
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil(20000) { !rule.activity.model.busy && rule.activity.model.albums.any { it.name == "云南旅行" } }
        waitText("云南旅行")
        rule.onNodeWithText("云南旅行").performScrollTo().performClick()
        rule.waitUntil(10000) { rule.activity.model.currentAlbum?.name == "云南旅行" }
        rule.onNodeWithContentDescription("相册操作").performClick()
        rule.onNodeWithText("编辑相册").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement(sourceName)
        rule.onNodeWithText("保存").performClick()
        waitText(sourceName)
        rule.waitUntil(10000) { rule.onAllNodesWithText("编辑相册").fetchSemanticsNodes().isEmpty() }
        screenshot("album")
        rule.onNodeWithContentDescription("上传回忆").performClick()
        val pickerDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        pickerDevice.waitForIdle()
        pickerDevice.pressBack()
        val pickerModel = rule.activity.model
        rule.waitUntil(20000) { pickerModel.ready }
        rule.onNodeWithText("收起").assertDoesNotExist()
        assertTrue(pickerModel.tasks.isEmpty())
        uploadFile("qa-photo.png")
        verifyLoadingStates()
        verifyThumbnailCache()
        val previewModel = rule.activity.model
        runBlocking { previewModel.api.obj("/qa/delays?images=2&fail_images=true", "POST") }
        rule.onNodeWithContentDescription("qa-photo.png").performClick()
        waitLoading("image")
        screenshot("photo-loading")
        waitText("图片加载失败")
        rule.onNodeWithTag("loading-image").assertDoesNotExist()
        runBlocking { previewModel.api.obj("/qa/delays?images=2", "POST") }
        rule.onNodeWithText("重新加载图片").performClick()
        waitLoading("image")
        rule.waitUntil(20000) { rule.onAllNodesWithTag("loading-image").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("图片加载失败").assertDoesNotExist()
        rule.onNode(hasContentDescription("qa-photo.png") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        runBlocking { previewModel.api.obj("/qa/delays", "POST") }
        screenshot("photo")
        rule.onNodeWithText("设为封面").performClick()
        rule.onNodeWithContentDescription("关闭").performClick()
        uploadFile("qa-video.mp4")
        runBlocking { previewModel.api.obj("/qa/delays?images=2", "POST") }
        rule.onNodeWithContentDescription("qa-video.mp4").performClick()
        waitLoading("video")
        screenshot("video-loading")
        lateinit var playerView: PlayerView
        rule.mainClock.autoAdvance = false
        try {
            onView(isAssignableFrom(PlayerView::class.java)).check { view, missing ->
                if (missing != null) throw missing
                playerView = view as PlayerView
            }
            rule.waitUntil(30000) {
                var ready = false
                rule.runOnUiThread { ready = playerView.player!!.playbackState == androidx.media3.common.Player.STATE_READY }
                rule.mainClock.advanceTimeByFrame()
                ready
            }
            rule.runOnUiThread { assertTrue("Video must wait for the success animation", !playerView.player!!.playWhenReady) }
            rule.mainClock.advanceTimeBy(400)
            rule.runOnUiThread { assertTrue("Video played during progress travel", !playerView.player!!.playWhenReady) }
            rule.mainClock.advanceTimeBy(300)
            rule.runOnUiThread { assertTrue("Video played during success animation", !playerView.player!!.playWhenReady) }
            rule.mainClock.advanceTimeBy(600)
            rule.runOnUiThread { assertTrue("Video should resume after completion", playerView.player!!.playWhenReady) }
        } finally { rule.mainClock.autoAdvance = true }
        rule.waitUntil(30000) { rule.onAllNodesWithTag("loading-video").fetchSemanticsNodes().isEmpty() }
        runBlocking { previewModel.api.obj("/qa/delays", "POST") }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        onView(isAssignableFrom(PlayerView::class.java)).check { view, missing ->
            if (missing != null) throw missing
            playerView = view as PlayerView
            playerView.controllerShowTimeoutMs = 0
            playerView.showController()
        }
        rule.waitUntil(20000) {
            var advanced = false
            rule.runOnUiThread { advanced = (playerView.player?.currentPosition ?: 0) >= 1000 }
            advanced
        }
        rule.runOnUiThread { playerView.player!!.pause(); playerView.showController() }
        screenshot("video")
        val seek = device.findObject(By.res("com.sakura.romance.debug:id/exo_progress"))
        assertTrue("Video seek bar is unavailable", seek != null)
        if (seek != null) {
            val bounds = seek.visibleBounds
            device.click(bounds.left + bounds.width() / 4, bounds.centerY())
            rule.waitUntil(10000) {
                var sought = false
                rule.runOnUiThread { sought = playerView.player!!.currentPosition in 1500L..3000L }
                sought
            }
        }
        rule.onNodeWithContentDescription("关闭").performClick()
        screenshot("media")
        val queueModel = rule.activity.model
        rule.runOnUiThread { queueModel.enqueue(listOf(android.net.Uri.parse(queueModel.tasks.first().uri)), "missing-qa-album") }
        rule.waitUntil(20000) { queueModel.tasks.last().status == "上传失败" && !queueModel.uploading }
        rule.onNodeWithContentDescription("上传队列").performClick()
        rule.onNodeWithTag("upload-queue-list").performScrollToNode(hasText("qa-video.mp4"))
        rule.waitUntil(20000) { rule.onAllNodesWithContentDescription("qa-video.mp4 缩略图").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("upload-queue-list").performScrollToNode(hasText("上传失败"))
        rule.waitUntil(20000) { rule.onAllNodesWithContentDescription("qa-photo.png 缩略图").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("upload-queue-list").performScrollToNode(hasText("上传失败")).assertIsDisplayed()
        assertEquals("上传失败", displayUploadTasks(queueModel.tasks).first().status)
        for (task in queueModel.tasks.filter { it.status == "已完成" }) {
            assertTrue(task.completedAt > 0)
            assertEquals(task.completedAt, UploadTask.parse(task.toJson()).completedAt)
        }
        screenshot("queue-thumbnails")
        rule.onNodeWithText("收起").performClick()
        rule.runOnUiThread { queueModel.removeTask(queueModel.tasks.last()) }
        rule.waitUntil(20000) { queueModel.tasks.size == 2 && !queueModel.busy }
        verifyDeferredUploads()
        verifyBatchActions()
        verifyImmediateUploads()
        verifyMediaPaging()
        rule.onNodeWithContentDescription("返回相册").performClick()
        waitText("我们的相册")
        rule.mainClock.advanceTimeBy(5_000)
        screenshot("home")
        rule.onNodeWithText("我们的空间").performClick()
        rule.onNodeWithText("共享密码").assertDoesNotExist()
        rule.onNodeWithText("相册", useUnmergedTree = true).performClick()
        rule.onNodeWithText(sourceName).performScrollTo().performClick()
        rule.waitUntil(10000) { rule.activity.model.currentAlbum?.name == sourceName }
        uploadFile("qa-photo.png", additionalName = "qa-video.mp4")
        verifyBackgroundUploads()
        rule.onNodeWithText("我们的空间").performClick()
        rule.mainClock.advanceTimeBy(5_000)
        rule.onNodeWithText("SakuYao²", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("settings")
        // Real screens with clearly named, empty QA albums for visual review.
        runBlocking {
            listOf("周末小日常", "一起看日落", "和你去看海", "大理的夏天").forEach { name ->
                previewModel.api.obj("/api/albums", "POST", json("name" to name))
            }
        }
        rule.runOnUiThread { previewModel.closeAlbum() }
        rule.waitUntil(20000) { previewModel.albums.any { it.name == "大理的夏天" } && !previewModel.busy }
        rule.onNodeWithText("相册", useUnmergedTree = true).performClick()
        rule.onNodeWithText("共同珍藏").assertDoesNotExist()
        screenshot("review-albums")
        rule.onNodeWithContentDescription("新建相册").performClick()
        screenshot("review-dialog")
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithContentDescription("上传队列").performClick()
        screenshot("review-queue")
        rule.onNodeWithContentDescription("上传说明").performClick()
        screenshot("review-queue-help")
        rule.onNodeWithText("收起").performClick()
        rule.onNodeWithText("我们的空间").performClick()
        screenshot("review-settings")
    }

    private fun waitText(text: String) {
        rule.waitUntil(20000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitLoading(theme: String) {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("loading-$theme").fetchSemanticsNodes().isNotEmpty() }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun mediaSwipeOnlyShowsLoadingWhileContentIsPending() {
        waitText("跳过")
        rule.onNodeWithText("跳过").performClick()
        val model = rule.activity.model
        if (!model.bound) rule.runOnUiThread { model.bind("https://10.0.2.2:18444", requireNotNull(InstrumentationRegistry.getArguments().getString("invite")), "媒体加载验收") }
        rule.waitUntil(20000) { model.ready && !model.busy }
        rule.runOnUiThread { model.createAlbum(sourceName) }
        rule.waitUntil(20000) { !model.busy && model.albums.any { it.name == sourceName } }
        waitText(sourceName)
        rule.onNodeWithText(sourceName).performScrollTo().performClick()
        rule.waitUntil(10000) { rule.activity.model.currentAlbum?.name == sourceName }
        uploadFile("qa-photo.png")
        runBlocking { model.api.obj("/qa/delays?images=2", "POST") }
        rule.onNodeWithContentDescription("qa-photo.png").performClick()
        waitLoading("image")
        rule.waitUntil(20000) { rule.onAllNodesWithTag("loading-image").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasContentDescription("qa-photo.png") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭").performClick()
        runBlocking { model.api.obj("/qa/delays", "POST") }
        uploadFile("qa-video.mp4")
        // Wait for displayed pixels: an absent indicator during the debounce is not a cache hit.
        rule.onNodeWithContentDescription("qa-photo.png").performClick()
        rule.waitUntil(20000) {
            runCatching { rule.onNode(hasContentDescription("qa-photo.png") and hasAnyAncestor(isDialog())).assertIsDisplayed(); true }.getOrDefault(false)
        }
        runBlocking { model.api.obj("/qa/delays?images=2", "POST") }
        lateinit var playerView: PlayerView
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("media-pager").performTouchInput { swipeRight() }
            rule.waitUntil(10000) {
                rule.mainClock.advanceTimeByFrame()
                runCatching {
                    onView(isAssignableFrom(PlayerView::class.java)).check { view, missing ->
                        if (missing != null) throw missing
                        playerView = view as PlayerView
                    }
                }.isSuccess
            }
            rule.onNodeWithTag("video-poster").assertIsDisplayed()
            rule.onNodeWithTag("loading-video").assertDoesNotExist()
            rule.runOnUiThread {
                assertTrue("Controls must stay hidden before the video is ready", !playerView.useController)
                assertTrue("Video must stay paused during the loading debounce", !playerView.player!!.playWhenReady)
            }
            screenshot("video-debounce")
            rule.mainClock.advanceTimeBy(256)
            rule.onNodeWithTag("loading-video").assertIsDisplayed()
            screenshot("video-loading")
            rule.waitUntil(30000) {
                var ready = false
                rule.runOnUiThread { ready = playerView.player!!.playbackState == androidx.media3.common.Player.STATE_READY }
                rule.mainClock.advanceTimeByFrame()
                ready
            }
            rule.runOnUiThread { assertTrue("Video must wait for the success animation", !playerView.player!!.playWhenReady) }
            rule.mainClock.advanceTimeBy(400)
            rule.runOnUiThread { assertTrue("Video played during progress travel", !playerView.player!!.playWhenReady) }
            rule.mainClock.advanceTimeBy(300)
            rule.runOnUiThread { assertTrue("Video played during success animation", !playerView.player!!.playWhenReady) }
            rule.mainClock.advanceTimeBy(600)
            rule.runOnUiThread { assertTrue("Video should resume after completion", playerView.player!!.playWhenReady) }
        } finally { rule.mainClock.autoAdvance = true }
        runBlocking { model.api.obj("/qa/delays?images=5", "POST") }
        // A return swipe must reveal the cached photo without an obligatory loading sequence.
        testDevice.swipe(950, 1100, 100, 1100, 20)
        rule.waitUntil(10000) { model.viewing?.name == "qa-photo.png" }
        rule.mainClock.autoAdvance = false
        try {
            rule.mainClock.advanceTimeBy(500)
            rule.onNodeWithTag("loading-image").assertDoesNotExist()
            rule.onNode(hasContentDescription("qa-photo.png") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        } finally { rule.mainClock.autoAdvance = true }
        val retainedPlayer = playerView.player!!
        repeat(5) { round ->
            rule.onNodeWithTag("media-pager").performTouchInput { swipeRight() }
            rule.waitUntil(10000) { model.viewing?.name == "qa-video.mp4" }
            rule.mainClock.autoAdvance = false
            try {
                repeat(20) {
                    rule.mainClock.advanceTimeBy(32)
                    rule.onNodeWithTag("loading-video").assertDoesNotExist()
                }
                rule.onNodeWithTag("video-poster").assertDoesNotExist()
                rule.runOnUiThread {
                    assertTrue("Round $round recreated a loaded video", playerView.player === retainedPlayer)
                    assertEquals(androidx.media3.common.Player.STATE_READY, retainedPlayer.playbackState)
                    assertEquals("Round $round lost playback intent", round == 0 || round % 2 == 0, retainedPlayer.playWhenReady)
                    if (round % 2 == 0) retainedPlayer.pause() else retainedPlayer.play()
                }
            } finally { rule.mainClock.autoAdvance = true }
            testDevice.swipe(950, 1100, 100, 1100, 20)
            rule.waitUntil(10000) { model.viewing?.name == "qa-photo.png" }
            rule.runOnUiThread { assertTrue("Hidden video must pause", !retainedPlayer.playWhenReady) }
            rule.onNodeWithTag("loading-image").assertDoesNotExist()
        }
        screenshot("photo")
        rule.onNodeWithContentDescription("关闭").performClick()
        rule.waitUntil(5000) {
            var released = false
            rule.runOnUiThread { released = retainedPlayer.playbackState == androidx.media3.common.Player.STATE_IDLE }
            released
        }
        // Force a cache miss to exercise network failure, then retry without exposing controls.
        val cacheSource = StreamCache.factory(rule.activity, model.vault,
            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(model.api.client)).createDataSource()
        val video = model.media.single { it.name == "qa-video.mp4" }
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri(model.api.mediaUrl(video.id)).build()
        cacheSource.cache.removeResource(cacheSource.cacheKeyFactory.buildCacheKey(spec))
        runBlocking { model.api.obj("/qa/delays?fail_images=true", "POST") }
        rule.onNodeWithContentDescription("qa-video.mp4").performClick()
        waitText("视频加载失败，请检查网络或相册授权后重试。")
        rule.onNodeWithTag("loading-video").assertDoesNotExist()
        onView(isAssignableFrom(PlayerView::class.java)).check { view, missing ->
            if (missing != null) throw missing
            playerView = view as PlayerView
        }
        rule.runOnUiThread { assertTrue(!playerView.useController) }
        runBlocking { model.api.obj("/qa/delays?images=2", "POST") }
        rule.onNodeWithText("重试").performClick()
        waitLoading("video")
        rule.runOnUiThread { assertTrue(!playerView.useController && !playerView.player!!.playWhenReady) }
        // Let the retry finish offscreen; returning must not replay an unseen success animation.
        testDevice.swipe(950, 1100, 100, 1100, 20)
        rule.waitUntil(10000) { model.viewing?.name == "qa-photo.png" }
        rule.waitUntil(30000) {
            var ready = false
            rule.runOnUiThread { ready = playerView.player!!.playbackState == androidx.media3.common.Player.STATE_READY }
            ready
        }
        rule.runOnUiThread { assertTrue("An offscreen retry must not autoplay", !playerView.player!!.playWhenReady) }
        rule.onNodeWithTag("media-pager").performTouchInput { swipeRight() }
        rule.waitUntil(10000) { model.viewing?.name == "qa-video.mp4" }
        rule.mainClock.autoAdvance = false
        try {
            repeat(20) {
                rule.mainClock.advanceTimeBy(32)
                rule.onNodeWithTag("loading-video").assertDoesNotExist()
            }
            rule.runOnUiThread { assertTrue("Retry lost autoplay intent", playerView.player!!.playWhenReady) }
        } finally { rule.mainClock.autoAdvance = true }
        screenshot("video-ready")
        rule.onNodeWithContentDescription("关闭").performClick()
        runBlocking { model.api.obj("/qa/delays", "POST") }
    }

    private fun uploadFile(name: String, additionalName: String? = null) {
        val model = rule.activity.model
        val previousIds = model.tasks.map { it.id }.toSet()
        val previousStatusReads = runBlocking { model.api.obj("/qa/request-counts").getInt("upload_status_get") }
        rule.onNodeWithContentDescription("上传回忆").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle()
        if (!device.wait(Until.hasObject(By.text(name)), 5000)) {
            device.wait(Until.findObject(By.desc("Show roots")), 5000)?.click()
            device.waitForIdle()
            device.wait(Until.hasObject(By.text("Downloads")), 5000)
            device.findObject(By.text("Downloads"))?.click()
            device.waitForIdle()
        }
        val document = device.wait(Until.findObject(By.text(name)), 10000)
        assertTrue("QA document not found in system picker: $name", document != null)
        if (additionalName == null) document.click()
        else {
            document.longClick()
            device.waitForIdle()
            val other = device.wait(Until.findObject(By.text(additionalName)), 10000)
            assertTrue("Second QA document not found: $additionalName", other != null)
            other.click()
            device.waitForIdle()
            val open = device.wait(Until.findObject(By.res("com.google.android.documentsui:id/action_menu_select")), 10000)
            assertTrue("Multi-select confirmation not found", open != null)
            open.click()
        }
        val names = listOfNotNull(name, additionalName)
        rule.waitUntil(20000) { model.ready && names.all { selected -> model.tasks.any { it.name == selected && it.id !in previousIds } } }
        // Returning from the picker must open progress automatically, without a queue-menu tap.
        waitText("收起")
        rule.onNodeWithText("收起").assertIsDisplayed()
        // A fast local upload can already be complete when the sheet is inspected.
        rule.waitUntil(20000) {
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isNotEmpty() ||
                names.all { selected -> model.tasks.any { it.name == selected && it.id !in previousIds && it.status == "已完成" } }
        }
        rule.waitUntil(60000) { names.all { selected -> model.tasks.any { it.name == selected && it.id !in previousIds && it.status == "已完成" } } }
        rule.waitUntil(20000) { model.media.any { it.name == name } }
        assertEquals(previousStatusReads, runBlocking { model.api.obj("/qa/request-counts").getInt("upload_status_get") })
        rule.onNodeWithText("收起").performClick()
    }

    private fun verifyLoadingStates() {
        val model = rule.activity.model
        val album = requireNotNull(model.currentAlbum)
        runBlocking { model.api.obj("/qa/delays?lists=2", "POST") }
        rule.runOnUiThread { model.closeAlbum(); model.background(); model.resume() }
        waitText("正在加载相册…")
        rule.onNodeWithText("第一本相册，从这里开始").assertDoesNotExist()
        screenshot("albums-loading")
        rule.waitUntil(20000) { !model.albumsLoading && model.albums.isNotEmpty() }
        rule.runOnUiThread { model.openAlbum(album) }
        waitLoading("app")
        rule.onNodeWithText("这里，留给我们的故事").assertDoesNotExist()
        screenshot("media-loading")
        rule.waitUntil(20000) { !model.mediaLoading && model.media.isNotEmpty() }
        runBlocking { model.api.obj("/qa/delays?fail_lists=true", "POST") }
        rule.runOnUiThread { model.openAlbum(album) }
        waitText("加载失败，请重试")
        rule.onNodeWithText("这里，留给我们的故事").assertDoesNotExist()
        runBlocking { model.api.obj("/qa/delays", "POST") }
        rule.onNodeWithText("重新加载").performClick()
        rule.waitUntil(20000) { !model.mediaLoading && model.media.isNotEmpty() }
    }

    private fun verifyImmediateUploads() {
        val model = rule.activity.model
        val uri = android.net.Uri.parse(model.tasks.first { it.mime.startsWith("image/") }.uri)
        val album = runBlocking { model.api.obj("/api/albums", "POST", json("name" to "优先级验收")) }.getString("id")
        runBlocking { model.api.obj("/qa/delays?receive_delay=0.04", "POST") }
        val before = model.tasks.map { it.id }.toSet()
        rule.runOnUiThread { model.enqueue(listOf(uri, uri, uri), album) }
        rule.waitUntil(30000) { model.tasks.count { it.id !in before } == 3 && model.tasks.any { it.id !in before && it.offset > 0 } }
        val ids = model.tasks.filter { it.id !in before }.map { it.id }
        val originalRemote = model.tasks.first { it.id == ids[0] }.remote
        rule.onNodeWithContentDescription("上传队列").performClick()
        rule.onNodeWithTag("upload-queue-list").performScrollToNode(hasTestTag("upload-now-${ids[2]}"))
        rule.onNodeWithTag("upload-now-${ids[2]}").performClick()
        rule.waitUntil(30000) { model.activeUploadId == ids[2] }
        rule.onNodeWithTag("upload-task-${ids[2]}").assertIsDisplayed()
        val paused = model.tasks.first { it.id == ids[0] }
        assertEquals("已暂停", paused.status)
        assertEquals(paused.offset, paused.displayOffset)
        assertEquals(paused.offset, UploadTask.parse(paused.toJson()).offset)
        // Progress updates must not pull a user away from a manually scrolled task.
        rule.onNodeWithTag("upload-queue-list").performScrollToNode(hasTestTag("upload-task-${ids[1]}"))
        android.os.SystemClock.sleep(500)
        rule.onNodeWithTag("upload-task-${ids[1]}").assertIsDisplayed()
        assertEquals(null, model.tasks.first { it.id == ids[0] }.sentOffset)
        rule.onNodeWithText("收起").performClick()
        rule.waitUntil(120000) { !model.uploading && model.tasks.filter { it.id in ids }.all { it.status == "已完成" } }
        assertEquals(listOf(ids[2], ids[0], ids[1]), model.tasks.filter { it.id in ids }.sortedBy { it.completedAt }.map { it.id })
        assertEquals(originalRemote, model.tasks.first { it.id == ids[0] }.remote)
        val persisted = org.json.JSONArray(File(rule.activity.filesDir, "upload-queue.json").readText())
        assertEquals(ids[2], persisted.getJSONObject(0).getString("id"))

        val nextBefore = model.tasks.map { it.id }.toSet()
        rule.runOnUiThread { model.enqueue(listOf(uri, uri, uri), album) }
        rule.waitUntil(30000) { model.tasks.count { it.id !in nextBefore } == 3 && model.activeUploadId != null }
        val next = model.tasks.filter { it.id !in nextBefore }.map { it.id }
        rule.runOnUiThread { model.uploadNow(next[1]); model.uploadNow(next[2]) }
        rule.waitUntil(30000) { model.activeUploadId == next[2] }
        rule.runOnUiThread { model.pauseUploads() }
        rule.waitUntil(30000) { !model.uploading }
        rule.runOnUiThread { model.uploadNow(next[1]) }
        rule.waitUntil(90000) { !model.uploading && model.tasks.first { it.id == next[1] }.status == "已完成" }
        assertTrue(model.tasks.filter { it.id == next[0] || it.id == next[2] }.none { it.status == "已完成" })
        runBlocking { model.api.obj("/qa/delays", "POST") }
        rule.runOnUiThread { model.startUploads() }
        rule.waitUntil(90000) { !model.uploading && model.tasks.filter { it.id in next }.all { it.status == "已完成" } }
        assertEquals(6, serverMediaIds(model, album).size)
        val savingBefore = model.tasks.map { it.id }.toSet()
        runBlocking { model.api.obj("/qa/delays?complete_delay=3", "POST") }
        rule.runOnUiThread { model.enqueue(listOf(uri, uri), album) }
        rule.waitUntil(30000) { model.tasks.any { it.id !in savingBefore && it.status == "正在保存" } }
        val saving = model.tasks.filter { it.id !in savingBefore }.map { it.id }
        val savingRemote = model.tasks.first { it.id == saving[0] }.remote
        rule.runOnUiThread { model.uploadNow(saving[1]) }
        rule.waitUntil(60000) { !model.uploading && model.tasks.filter { it.id in saving }.all { it.status == "已完成" } }
        assertEquals(listOf(saving[1], saving[0]), model.tasks.filter { it.id in saving }.sortedBy { it.completedAt }.map { it.id })
        assertEquals(savingRemote, model.tasks.first { it.id == saving[0] }.remote)
        assertEquals(8, serverMediaIds(model, album).size)
        runBlocking { model.api.obj("/qa/delays", "POST") }
        runBlocking {
            for (id in serverMediaIds(model, album)) model.api.request("/api/media/$id", "DELETE")
            model.api.request("/api/albums/$album", "DELETE")
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun verifyMediaPaging() {
        val model = rule.activity.model
        val original = requireNotNull(model.currentAlbum)
        val albumId = runBlocking { model.api.obj("/api/albums", "POST", json("name" to "翻页验收")) }.getString("id")
        val image = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val photo = java.io.ByteArrayOutputStream().use { output -> image.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        image.recycle()
        val video = rule.activity.contentResolver.openInputStream(android.net.Uri.parse(model.tasks.first { it.mime.startsWith("video/") }.uri))!!.use { it.readBytes() }
        runBlocking {
            // Newest first: image at 28, video at 29, image at 30, crossing the page boundary.
            for (index in 32 downTo 0) {
                val bytes = if (index == 29) video else photo
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val upload = model.api.obj("/api/uploads", "POST", json("request_id" to java.util.UUID.randomUUID().toString(), "album_id" to albumId,
                    "name" to "paging-$index", "mime" to if (index == 29) "video/mp4" else "image/png", "size" to bytes.size, "sha256" to hash)).getString("id")
                model.api.request("/api/uploads/$upload/chunks?offset=0", "PUT", bytes = bytes)
                var result = model.api.obj("/api/uploads/$upload/complete", "POST")
                while (result.optString("status") == "saving") { kotlinx.coroutines.delay(100); result = model.api.obj("/api/uploads/$upload") }
                assertEquals("complete", result.getString("status"))
            }
        }
        rule.runOnUiThread { model.openAlbum(Album(albumId, "翻页验收", 33, null)) }
        rule.waitUntil(20000) { model.media.size == 30 && !model.mediaLoading }
        runBlocking { model.api.obj("/qa/delays?fail_lists=true", "POST") }
        rule.runOnUiThread { model.viewing = model.media[28] }
        waitText("加载更多失败，点击重试")
        runBlocking { model.api.obj("/qa/delays", "POST") }
        rule.onNodeWithText("加载更多失败，点击重试").performClick()
        rule.waitUntil(20000) { model.media.size == 33 }
        rule.onNodeWithTag("media-pager").performTouchInput { swipeLeft() }
        rule.waitUntil(10000) { model.viewing?.name == "paging-29" }
        lateinit var player: androidx.media3.common.Player
        onView(isAssignableFrom(PlayerView::class.java)).check { view, error ->
            if (error != null) throw error
            player = (view as PlayerView).player!!
        }
        // Drag on the video canvas, above the controller.
        rule.onNodeWithTag("media-pager").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * .85f, height * .2f), androidx.compose.ui.geometry.Offset(width * .15f, height * .2f), 400)
        }
        rule.waitUntil(10000) { model.viewing?.name == "paging-30" }
        rule.runOnUiThread { assertTrue("An outgoing video must pause immediately", !player.playWhenReady) }
        // Keep the adjacent video's buffer so a return swipe does not prepare it again.
        rule.runOnUiThread { assertTrue("Adjacent video was released", player.playbackState != androidx.media3.common.Player.STATE_IDLE) }
        rule.onNodeWithTag("media-pager").performTouchInput { swipeRight() }
        rule.waitUntil(10000) { model.viewing?.name == "paging-29" }
        rule.onNodeWithTag("media-pager").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * .15f, height * .2f), androidx.compose.ui.geometry.Offset(width * .85f, height * .2f), 400)
        }
        rule.waitUntil(10000) { model.viewing?.name == "paging-28" }
        rule.onNodeWithTag("media-pager").performTouchInput {
            pinch(androidx.compose.ui.geometry.Offset(width * .4f, height * .5f), androidx.compose.ui.geometry.Offset(width * .1f, height * .5f),
                androidx.compose.ui.geometry.Offset(width * .6f, height * .5f), androidx.compose.ui.geometry.Offset(width * .9f, height * .5f), 400)
        }
        rule.onNodeWithTag("media-pager").performTouchInput { swipeLeft() }
        assertEquals("paging-28", model.viewing?.name)
        rule.onNodeWithTag("media-pager").performTouchInput {
            pinch(androidx.compose.ui.geometry.Offset(width * .1f, height * .5f), androidx.compose.ui.geometry.Offset(width * .46f, height * .5f),
                androidx.compose.ui.geometry.Offset(width * .9f, height * .5f), androidx.compose.ui.geometry.Offset(width * .54f, height * .5f), 400)
        }
        rule.onNodeWithTag("media-pager").performTouchInput { swipeRight() }
        rule.waitUntil(10000) { model.viewing?.name == "paging-27" }
        val selected = requireNotNull(model.viewing)
        rule.onNodeWithText("设为封面").performClick()
        rule.waitUntil(20000) { !model.busy }
        runBlocking {
            val albums = model.api.array("/api/albums?limit=100")
            val album = (0 until albums.length()).map { albums.getJSONObject(it) }.first { it.getString("id") == albumId }
            assertEquals(selected.id, album.getString("display_cover"))
        }
        rule.onNodeWithText("移动", useUnmergedTree = true).performClick()
        rule.onNodeWithText(original.name).performClick()
        rule.onNodeWithText("确认移动").performClick()
        rule.waitUntil(20000) { model.viewing == null && !model.busy }
        assertTrue(selected.id in serverMediaIds(model, original.id))
        assertTrue(selected.id !in serverMediaIds(model, albumId))
        rule.runOnUiThread { model.viewing = model.media.first() }
        val deleted = requireNotNull(model.viewing).id
        rule.onNodeWithText("删除", useUnmergedTree = true).performClick()
        rule.onNodeWithText("确认删除").performClick()
        rule.waitUntil(20000) { model.viewing == null && !model.busy }
        assertTrue(deleted !in serverMediaIds(model, albumId))
        runBlocking { model.api.request("/api/media/${selected.id}", "DELETE") }
        runBlocking {
            for (id in serverMediaIds(model, albumId)) model.api.request("/api/media/$id", "DELETE")
            model.api.request("/api/albums/$albumId", "DELETE")
        }
        rule.runOnUiThread { model.openAlbum(original) }
        rule.waitUntil(20000) { !model.mediaLoading }
    }

    private fun verifyBackgroundUploads() {
        val model = rule.activity.model
        val uri = android.net.Uri.parse(model.tasks.first().uri)
        val album = runBlocking { model.api.obj("/api/albums", "POST", json("name" to "后台上传验收")) }.getString("id")
        val before = model.tasks.map { it.id }.toSet()
        runBlocking { model.api.obj("/qa/delays?receive_delay=0.08", "POST") }
        rule.runOnUiThread { model.enqueue(listOf(uri, uri), album) }
        rule.waitUntil(20000) { model.uploading && model.tasks.any { it.id !in before && it.remote.isNotEmpty() } }
        assertTrue(model.tasks.last().size > 32L * 1024 * 1024)
        rule.waitUntil(20000) { model.tasks.any { it.id !in before && it.offset > 0 && it.offset < it.size } }
        val notifications = rule.activity.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(notifications.activeNotifications.any { it.id == 1001 })
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        testDevice.sleep()
        try {
            val backgroundSamples = mutableListOf<Pair<Long, Int>>()
            val deadline = System.currentTimeMillis() + 120000
            while ((model.uploading || model.tasks.count { it.id !in before && it.status == "已完成" } != 2) && System.currentTimeMillis() < deadline) {
                val current = model.tasks.firstOrNull { it.id !in before && it.status == "正在上传" }
                val progress = notifications.activeNotifications.firstOrNull { it.id == 1001 }?.notification?.extras?.getInt(android.app.Notification.EXTRA_PROGRESS) ?: -1
                current?.let { backgroundSamples += it.displayOffset to progress }
                android.os.SystemClock.sleep(250)
            }
            File(rule.activity.filesDir, "qa-background-progress.txt").writeText(backgroundSamples.joinToString("\n"))
            assertTrue("Background notification must show intermediate progress: $backgroundSamples", backgroundSamples.any { it.second in 1..999 })
            assertTrue("Displayed bytes must advance in background", backgroundSamples.map { it.first }.distinct().size > 3)
            assertEquals(2, model.tasks.count { it.id !in before && it.status == "已完成" })
            assertTrue(!model.ready && !model.uploading)
            assertTrue(model.api.unlockToken.isEmpty())
            assertTrue(notifications.activeNotifications.none { it.id == 1001 })
        } finally {
            testDevice.wakeUp()
            testDevice.executeShellCommand("wm dismiss-keyguard")
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            runBlocking { model.api.obj("/qa/delays", "POST") }
        }
        // Public albums resume automatically; protected-album unlock is covered by AlbumPrivacyTest.
        rule.waitUntil(20000) { model.ready && !model.busy }
        assertTrue(model.api.unlockToken.isEmpty())
        rule.waitUntil(20000) { model.ready && !model.busy }
        assertEquals(2, serverMediaIds(model, album).size)
        // The system notification's pause action cancels the call cleanly and allows resuming.
        runBlocking { model.api.obj("/qa/delays?chunks=5", "POST") }
        rule.runOnUiThread { model.enqueue(listOf(uri), album) }
        rule.waitUntil(20000) { model.uploading && model.tasks.last().remote.isNotEmpty() }
        val pausedRunId = model.uploadRunId
        rule.activity.startService(android.content.Intent(rule.activity, UploadService::class.java).setAction(UploadService.PAUSE))
        rule.waitUntil(20000) { !model.uploading }
        assertEquals("已暂停", model.tasks.last().status)
        assertEquals("", model.tasks.last().error)
        runBlocking { model.api.obj("/qa/delays", "POST") }
        rule.runOnUiThread { model.startUploads() }
        rule.waitUntil(20000) { model.uploadRunId > pausedRunId }
        rule.runOnUiThread { model.uploadServiceDestroyed(pausedRunId) }
        rule.waitUntil(60000) { !model.uploading && model.tasks.last().status == "已完成" }
        assertEquals(3, serverMediaIds(model, album).size)
        runBlocking { model.api.obj("/qa/delays?chunks=5", "POST") }
        rule.runOnUiThread { model.enqueue(listOf(uri), album) }
        rule.waitUntil(20000) { model.uploading && model.tasks.last().remote.isNotEmpty() }
        android.os.SystemClock.sleep(300)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        // Public albums resume automatically; protected-album unlock is covered by AlbumPrivacyTest.
        rule.waitUntil(20000) { model.ready && !model.busy }
        assertTrue(model.api.unlockToken.isEmpty())
        runBlocking { model.api.obj("/qa/delays", "POST") }
        rule.waitUntil(60000) { !model.uploading && model.tasks.last().status == "已完成" }
        assertTrue(model.ready)
        assertEquals(4, serverMediaIds(model, album).size)
        runBlocking {
            model.api.obj("/qa/delays", "POST")
            for (id in serverMediaIds(model, album)) model.api.request("/api/media/$id", "DELETE")
            model.api.request("/api/albums/$album", "DELETE")
        }
    }

    private fun verifyThumbnailCache() {
        val model = rule.activity.model
        val item = model.media.single { it.name == "qa-photo.png" }
        val request = coil.request.ImageRequest.Builder(rule.activity)
            .data(model.api.mediaUrl(item.id, true)).size(192)
            .memoryCacheKey("qa-thumbnail-${item.id}").build()
        val first = runBlocking { model.images.execute(request) }
        val second = runBlocking { model.images.execute(request) }
        assertTrue(first is coil.request.SuccessResult)
        assertEquals(coil.decode.DataSource.DISK, (first as coil.request.SuccessResult).dataSource)
        assertTrue(second is coil.request.SuccessResult)
        assertEquals(coil.decode.DataSource.MEMORY_CACHE, (second as coil.request.SuccessResult).dataSource)
        val album = requireNotNull(model.currentAlbum)
        rule.onNodeWithContentDescription("返回相册").performClick()
        waitText(album.name)
        rule.onNodeWithText(album.name).performScrollTo().performClick()
        rule.waitUntil(20000) { model.media.any { it.id == item.id } }
        val afterNavigation = runBlocking { model.images.execute(request) }
        assertTrue(afterNavigation is coil.request.SuccessResult)
        assertEquals(coil.decode.DataSource.MEMORY_CACHE, (afterNavigation as coil.request.SuccessResult).dataSource)
        val oldLoader = model.images
        rule.runOnUiThread {
            model.background()
            assertTrue(oldLoader !== model.images)
            assertTrue(oldLoader.memoryCache!!.keys.isEmpty())
            assertTrue(model.images.memoryCache!!.keys.isEmpty())
            model.resume()
        }
        rule.waitUntil(20000) { model.ready && model.media.any { it.id == item.id } }
        val afterLock = runBlocking { model.images.execute(request) }
        assertTrue(afterLock is coil.request.SuccessResult)
        assertEquals(coil.decode.DataSource.DISK, (afterLock as coil.request.SuccessResult).dataSource)
    }

    private fun verifyDeferredUploads() {
        val model = rule.activity.model
        val uri = android.net.Uri.parse(model.tasks.first().uri)
        val album = runBlocking { model.api.obj("/api/albums", "POST", json("name" to "上传时序验收")) }.getString("id")
        var count = model.tasks.size
        rule.runOnUiThread { model.background(); model.enqueue(listOf(uri), album) }
        rule.waitUntil(20000) { model.tasks.size == count + 1 && !model.busy }
        assertTrue(!model.ready && !model.uploading)
        rule.runOnUiThread { model.resume() }
        rule.waitUntil(60000) { model.tasks.last().status == "已完成" }
        assertEquals(1, serverMediaIds(model, album).size)

        // Explicit pause must survive session recovery and selection of another file.
        count = model.tasks.size
        rule.runOnUiThread { model.background(); model.enqueue(listOf(uri), album) }
        rule.waitUntil(20000) { model.tasks.size == count + 1 && !model.busy }
        val pausedId = model.tasks.last().id
        rule.runOnUiThread { model.pauseUploads(); model.resume() }
        rule.waitUntil(20000) { model.ready }
        rule.runOnUiThread { model.enqueue(listOf(uri), album) }
        rule.waitUntil(60000) { model.tasks.size == count + 2 && model.tasks.last().status == "已完成" && !model.uploading }
        assertEquals("", model.tasks.single { it.id == pausedId }.remote)
        assertEquals(2, serverMediaIds(model, album).size)
        rule.runOnUiThread { model.startUploads() }
        rule.waitUntil(60000) { model.tasks.single { it.id == pausedId }.status == "已完成" && !model.uploading }
        assertEquals(3, serverMediaIds(model, album).size)
        runBlocking {
            for (id in serverMediaIds(model, album)) model.api.request("/api/media/$id", "DELETE")
            model.api.request("/api/albums/$album", "DELETE")
        }
        rule.runOnUiThread { model.refresh() }
        rule.waitUntil(20000) { !model.busy }
    }

    private fun verifyBatchActions() {
        val model = rule.activity.model
        val source = requireNotNull(model.currentAlbum)
        val ids = model.media.map { it.id }.toSet()
        assertEquals(2, ids.size)
        rule.onNodeWithText("多选").performClick()
        rule.onNodeWithText("移动到相册").assertIsNotEnabled()
        rule.onNodeWithText("删除所选").assertIsNotEnabled()
        rule.onNodeWithContentDescription("qa-photo.png").performClick()
        rule.onNodeWithText("已选 1 项").assertIsDisplayed()
        rule.onNodeWithContentDescription("qa-photo.png").performClick()
        rule.onNodeWithText("已选 0 项").assertIsDisplayed()
        rule.onNodeWithText("全选已加载").performClick()
        rule.onNodeWithText("已选 2 项").assertIsDisplayed()
        screenshot("selection")
        rule.onNodeWithText("删除所选").performClick()
        waitText("删除选中的 2 项回忆？")
        rule.onNodeWithText("删除选中的 2 项回忆？").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        assertEquals(ids, serverMediaIds(model, source.id))
        rule.onNodeWithContentDescription("取消多选").performClick()
        rule.onNodeWithContentDescription("qa-photo.png").performTouchInput { longClick() }
        rule.onNodeWithText("已选 1 项").assertIsDisplayed()
        rule.onNodeWithContentDescription("取消多选").performClick()
        rule.onNodeWithContentDescription("返回相册").performClick()
        rule.onNodeWithContentDescription("新建相册").performClick()
        rule.onNode(hasSetTextAction()).performTextInput(destinationName)
        rule.onNodeWithText("保存").performClick()
        waitText(destinationName)
        rule.waitUntil(20000) { !model.busy && model.albums.any { it.name == destinationName } }
        val destination = model.albums.single { it.name == destinationName }
        rule.onNodeWithText(source.name).performClick()
        rule.waitUntil(20000) { model.media.size == 2 && !model.busy }
        rule.onNodeWithText("多选").performClick()
        rule.onNodeWithText("全选已加载").performClick()
        rule.runOnUiThread { model.moveSelectedMedia(destination.copy(id = "missing-qa-album")) }
        rule.waitUntil(20000) { !model.managingMedia }
        assertEquals(ids, model.selectedMediaIds)
        assertEquals(ids, serverMediaIds(model, source.id))
        rule.onNodeWithText("移动到相册").performClick()
        rule.onNodeWithText(destination.name).performClick()
        rule.onNodeWithText("确认移动").performClick()
        rule.waitUntil(20000) { !model.managingMedia && model.media.isEmpty() }
        assertEquals(emptySet<String>(), serverMediaIds(model, source.id))
        assertEquals(ids, serverMediaIds(model, destination.id))
        assertEquals(0, model.albums.single { it.id == source.id }.count)
        assertEquals(2, model.albums.single { it.id == destination.id }.count)
        rule.onNodeWithContentDescription("返回相册").performClick()
        rule.onNodeWithText(destination.name).performClick()
        rule.waitUntil(20000) { model.media.size == 2 && !model.busy }
        rule.onNodeWithText("多选").performClick()
        rule.onNodeWithText("全选已加载").performClick()
        rule.onNodeWithText("删除所选").performClick()
        rule.onNodeWithText("确认删除").performClick()
        rule.waitUntil(20000) { !model.managingMedia && model.media.isEmpty() }
        assertEquals(emptySet<String>(), serverMediaIds(model, destination.id))
        assertEquals(0, model.albums.single { it.id == destination.id }.count)
        rule.onNodeWithContentDescription("返回相册").performClick()
        rule.onNodeWithText(source.name).performClick()
    }

    private fun serverMediaIds(model: AppModel, albumId: String): Set<String> = runBlocking {
        val items = model.api.array("/api/albums/$albumId/media?limit=100")
        (0 until items.length()).map { items.getJSONObject(it).getString("id") }.toSet()
    }

    private fun screenshot(name: String, waitForIdle: Boolean = true) {
        if (waitForIdle) rule.waitForIdle()
        android.os.SystemClock.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(rule.activity.filesDir, "qa-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun assertSplashIsMoving() {
        assertTrue("Splash particles did not move between frames", changedSplashPixels() > 40)
    }

    private fun changedSplashPixels(): Int {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val first = requireNotNull(automation.takeScreenshot())
        android.os.SystemClock.sleep(260)
        val second = requireNotNull(automation.takeScreenshot())
        var changed = 0
        for (y in 100 until minOf(first.height, second.height) - 100 step 16) {
            for (x in 0 until minOf(first.width, second.width) step 16) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
            }
        }
        first.recycle()
        second.recycle()
        return changed
    }
}
