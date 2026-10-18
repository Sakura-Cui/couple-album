package com.sakura.romance

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

private suspend fun <T> AppModel.uploadRequest(id: String, block: suspend () -> T): T = retryUploadRequest(onRetry = { attempt ->
    withContext(Dispatchers.Main) {
        tasks.find { it.id == id }?.let { updateTask(it.copy(status = "正在重试（$attempt/3）", sentOffset = null), persist = false) }
    }
}, send = block)

internal suspend fun AppModel.uploadRecovering(original: UploadTask) {
    try { uploadOne(original) }
    catch (error: UploadRestartRequired) {
        val current = tasks.find { it.id == original.id } ?: throw CancellationException()
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(Uri.parse(current.uri))?.close()
                ?: throw java.io.FileNotFoundException()
        }
        if (current.remote.isNotEmpty()) {
            try { uploadRequest(current.id) { api.request("/api/uploads/${current.remote}", "DELETE") } }
            catch (error: ApiError) { if (error.status != 404) throw error }
        }
        val replacement = current.copy(requestId = UUID.randomUUID().toString(), remote = "", offset = 0,
            hash = "", sentOffset = null, status = "重新上传", error = "")
        updateTask(replacement)
        uploadNotice = "旧任务无法续传，已重新开始上传"
        // At most one replacement per user attempt; never loop on a damaged source file.
        try { uploadOne(replacement) }
        catch (error: UploadRestartRequired) { throw IllegalStateException("文件仍无法校验，请重新选择原文件") }
    }
}

private suspend fun AppModel.uploadParallelChunks(item: UploadTask, source: UploadSource, transferJob: Job): UploadTask {
    val epoch = progressEpoch.incrementAndGet()
    val sent = mutableMapOf<Long, Long>() // Accessed only on Main.
    var confirmed = item.offset // Accessed only on Main.
    val context = coroutineContext
    withContext(Dispatchers.Main) { updateTask(item.copy(status = "正在上传", sentOffset = null), persist = false) }
    try {
        val next = uploadChunks(source, item.offset, item.size, parallelChunkSize.toInt(), parallelUploads,
            checkActive = { context.ensureActive() }, receiveWindow = parallelWindow, onConfirmed = { prefix ->
                withContext(Dispatchers.Main) {
                    confirmed = prefix
                    sent.keys.removeAll { minOf(it + parallelChunkSize, item.size) <= prefix }
                    updateTask(item.copy(offset = prefix, sentOffset = prefix + sent.values.sum(), status = "正在上传"), persist = false)
                }
            }) { offset, bytes ->
            retryUploadRequest(onRetry = { attempt ->
                withContext(Dispatchers.Main) {
                    sent.remove(offset)
                    tasks.find { it.id == item.id }?.let {
                        updateTask(it.copy(status = "正在重试（$attempt/3）", sentOffset = confirmed + sent.values.sum()), persist = false)
                    }
                }
            }) {
                var lastProgress = 0L
                val result = JSONObject(api.request("/api/uploads/${item.remote}/chunks?offset=$offset&parallel=true", "PUT", bytes = bytes, progress = { count ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 250 || count == bytes.size.toLong()) {
                        lastProgress = now
                        viewModelScope.launch {
                            if (transferJob.isActive && progressEpoch.get() == epoch && activeUploadId == item.id && offset >= confirmed) {
                                sent[offset] = count
                                tasks.find { it.id == item.id }?.let {
                                    updateTask(it.copy(sentOffset = confirmed + sent.values.sum()), persist = false)
                                }
                            }
                        }
                    }
                }))
                check(result.getLong("received_offset") == offset + bytes.size) { "服务器分片确认位置不一致，请重新尝试继续上传" }
                result.getLong("offset")
            }
        }
        val updated = item.copy(offset = next, sentOffset = null)
        withContext(Dispatchers.Main) { updateTask(updated, persist = false) }
        return updated
    } finally { progressEpoch.incrementAndGet() }
}

private suspend fun AppModel.uploadOne(original: UploadTask) {
    val transferJob = coroutineContext[Job]!!
    var item = original.copy(error = "", sentOffset = null, status = "准备上传")
    updateTask(item, persist = false)
    val resolver = getApplication<Application>().contentResolver
    val uri = Uri.parse(item.uri)
    if (item.hash.isEmpty() && !deferredUploadHash) {
        updateTask(item.copy(status = "校验文件"), persist = false)
        val hash = withContext(Dispatchers.IO) {
            val hash = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(256 * 1024)
                var count: Int
                while (input.read(buffer).also { count = it } != -1) { coroutineContext.ensureActive(); hash.update(buffer, 0, count) }
            } ?: throw IllegalStateException("原文件无法读取，请重新选择")
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        item = item.copy(hash = hash); updateTask(item)
    }
    val remote = if (item.remote.isEmpty()) {
        val started = uploadRequest(item.id) { api.obj("/api/uploads", "POST", json("request_id" to item.requestId, "album_id" to item.album,
            "name" to item.name, "mime" to item.mime, "size" to item.size).apply {
            if (item.hash.isNotEmpty()) put("sha256", item.hash)
        }) }
        item = item.copy(remote = started.getString("id")); updateTask(item)
        started
    } else try { uploadRequest(item.id) { api.obj("/api/uploads/${item.remote}") } }
    catch (error: ApiError) { if (error.status == 404) throw UploadRestartRequired() else throw error }
    if (uploadNeedsRestart(remote.getString("status"), remote.optString("error"))) throw UploadRestartRequired()
    item = item.copy(offset = remote.getLong("offset"), status = "正在上传"); updateTask(item, persist = false)
    if (remote.getString("status") != "complete") {
        if (item.offset < item.size || item.hash.isEmpty()) withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { input ->
                val source = UploadSource(input, calculateHash = item.hash.isEmpty())
                val context = coroutineContext
                if (item.offset > 0 && item.hash.isEmpty()) {
                    withContext(Dispatchers.Main) { updateTask(item.copy(status = "校验续传位置"), persist = false) }
                }
                source.resume(item.offset) { context.ensureActive() }
                withContext(Dispatchers.Main) { updateTask(item, persist = false) }
                while (item.offset < item.size) {
                    coroutineContext.ensureActive()
                    if (parallelUploadSupported(parallelChunkSize, parallelUploads, uploadChunkSize, item.offset)) {
                        item = uploadParallelChunks(item, source, transferJob)
                        continue
                    }
                    val size = minOf(uploadChunkSize, 1024L * 1024, item.size - item.offset).toInt()
                    val bytes = source.readChunk(size) { context.ensureActive() }
                    val confirmed = item.offset
                    var attempt = 0
                    val nextOffset = uploadRequest(item.id) {
                        if (attempt++ > 0) {
                            val state = api.obj("/api/uploads/${item.remote}")
                            if (uploadNeedsRestart(state.getString("status"), state.optString("error"))) throw UploadRestartRequired()
                            if (uploadChunkAlreadyReceived(state.getLong("offset"), confirmed, size)) return@uploadRequest confirmed + size
                        }
                        val epoch = progressEpoch.incrementAndGet()
                        withContext(Dispatchers.Main) { updateTask(item.copy(status = "正在上传", sentOffset = null), persist = false) }
                        var lastProgress = 0L
                        val result = JSONObject(api.request("/api/uploads/${item.remote}/chunks?offset=$confirmed", "PUT", bytes = bytes, progress = { sent ->
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastProgress >= 250 || sent == bytes.size.toLong()) {
                                lastProgress = now
                                val sentOffset = confirmed + sent
                                viewModelScope.launch {
                                    if (transferJob.isActive && progressEpoch.get() == epoch && activeUploadId == original.id) {
                                        val current = tasks.find { it.id == original.id } ?: return@launch
                                        updateTask(current.copy(sentOffset = sentOffset), persist = false)
                                    }
                                }
                            }
                        }))
                        progressEpoch.incrementAndGet()
                        result.getLong("offset")
                    }
                    check(nextOffset == confirmed + size) { "服务器上传位置不一致，请暂停后重试" }
                    item = item.copy(offset = nextOffset)
                    withContext(Dispatchers.Main) { updateTask(item, persist = false) }
                }
                source.finishHash()?.let { hash ->
                    item = item.copy(hash = hash)
                    withContext(Dispatchers.Main) { updateTask(item) }
                }
            } ?: throw IllegalStateException("无法读取原文件，请重新选择")
        }
        updateTask(item.copy(status = "正在保存"), persist = false)
        var saved = try { uploadRequest(item.id) { api.obj("/api/uploads/${item.remote}/complete", "POST", json("sha256" to item.hash)) } }
        catch (error: ApiError) {
            if (error.message in listOf("文件校验失败，请取消任务后重新上传", "文件校验值与任务不一致", "上传文件不完整")) throw UploadRestartRequired()
            throw error
        }
        while (saved.optString("status") == "saving") {
            delay(1000)
            coroutineContext.ensureActive()
            val status = uploadRequest(item.id) { api.obj("/api/uploads/${item.remote}") }
            if (status.getString("status") == "pending" && status.optString("error").isNotEmpty()) {
                if (uploadNeedsRestart(status.getString("status"), status.getString("error"))) throw UploadRestartRequired()
                throw IllegalStateException(status.getString("error"))
            }
            // Reissuing is idempotent and resumes a save interrupted by a server restart.
            saved = if (status.getString("status") == "saving") uploadRequest(item.id) { api.obj("/api/uploads/${item.remote}/complete", "POST", json("sha256" to item.hash)) } else status
        }
        check(saved.optString("status", "complete") == "complete") { "保存未完成，请重试" }
    }
    if (item.mime.startsWith("video/")) {
        runCatching {
            val bytes = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(getApplication(), uri)
                    val frame = if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                    } else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    frame?.let { original ->
                        val factor = minOf(1f, 640f / maxOf(original.width, original.height))
                        val bitmap = if (factor < 1f) Bitmap.createScaledBitmap(original, (original.width * factor).toInt().coerceAtLeast(1), (original.height * factor).toInt().coerceAtLeast(1), true) else original
                        if (bitmap !== original) original.recycle()
                        ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output); bitmap.recycle(); output.toByteArray() }
                    }
                } finally { retriever.release() }
            }
            if (bytes != null) {
                api.request("/api/media/${item.remote}/thumbnail", "PUT", bytes = bytes)
                previewCache.invalidate(api.mediaUrl(item.remote, true))
            }
        }.onFailure { if (it is CancellationException) throw it }
    }
    updateTask(item.copy(status = "已完成", offset = item.size, error = "", completedAt = System.currentTimeMillis()))
}

