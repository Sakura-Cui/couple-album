package com.sakura.romance

import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

// Hash bytes when reading the source, once per chunk, never when retransmitting it.
internal class UploadSource(private val input: InputStream, calculateHash: Boolean) {
    private val digest = if (calculateHash) MessageDigest.getInstance("SHA-256") else null

    fun resume(offset: Long, checkActive: () -> Unit) {
        var remaining = offset
        val buffer = ByteArray(256 * 1024)
        while (remaining > 0) {
            checkActive()
            if (digest == null) {
                val skipped = input.skip(remaining)
                if (skipped > 0) { remaining -= skipped; continue }
            }
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(count > 0) { "原文件已变更，请重新选择" }
            digest?.update(buffer, 0, count)
            remaining -= count
        }
    }

    fun readChunk(size: Int, checkActive: () -> Unit): ByteArray {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            checkActive()
            val count = input.read(bytes, read, size - read)
            check(count > 0) { "原文件已变更，请重新选择" }
            read += count
        }
        digest?.update(bytes)
        return bytes
    }

    fun finishHash(): String? = digest?.digest()?.joinToString("") { "%02x".format(it) }
}

internal suspend fun <T> retryUploadRequest(
    wait: suspend (Long) -> Unit = { delay(it) },
    onRetry: suspend (Int) -> Unit = {},
    send: suspend () -> T
): T {
    var failures = 0
    while (true) {
        try { return send() }
        catch (error: IOException) {
            if (error is ApiError && error.status !in listOf(408, 429, 500, 502, 503, 504)) throw error
            if (failures == 3) throw error
            onRetry(failures + 1)
            wait(1000L shl failures)
            failures++
        }
    }
}

internal fun uploadNeedsRestart(status: String, error: String): Boolean = status == "cancelled" ||
    (status == "pending" && error in listOf("文件校验失败，请取消任务后重新上传", "上传文件不完整"))

internal fun uploadChunkAlreadyReceived(offset: Long, start: Long, size: Int): Boolean {
    check(offset == start || offset == start + size) { "服务器上传位置不一致，请重新尝试继续上传" }
    return offset == start + size
}

internal class UploadRestartRequired : Exception()

// Read/hash in source order; refill only within the server's bounded receive window.
internal suspend fun uploadChunks(
    source: UploadSource, start: Long, total: Long, chunkSize: Int, parallelism: Int,
    checkActive: () -> Unit, onConfirmed: suspend (Long) -> Unit = {}, receiveWindow: Int = parallelism,
    send: suspend (Long, ByteArray) -> Long
): Long = coroutineScope {
    require(chunkSize > 0 && parallelism in 1..3 && receiveWindow in parallelism..12 && start in 0..total)
    val completed = Channel<Pair<Long, Long>>(parallelism)
    val requests = mutableMapOf<Long, Job>()
    var next = start
    var confirmed = start
    while (next < total || requests.isNotEmpty()) {
        while (next < total && requests.size < parallelism && next - confirmed < chunkSize.toLong() * receiveWindow) {
            checkActive()
            val offset = next
            val bytes = source.readChunk(minOf(chunkSize.toLong(), total - next).toInt(), checkActive)
            next += bytes.size
            requests[offset] = launch { completed.send(offset to send(offset, bytes)) }
        }
        check(requests.isNotEmpty()) { "服务器上传位置不一致，请重新尝试继续上传" }
        val (finished, offset) = completed.receive()
        if (requests.remove(finished) == null) continue
        check(offset in start..next && (offset == total || (offset - start) % chunkSize == 0L)) {
            "服务器上传位置不一致，请重新尝试继续上传"
        }
        if (offset > confirmed) {
            confirmed = offset
            // A contiguous durable prefix also confirms siblings whose replies were lost.
            for (received in requests.keys.filter { it < confirmed }) requests.remove(received)?.cancelAndJoin()
            onConfirmed(confirmed)
        }
    }
    check(confirmed == total) { "服务器上传位置不一致，请重新尝试继续上传" }
    confirmed
}

internal fun parallelUploadSupported(serverChunkSize: Long, serverParallelism: Int, chunkLimit: Long, offset: Long): Boolean =
    serverChunkSize == 1024L * 1024 && serverParallelism >= 2 && chunkLimit >= serverChunkSize && offset % serverChunkSize == 0L
