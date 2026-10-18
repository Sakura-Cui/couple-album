package com.sakura.romance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.RequestBody
import okio.BufferedSink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiError(val status: Int, override val message: String) : IOException(message)

class Api(private val vault: Vault) {
    @Volatile var unlockToken = ""
    private val connectionEpoch = AtomicLong()
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val base = vault.url.trimEnd('/')
            val builder = request.newBuilder()
            if (request.url.toString().startsWith("$base/")) {
                builder.header("Authorization", "Bearer ${vault.token}")
                if (unlockToken.isNotEmpty()) builder.header("X-Unlock-Token", unlockToken)
            }
            chain.proceed(builder.build())
        }.build()

    fun resetConnection() {
        connectionEpoch.incrementAndGet()
        unlockToken = ""
        client.dispatcher.cancelAll()
    }

    private fun checkConnection(epoch: Long) {
        if (connectionEpoch.get() != epoch) throw CancellationException("连接已更换")
    }

    fun mediaUrl(id: String, thumbnail: Boolean = false) = "${vault.url}/api/media/$id/${if (thumbnail) "thumbnail" else "content"}"

    fun adaptiveUrl(id: String) = "${vault.url}/api/media/$id/playback/hls/master.m3u8"

    fun playbackUrl(id: String) = "${vault.url}/api/media/$id/playback/content"

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun request(path: String, method: String = "GET", json: JSONObject? = null, bytes: ByteArray? = null, progress: ((Long) -> Unit)? = null): String {
        val epoch = connectionEpoch.get()
        try {
            val result = withContext(Dispatchers.IO) {
                checkConnection(epoch)
                val body = when {
                    bytes != null -> object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaType()
                        override fun contentLength() = bytes.size.toLong()
                        override fun writeTo(sink: BufferedSink) {
                            var sent = 0
                            while (sent < bytes.size) {
                                val count = minOf(64 * 1024, bytes.size - sent)
                                sink.write(bytes, sent, count)
                                sent += count
                                progress?.invoke(sent.toLong())
                            }
                        }
                    }
                    method in listOf("POST", "PUT", "PATCH") -> (json?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
                    else -> null
                }
                val request = Request.Builder().url(vault.url + path).method(method, body).build()
                checkConnection(epoch)
                val response = suspendCancellableCoroutine<Response> { continuation ->
                    val call = client.newCall(request)
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, error: IOException) { continuation.resumeWithException(error) }
                        override fun onResponse(call: Call, response: Response) {
                            continuation.resume(response) { response.close() }
                        }
                    })
                }
                response.use { response ->
                    val text = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val detail = runCatching { JSONObject(text).opt("detail") }.getOrNull()
                        val message = if (detail is String) detail else "请求失败（${response.code}），请检查输入或稍后重试"
                        throw ApiError(response.code, message)
                    }
                    text
                }
            }
            checkConnection(epoch)
            return result
        } catch (error: Exception) {
            checkConnection(epoch)
            throw error
        }
    }

    suspend fun obj(path: String, method: String = "GET", json: JSONObject? = null) = JSONObject(request(path, method, json))
    suspend fun array(path: String) = JSONArray(request(path))
}

fun json(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }

data class Album(val id: String, val name: String, val count: Int, val cover: String?, val pinned: Boolean = false, val protected: Boolean = false) {
    companion object {
        fun parse(o: JSONObject) = Album(o.getString("id"), o.getString("name"), o.optInt("count"), o.optString("display_cover").takeUnless { it.isBlank() || it == "null" }, o.optInt("pinned") == 1, o.optInt("protected") == 1)
    }
}

data class Media(val id: String, val albumId: String, val name: String, val mime: String, val size: Long, val created: Long, val thumbnail: Boolean, val cursor: String = "", val motionId: String? = null) {
    val video get() = mime.startsWith("video/")
    val live get() = motionId != null
    companion object {
        fun parse(o: JSONObject) = Media(o.getString("id"), o.getString("album_id"), o.getString("name"), o.getString("mime"), o.getLong("size"), (o.getDouble("created") * 1000).toLong(), o.optInt("thumbnail") == 1, o.optString("cursor"), o.optString("motion_id").takeUnless { it.isBlank() || it == "null" })
    }
}
