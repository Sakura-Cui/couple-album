package com.sakura.romance

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SignatureHeaderTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun saveCancelRestoreAndDraftSurviveRecreation() {
        var generation by mutableIntStateOf(0)
        var connected by mutableStateOf(true)
        var signature by mutableStateOf("")
        val restoration = StateRestorationTester(rule)
        restoration.setContent { SakuraTheme { key(generation) { Column { SignatureHeader(); DeviceProfileCard("测试机", connected, signature) { value, done -> signature = value; done() } } } } }
        rule.onNodeWithContentDescription("已连接").assertIsDisplayed()
        rule.runOnIdle { connected = false }
        rule.onNodeWithContentDescription("连接失败").assertIsDisplayed()
        rule.onNodeWithText("已连接我们的私人空间").assertDoesNotExist()
        rule.runOnIdle { connected = true }
        rule.onNodeWithContentDescription("已连接").assertIsDisplayed()
        rule.onNodeWithTag("space-header").assertHasNoClickAction()
        rule.onNodeWithTag("device-signature").performClick()
        rule.onNodeWithTag("signature-input").performTextReplacement("老婆宝宝，每天都爱你 💕")
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("signature-input").assertTextContains("老婆宝宝，每天都爱你 💕")
        rule.onNodeWithText("保存").performScrollTo().performClick()
        rule.runOnIdle { generation++ }
        rule.onNodeWithText("老婆宝宝，每天都爱你 💕").assertIsDisplayed()
        assertEquals("老婆宝宝，每天都爱你 💕", signature)
        rule.onNodeWithTag("device-signature").performClick()
        rule.onNodeWithTag("signature-input").performTextReplacement("取消不应该保存")
        rule.onNodeWithText("取消").performScrollTo().performClick()
        rule.onNodeWithText("老婆宝宝，每天都爱你 💕").assertIsDisplayed()
        rule.onNodeWithTag("device-signature").performClick()
        rule.onNodeWithTag("signature-input").performTextReplacement(" ")
        rule.onNodeWithText("保存").performScrollTo().performClick()
        rule.onNodeWithText("我们的空间").assertIsDisplayed()
        rule.onNodeWithText("写一句属于自己的话").assertIsDisplayed()
        rule.onNodeWithTag("space-header").assertHasNoClickAction()
    }

    @Test fun compactWidthLargeFontLongTextAndLimit() {
        val longText = "老婆宝宝每天都爱你和你一起慢慢变老呀💕💕"
        var fontScale by mutableFloatStateOf(1f)
        assertEquals(20, longText.codePointCount(0, longText.length))
        var signature by mutableStateOf(longText)
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                SakuraTheme {
                    RomanceScene {
                        Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(24.dp).testTag("signature-preview")) {
                            SignatureHeader()
                            Spacer(Modifier.height(24.dp))
                            DeviceProfileCard("测试机（优先移除一次性邀请凭证）", true, signature) { value, done -> signature = value; done() }
                        }
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(longText, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        assertTrue(layouts.single().lineCount in 2..3)
        rule.onNodeWithTag("signature-preview").captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.filesDir, "qa-device-signature-20.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.runOnIdle { fontScale = 2f }
        layouts.clear()
        rule.onNodeWithText(longText, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        assertTrue(layouts.single().lineCount > 2)
        rule.onNodeWithContentDescription("编辑个性签名", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("signature-preview").captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.filesDir, "qa-device-signature-large-font.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.onNodeWithTag("device-signature").performClick()
        rule.onNodeWithTag("signature-input").performTextReplacement("💕".repeat(21))
        rule.onNodeWithText("保存").assertIsNotEnabled()
        rule.onNodeWithTag("signature-input").performTextReplacement("💕".repeat(20))
        rule.onNodeWithText("保存").assertIsEnabled()
        rule.onNodeWithText("保存").performScrollTo().assertIsDisplayed()
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(context.filesDir, "qa-device-signature-dialog.png"))
        rule.onNodeWithText("保存").performClick()
        rule.onNodeWithText("💕".repeat(20)).assertExists()
    }
}
