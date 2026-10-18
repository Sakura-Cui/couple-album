package com.sakura.romance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Visual review on the already bound, dedicated local QA emulator. */
@RunWith(AndroidJUnit4::class)
class VisualReviewTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun captureFinalScreensAndLargeText() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val originalScale = device.executeShellCommand("settings get system font_scale").trim()
        try {
            openApp()
            capture("review-albums")
            rule.onNodeWithContentDescription("新建相册").performClick()
            capture("review-dialog")
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithContentDescription("上传队列").performClick()
            rule.onNodeWithText("已珍藏").assertDoesNotExist()
            rule.onNodeWithText("选好文件，解锁后自动上传。", substring = true).assertDoesNotExist()
            capture("review-queue")
            rule.onNodeWithContentDescription("上传说明").performClick()
            rule.onNodeWithText("选好文件，解锁后自动上传。", substring = true).assertIsDisplayed()
            capture("review-queue-help")
            rule.onNodeWithText("收起").performClick()
            rule.onNodeWithText("我们的空间").performClick()
            capture("review-settings")

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, 512, 512)
            icon.draw(Canvas(bitmap))
            File(context.filesDir, "qa-icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()

            device.executeShellCommand("settings put system font_scale 1.3")
            android.os.SystemClock.sleep(1000)
            rule.activityRule.scenario.recreate()
            openApp()
            capture("review-large-text")
            rule.onNodeWithText("我们的空间").performClick()
            rule.onNodeWithText("上传队列").performScrollTo()
            device.swipe(device.displayWidth / 2, device.displayHeight * 2 / 3,
                device.displayWidth / 2, device.displayHeight / 3, 20)
            rule.onNodeWithText("上传队列").performClick()
            rule.waitUntil(20000) { rule.onAllNodesWithContentDescription("上传说明").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("上传说明").performClick()
            rule.onNodeWithText("收起").assertIsDisplayed()
            rule.onNodeWithText("选好文件，解锁后自动上传。", substring = true).assertIsDisplayed()
            capture("review-large-queue")
        } finally {
            device.executeShellCommand(if (originalScale == "null") "settings delete system font_scale" else "settings put system font_scale $originalScale")
        }
    }

    @Test fun queueLoadingKeepsDismissVisibleWithLargeText() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val originalScale = device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("settings put system font_scale 1.3")
            rule.activityRule.scenario.recreate()
            openApp()
            rule.onNodeWithContentDescription("上传队列").performClick()
            rule.onNodeWithText("收起").assertIsDisplayed()
            rule.onNodeWithContentDescription("上传说明").performClick()
            capture("loading-compact-help")
            rule.onNodeWithText("收起").assertIsDisplayed().performClick()
            rule.onNodeWithTag("loading-queue").assertDoesNotExist()
        } finally {
            device.executeShellCommand(if (originalScale == "null") "settings delete system font_scale" else "settings put system font_scale $originalScale")
        }
    }

    private fun openApp() {
        rule.runOnUiThread { org.junit.Assert.assertEquals(0, rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
        if (rule.onAllNodesWithText("跳过").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithText("跳过").performClick()
        if (!rule.activity.model.bound) {
            val args = InstrumentationRegistry.getArguments()
            rule.runOnUiThread { rule.activity.model.bind(requireNotNull(args.getString("qa_visual_url")),
                requireNotNull(args.getString("invite")), "大字体回归") }
        }
        rule.waitUntil(20000) { rule.activity.model.ready }
        rule.waitForIdle()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        android.os.SystemClock.sleep(400)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.filesDir, "qa-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
