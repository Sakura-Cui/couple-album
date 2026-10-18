package com.sakura.romance

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) { rule.waitUntil(12000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() } }

    @Test fun recipientInboxDeepLinkImageAndIndependentAlbumPassword() {
        val args = InstrumentationRegistry.getArguments()
        val model = rule.activity.model
        rule.runOnUiThread {
            model.vault.url = "https://10.0.2.2:18444"
            model.bind(model.vault.url, args.getString("invite")!!, "收件箱验收")
        }
        rule.waitUntil(12000) { model.ready }
        rule.onAllNodesWithText("跳过").fetchSemanticsNodes().firstOrNull()?.let { rule.onNodeWithText("跳过").performClick() }
        val response = runBlocking { model.api.obj("/qa/letter", "POST") }
        val messageId = response.getString("id")
        rule.runOnUiThread { model.openInbox() }
        waitFor("老婆宝宝，验收信")
        rule.onNodeWithText("• 老婆宝宝，验收信").performClick()
        waitFor("给老婆宝宝的信")
        rule.onNodeWithText("这封信有一张图片，也有我的想念。").assertIsDisplayed()
        rule.waitUntil(15000) { rule.onAllNodesWithContentDescription("信里的图片").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("信里的图片").assertExists()
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
            java.io.File(rule.activity.filesDir, "qa-inbox-detail.png"))
        rule.runOnUiThread { model.closeInbox() }
        rule.runOnUiThread {
            rule.activity.startActivity(Intent(rule.activity.intent).setClass(rule.activity, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("sakura_message_id", messageId))
        }
        waitFor("给老婆宝宝的信")
        rule.runOnUiThread { model.handleMessageIntent(Intent().putExtra("sakura_message_id", "../../private")) }
        assertEquals(messageId, model.letterTarget)
        rule.activityRule.scenario.recreate()
        rule.waitUntil(12000) { model.ready && model.letterDetail?.id == messageId }
        runBlocking { model.api.obj("/api/password", "PUT", json("new_password" to "inbox-qa-password")) }
        rule.runOnUiThread { model.background(); model.resume() }
        rule.waitUntil(12000) { model.albumPasswordEnabled && model.ready }
        waitFor("给老婆宝宝的信")
        val detail = runBlocking { model.api.obj("/api/messages/$messageId") }
        assertFalse(detail.isNull("read_at"))
    }
}
