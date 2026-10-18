package com.sakura.romance

import android.app.Application
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class UploadQueue(application: Application) {
    private val queueMutex = Mutex()
    private val queueFile = AtomicFile(File(application.filesDir, "upload-queue.json"))
    val exists get() = queueFile.baseFile.exists()

    fun read(): List<JSONObject> {
        val queue = JSONArray(queueFile.readFully().toString(Charsets.UTF_8))
        return (0 until queue.length()).map { queue.getJSONObject(it) }
    }

    suspend fun save(tasks: List<UploadTask>, pendingRemovals: List<UploadTask>) {
        // Capture immutable UI state on Main; serialize and fsync serially on IO.
        val snapshot = tasks.map { it.toJson() } + pendingRemovals.map { it.toJson().put("removed", true) }
        queueMutex.withLock {
            withContext(Dispatchers.IO + NonCancellable) {
                val data = JSONArray().apply { snapshot.forEach { put(it) } }.toString().toByteArray()
                val output = queueFile.startWrite()
                try { output.write(data); queueFile.finishWrite(output) }
                catch (error: Exception) { queueFile.failWrite(output); throw error }
            }
        }
    }
}
