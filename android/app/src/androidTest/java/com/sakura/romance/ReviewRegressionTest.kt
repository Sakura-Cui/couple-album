package com.sakura.romance

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on sakura-test, with qa_review_server.py and qa-photo.png in Downloads. */
@RunWith(AndroidJUnit4::class)
class ReviewRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = rule.activity.model
    private fun waitFor(check: () -> Boolean) = rule.waitUntil(20000, check)
    private fun qa(path: String) = runBlocking { model.api.obj(path, "POST") }

    @Before fun bind() {
        rule.mainClock.advanceTimeBy(5100)
        rule.runOnUiThread { model.vault.url = "https://10.0.2.2:18447" }
        val invite = qa("/qa/reset").getString("invite")
        rule.runOnUiThread { model.bind("https://10.0.2.2:18447", invite, "审查回归") }
        waitFor { model.ready && !model.busy && !model.albumsLoading }
    }

    @Test fun rebindAcceptsLowerVersionInTheSameProcess() {
        assertEquals(5, runBlocking { model.api.obj("/api/session") }.getInt("version"))
        val invite = qa("/qa/rebind").getString("invite")
        rule.runOnUiThread { model.refresh() }
        waitFor { !model.bound && !model.busy }
        rule.runOnUiThread { model.bind("https://10.0.2.2:18447", invite, "重新绑定") }
        waitFor { model.ready && !model.busy && !model.albumsLoading }
        assertEquals("重新绑定", model.deviceName)
        assertEquals(0, runBlocking { model.api.obj("/api/session") }.getInt("version"))
    }

    @Test fun oldRequestIsCancelledWhenConnectionChanges() = runBlocking {
        val old = async(Dispatchers.IO) { model.api.obj("/qa/slow", "POST") }
        withTimeout(10000) {
            while (!qa("/qa/slow-started").getBoolean("started")) delay(50)
        }
        rule.runOnUiThread { model.api.resetConnection() }
        try { old.await(); fail("Old response must be discarded") }
        catch (_: CancellationException) { }
        assertTrue(model.api.obj("/api/session").has("version"))
    }

    @Test fun pagingAfterDeletionDoesNotSkipMedia() {
        val album = qa("/qa/media").getString("album_id")
        rule.runOnUiThread { model.refresh() }
        waitFor { !model.busy && model.albums.any { it.id == album } }
        rule.runOnUiThread { model.openAlbum(model.albums.single { it.id == album }) }
        waitFor { !model.mediaLoading && model.media.size == 30 }
        val expected = (0 until 61).map { "%032x".format(it) }.toSet()
        val deleted = model.media.first().id
        runBlocking { model.api.request("/api/media/$deleted", "DELETE") }
        rule.runOnUiThread { model.moreMedia() }
        waitFor { !model.mediaLoading && !model.busy }
        rule.runOnUiThread { model.moreMedia() }
        waitFor { !model.mediaLoading && !model.busy }
        assertTrue("Existing media was skipped", model.media.map { it.id }.toSet().containsAll(expected - deleted))
        assertFalse(model.mediaMore)
    }

    @Test fun invalidUriDoesNotDiscardOtherSelectedFiles() {
        rule.runOnUiThread { model.createAlbum("文件异常回归") }
        waitFor { !model.busy && model.albums.any { it.name == "文件异常回归" } }
        val album = model.albums.single { it.name == "文件异常回归" }
        rule.runOnUiThread { model.openAlbum(album) }
        waitFor { !model.busy && !model.mediaLoading }
        rule.onNodeWithContentDescription("上传回忆").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        if (!device.wait(Until.hasObject(By.text("qa-photo.png")), 5000)) {
            device.wait(Until.findObject(By.desc("Show roots")), 5000)?.click()
            device.wait(Until.findObject(By.text("Downloads")), 5000)?.click()
        }
        val file = device.wait(Until.findObject(By.text("qa-photo.png")), 10000)
        assertNotNull("QA photo missing", file)
        file.click()
        waitFor { model.tasks.any { it.status == "已完成" } && !model.uploading && !model.busy }
        val original = model.tasks.first()
        val good = Uri.parse(original.uri)
        val invalid = Uri.parse("content://review.invalid/missing")
        val count = model.tasks.size
        rule.runOnUiThread { model.background(); model.enqueue(listOf(good, invalid, good), album.id) }
        waitFor { !model.busy }
        assertEquals("Valid files must survive one invalid URI", count + 2, model.tasks.size)
        assertTrue(rule.activity.contentResolver.persistedUriPermissions.any { it.uri == good && it.isReadPermission })
        assertEquals(2, model.tasks.count { it.id != original.id && it.uri == original.uri })
    }
}
