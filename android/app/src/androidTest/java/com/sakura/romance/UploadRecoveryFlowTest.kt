package com.sakura.romance

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class UploadRecoveryFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = rule.activity.model
    private fun waitUntil(check: () -> Boolean) = rule.waitUntil(60000, check)
    @Suppress("UNCHECKED_CAST")
    private fun queue(items: List<UploadTask>) = rule.runOnUiThread {
        val field = AppModel::class.java.getDeclaredField("tasks\$delegate").apply { isAccessible = true }
        (field.get(model) as MutableState<List<UploadTask>>).value = items
    }
    private fun faults(vararg entries: Pair<String, Any?>) = runBlocking { model.api.obj("/qa/upload-faults", "POST", json(*entries)) }
    private fun state(remote: String) = runBlocking { model.api.obj("/api/uploads/$remote") }
    private fun persisted() = JSONArray(File(rule.activity.filesDir, "upload-queue.json").readText())

    @Test fun activeRemovalSelectionRecoveryAndOfflineCleanup() {
        android.util.Log.i("UploadQA", "skip splash")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wait(
            androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("跳过")), 10000)?.click()
        rule.mainClock.advanceTimeBy(5100)
        android.util.Log.i("UploadQA", "bind")
        rule.runOnUiThread { model.bind("https://10.0.2.2:18446", InstrumentationRegistry.getArguments().getString("invite")!!, "上传恢复验收") }
        waitUntil { model.ready && !model.busy && !model.albumsLoading }
        rule.runOnUiThread { model.createAlbum("上传恢复专用") }
        waitUntil { model.albums.any { it.name == "上传恢复专用" } && !model.busy }
        val album = model.albums.first { it.name == "上传恢复专用" }
        rule.runOnUiThread { model.openAlbum(album) }
        waitUntil { model.currentAlbum?.id == album.id && !model.mediaLoading }
        val file = File(rule.activity.filesDir, "upload-recovery.png")
        val random = Random(42)
        val bitmap = Bitmap.createBitmap(IntArray(900 * 900) { random.nextInt() or (0xff shl 24) }, 900, 900, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val bytes = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun task(name: String) = UploadTask(UUID.randomUUID().toString(), Uri.fromFile(file).toString(), album.id, name, "image/png", file.length())
        fun remote(task: UploadTask) = runBlocking { model.api.obj("/api/uploads", "POST", json("request_id" to task.id,
            "album_id" to album.id, "name" to task.name, "mime" to task.mime, "size" to task.size)) }.getString("id")

        android.util.Log.i("UploadQA", "Removing the active task")
        faults("reset_counts" to true)
        // Removing the active task must cancel only that transfer and run the next item.
        val first = task("正在上传.png"); val second = task("下一项.png")
        queue(listOf(first, second)); faults("hold_chunks" to true)
        rule.runOnUiThread { model.startUploads() }
        waitUntil { model.activeUploadId == first.id && model.tasks.first().remote.isNotEmpty() }
        val firstRemote = model.tasks.first().remote
        rule.onNodeWithContentDescription("上传队列").performClick()
        rule.onNodeWithTag("upload-remove-${first.id}").assertIsEnabled().performClick()
        waitUntil { model.tasks.none { it.id == first.id } }
        faults("chunk_delay" to 0.8)
        waitUntil { model.tasks.none { it.id == first.id } && model.tasks.any { it.id == second.id && it.status == "已完成" } && !model.uploading }
        waitUntil { state(firstRemote).getString("status") == "cancelled" }
        assertTrue(file.exists())

        android.util.Log.i("UploadQA", "Resume only selected items")
        // Resume only selected items; an unselected paused item must not start.
        val selected = task("勾选继续.png").copy(status = "上传失败", error = "network failure")
        val untouched = task("未勾选.png").copy(status = "已暂停")
        val done = model.tasks.single()
        queue(listOf(selected, untouched, done)); faults("fail_chunks" to 1, "lose_replies" to 1)
        rule.onNodeWithTag("upload-select").performClick()
        rule.onNodeWithTag("upload-select-${selected.id}").performClick()
        rule.onNodeWithTag("upload-resume-selected").assertIsEnabled().performClick()
        waitUntil { model.tasks.any { it.id == selected.id && it.status.startsWith("正在重试") } }
        waitUntil { model.tasks.any { it.id == selected.id && it.status == "已完成" } && !model.uploading }
        assertEquals("", model.tasks.first { it.id == untouched.id }.remote)
        val metrics = runBlocking { model.api.obj("/qa/upload-counts") }
        assertTrue(metrics.getLong("max_chunk_bytes") <= 1024 * 1024)
        assertTrue(metrics.getInt("status_gets") > 0)
        val completed = model.tasks.first { it.id == selected.id }
        assertEquals(hash, state(completed.remote).getString("sha256"))
        rule.onNodeWithTag("upload-queue-list").performScrollToNode(hasTestTag("upload-select-${untouched.id}"))
        rule.onNodeWithTag("upload-select-${untouched.id}").performClick()
        rule.onNodeWithText("已选 2 项").assertIsDisplayed()
        rule.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(rule.activity.filesDir, "qa-upload-multiselect.png"))
        rule.onNodeWithTag("upload-delete-selected").performClick()
        waitUntil { model.tasks.size == 1 }
        assertEquals("complete", state(completed.remote).getString("status"))
        rule.onNodeWithTag("upload-select").performClick()

        android.util.Log.i("UploadQA", "Old cancelled sessions")
        // Old cancelled sessions and hash failures must rebuild once without reselecting the file.
        faults()
        val cancelled = task("取消后恢复.png")
        val cancelledRemote = remote(cancelled)
        runBlocking { model.api.request("/api/uploads/$cancelledRemote", "DELETE") }
        queue(listOf(cancelled.copy(remote = cancelledRemote, status = "上传失败")))
        rule.runOnUiThread { model.startUploads() }
        waitUntil { model.tasks.single().status == "已完成" && !model.uploading }
        assertNotEquals(cancelledRemote, model.tasks.single().remote)
        assertNotEquals(cancelled.id, model.tasks.single().requestId)
        assertEquals(model.tasks.single().requestId, UploadTask.parse(model.tasks.single().toJson()).requestId)
        val bad = task("校验后恢复.png")
        val badRemote = remote(bad)
        runBlocking {
            model.api.request("/api/uploads/$badRemote/chunks?offset=0", "PUT", bytes = bytes)
            try { model.api.obj("/api/uploads/$badRemote/complete", "POST", json("sha256" to "0".repeat(64))) } catch (_: ApiError) {}
        }
        queue(listOf(bad.copy(remote = badRemote, offset = bad.size, hash = "0".repeat(64), status = "上传失败")))
        rule.runOnUiThread { model.startUploads() }
        waitUntil { model.tasks.single().status == "已完成" && !model.uploading }
        assertNotEquals(badRemote, model.tasks.single().remote)
        assertEquals(hash, model.tasks.single().hash)

        android.util.Log.i("UploadQA", "A failed remote deletion")
        // A failed remote deletion disappears locally and persists a hidden cleanup entry.
        val offline = task("离线移除.png")
        val offlineRemote = remote(offline)
        queue(listOf(offline.copy(remote = offlineRemote)))
        faults("fail_delete" to true)
        rule.runOnUiThread { model.removeTasks(setOf(offline.id)) }
        waitUntil { model.tasks.isEmpty() && persisted().length() > 0 && (0 until persisted().length()).any { persisted().getJSONObject(it).optBoolean("removed") } }
        assertTrue(file.exists())
        rule.runOnUiThread {
            val restored = AppModel(rule.activity.application)
            assertTrue(restored.tasks.isEmpty())
        }
        faults()
        waitUntil { state(offlineRemote).getString("status") == "cancelled" && persisted().length() == 0 }

        android.util.Log.i("UploadQA", "Deleting during session creation")
        // Deleting during session creation must clean the remote session even when its ID was not received.
        val creating = task("创建时移除.png")
        queue(listOf(creating)); faults("create_delay" to 1.5)
        rule.runOnUiThread { model.startUploads() }
        waitUntil { model.activeUploadId == creating.id }
        Thread.sleep(400)
        rule.runOnUiThread { model.removeTask(creating) }
        waitUntil { !model.uploading && model.tasks.isEmpty() }
        faults()
        waitUntil { persisted().length() == 0 }
        assertEquals("cancelled", state(remote(creating)).getString("status"))
        assertTrue(file.exists())

        android.util.Log.i("UploadQA", "priority and selected resume after pause")
        val early = task("先上传.png"); val priority = task("优先上传.png")
        queue(listOf(early, priority)); faults("chunk_delay" to 0.6)
        rule.runOnUiThread { model.startUploads() }
        waitUntil { model.activeUploadId == early.id && model.tasks.first().remote.isNotEmpty() }
        rule.runOnUiThread { model.uploadNow(priority.id) }
        waitUntil { model.activeUploadId == priority.id }
        waitUntil { model.tasks.all { it.status == "已完成" } && !model.uploading }
        assertTrue(model.tasks.first { it.id == priority.id }.completedAt <= model.tasks.first { it.id == early.id }.completedAt)
        val paused = task("保持暂停.png"); val chosen = task("仅恢复这一项.png")
        queue(listOf(paused, chosen))
        rule.runOnUiThread { model.startUploads() }
        waitUntil { model.activeUploadId == paused.id && model.tasks.first().remote.isNotEmpty() }
        rule.runOnUiThread { model.pauseUploads() }
        waitUntil { !model.uploading }
        rule.runOnUiThread { model.continueUploads(setOf(chosen.id)) }
        waitUntil { model.tasks.first { it.id == chosen.id }.status == "已完成" && !model.uploading }
        assertEquals("已暂停", model.tasks.first { it.id == paused.id }.status)

        android.util.Log.i("UploadQA", "remove selected including active transfer")
        rule.runOnUiThread { model.continueUploads(setOf(paused.id)) }
        waitUntil { model.activeUploadId == paused.id }
        val pausedRemote = model.tasks.first { it.id == paused.id }.remote
        rule.runOnUiThread { model.removeTasks(setOf(paused.id, chosen.id)) }
        waitUntil { model.tasks.isEmpty() && !model.uploading && persisted().length() == 0 }
        assertEquals("cancelled", state(pausedRemote).getString("status"))
        assertTrue(file.exists())

        android.util.Log.i("UploadQA", "parallel video pause and resume")
        val video = File(rule.activity.filesDir, "parallel-resume.mp4")
        val videoBytes = ByteArray(15 * 1024 * 1024 + 73) { (it % 251).toByte() }
        byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109).copyInto(videoBytes)
        video.writeBytes(videoBytes)
        val videoHash = MessageDigest.getInstance("SHA-256").digest(videoBytes).joinToString("") { "%02x".format(it) }
        val videoTask = UploadTask(UUID.randomUUID().toString(), Uri.fromFile(video).toString(), album.id,
            "并行续传.mp4", "video/mp4", videoBytes.size.toLong())
        waitUntil { runBlocking { model.api.obj("/qa/upload-counts") }.getInt("active_chunks") == 0 }
        queue(listOf(videoTask)); faults("chunk_delay" to 0.8, "chunk_delays" to json("0" to 6.0), "reset_counts" to true)
        rule.runOnUiThread { model.startUploads() }
        waitUntil {
            val metrics = runBlocking { model.api.obj("/qa/upload-counts") }
            val started = metrics.getJSONArray("started_offsets")
            val finished = metrics.getJSONArray("finished_offsets")
            (0 until started.length()).any { started.getLong(it) == 3L * 1024 * 1024 } &&
                (0 until finished.length()).none { finished.getLong(it) == 0L }
        }
        android.util.Log.i("UploadQA", "refilled beyond three chunks while first chunk was still pending")
        rule.runOnUiThread { model.pauseUploads() }
        waitUntil { !model.uploading }
        assertEquals("已暂停", model.tasks.single().status)
        val videoRemote = model.tasks.single().remote
        val savedPrefix = state(videoRemote).getLong("offset")
        assertEquals(0L, savedPrefix)
        waitUntil { runBlocking { model.api.obj("/qa/upload-counts") }.getInt("active_chunks") == 0 }
        faults()
        rule.runOnUiThread { model.continueUploads(setOf(videoTask.id)) }
        waitUntil { model.tasks.single().status == "已完成" && !model.uploading }
        assertEquals(videoRemote, model.tasks.single().remote)
        assertEquals(videoHash, state(videoRemote).getString("sha256"))
        assertTrue(video.exists())
        val parallelMetrics = runBlocking { model.api.obj("/qa/upload-counts") }
        assertTrue(parallelMetrics.getInt("parallel_calls") > 0)
        assertEquals(3, parallelMetrics.getInt("max_active_chunks"))

        android.util.Log.i("UploadQA", "protected selection unlock then resume")
        faults()
        rule.runOnUiThread { model.saveAlbum(null, "加密上传恢复", true, "upload-qa-password") {} }
        waitUntil { model.albums.any { it.name == "加密上传恢复" } && !model.busy }
        val secret = model.albums.first { it.name == "加密上传恢复" }
        rule.runOnUiThread { model.background(); model.resume() }
        waitUntil { model.ready && !model.albumsLoading && !model.albumSessionUnlocked }
        val protected = task("需要解锁.png").copy(album = secret.id)
        queue(listOf(protected))
        rule.runOnUiThread { model.continueUploads(setOf(protected.id)) }
        waitUntil { model.albumToUnlock != null }
        assertFalse(model.uploading)
        rule.onNodeWithText("加密相册密码").performTextInput("upload-qa-password")
        rule.onNodeWithText("解锁").performClick()
        waitUntil { model.tasks.single().status == "已完成" && !model.uploading }

    }
}
