package com.sakura.romance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AlbumPrivacyTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) = rule.waitUntil(15000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String) {
        rule.mainClock.advanceTimeBy(5000)
        rule.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(rule.activity.filesDir, "qa-$name.png"))
    }

    @Test fun albumProtectionCreateUnlockEditAndBackground() {
        val model = rule.activity.model
        rule.onAllNodesWithText("跳过").fetchSemanticsNodes().firstOrNull()?.let { rule.onNodeWithText("跳过").performClick() }
        rule.runOnUiThread { model.bind((InstrumentationRegistry.getArguments().getString("qa_morning_url") ?: "https://10.0.2.2:18445"), InstrumentationRegistry.getArguments().getString("invite")!!, "相册加密验收") }
        rule.waitUntil(15000) { model.ready && !model.busy && !model.albumsLoading }
        rule.onNodeWithContentDescription("新建相册").performClick()
        rule.onNodeWithText("相册名称").performTextInput("只属于我们的秘密")
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithText("设置密码，至少 6 位").performScrollTo().performTextInput("album-qa-password")
        rule.onNodeWithText("再次输入密码").performScrollTo().performTextInput("album-qa-password")
        rule.onNodeWithText("保存").performScrollTo().performClick()
        rule.waitUntil(15000) { model.albums.any { it.name == "只属于我们的秘密" } && !model.busy }
        val private = model.albums.first { it.name == "只属于我们的秘密" }
        assertTrue(private.protected)
        rule.runOnUiThread { model.createAlbum("公开的小日常") }
        rule.waitUntil(15000) { model.albums.any { it.name == "公开的小日常" } && !model.busy }
        rule.waitUntil(15000) { rule.onAllNodesWithTag("album-${private.id}").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("album-${private.id}").assertIsDisplayed()
        capture("album-privacy-home")
        rule.onNodeWithText(private.name).performClick()
        rule.waitUntil(15000) { model.currentAlbum?.id == private.id && !model.busy }
        rule.onNodeWithContentDescription("相册操作").performClick()
        rule.onNodeWithText("编辑相册").performClick()
        capture("album-privacy-edit")
        rule.onNodeWithText("取消").performClick()
        rule.runOnUiThread { model.background(); model.resume() }
        rule.waitUntil(15000) { model.ready && !model.albumsLoading }
        assertNull(model.currentAlbum)
        assertFalse(model.albumSessionUnlocked)
        rule.onNodeWithText("公开的小日常").performClick()
        rule.waitUntil(15000) { model.currentAlbum?.name == "公开的小日常" }
        rule.onNodeWithText("解锁加密相册").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回相册").performClick()
        waitFor(private.name)
        rule.onNodeWithText(private.name).performClick()
        waitFor("解锁加密相册")
        capture("album-privacy-unlock")
        rule.onNodeWithText("加密相册密码").performTextInput("wrong-password")
        rule.onNodeWithText("解锁").performScrollTo().performClick()
        rule.waitUntil(10000) { !model.busy }
        assertNull(model.currentAlbum)
        rule.onNodeWithText("解锁加密相册").assertIsDisplayed()
        rule.onNodeWithText("加密相册密码").performTextReplacement("album-qa-password")
        rule.onNodeWithText("解锁").performScrollTo().performClick()
        rule.waitUntil(15000) { model.currentAlbum?.id == private.id && !model.busy }
        rule.onNodeWithContentDescription("相册操作").performClick()
        rule.onNodeWithText("编辑相册").performClick()
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithText("保存").performScrollTo().performClick()
        rule.waitUntil(15000) { model.albums.firstOrNull { it.id == private.id }?.protected == false && !model.busy }
        rule.onAllNodesWithText("我们的空间").onFirst().performClick()
        rule.onNodeWithText("共享密码").assertDoesNotExist()
        capture("album-privacy-settings")
        val albums = runBlocking { model.api.obj("/api/session") }
        assertTrue(albums.getBoolean("album_password_enabled"))
        assertFalse(albums.getBoolean("password_enabled"))
    }
}
