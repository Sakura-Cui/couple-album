package com.sakura.romance

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class LivePhotoPreparationException(message: String) : IOException(message)

internal fun requireShortLivePhoto(durationMs: Long?) {
    if (durationMs == null || durationMs !in 1..10000)
        throw LivePhotoPreparationException(if (durationMs == null || durationMs <= 0) "实况时长未知，请稍后重试" else "此实况超过 10 秒，暂不支持播放")
}

internal fun livePhotoContentSize(status: Int, contentRange: String?, contentLength: Long): Long {
    val size = when (status) {
        206 -> Regex("bytes 0-0/(\\d+)").matchEntire(contentRange.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
        200 -> contentLength
        else -> null
    }
    if (size == null || size !in 1..32L*1024*1024)
        throw LivePhotoPreparationException(if (size == null || size <= 0) "无法确认实况大小，请稍后重试" else "此实况超过 32 MB，暂不支持播放")
    return size
}

/** Range total is the selected file's actual byte length, never the still photo's size. */
internal suspend fun livePhotoSourceSize(api: Api, url: String): Long = suspendCancellableCoroutine { continuation ->
    val call = api.client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build())
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                try {
                    if (response.code !in listOf(200, 206)) throw ApiError(response.code, "实况访问失败，请重试。")
                    val body = response.body ?: throw IOException("实况响应为空。")
                    val size = livePhotoContentSize(response.code, response.header("Content-Range"), body.contentLength())
                    body.source().readByte()
                    if (continuation.isActive) continuation.resume(size)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        }
    })
}
