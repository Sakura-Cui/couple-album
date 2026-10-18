package com.sakura.romance

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class OriginalVideoSpaceException : IOException("手机可用空间不足，无法完整缓存原片。请释放空间后重试。")

internal fun requireOriginalVideoSpace(available: Long, missing: Long) {
    if (missing > 0 && available - missing < 16L * 1024 * 1024) throw OriginalVideoSpaceException()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OriginalVideoDownload(context: Context, vault: Vault, api: Api, item: Media, private val url: String = api.mediaUrl(item.id)) : Closeable {
    private val binding = vault.url + "\n" + vault.token
    private val cancelled = AtomicBoolean()
    private val calls = mutableListOf<Call>()
    private val callFactory = Call.Factory { request ->
        api.client.newCall(request).also { call ->
            synchronized(calls) {
                if (cancelled.get()) call.cancel() else calls.add(call)
            }
        }
    }
    private val factory = StreamCache.factory(context, vault, OkHttpDataSource.Factory(callFactory))
    private val spec = DataSpec.Builder().setUri(url).setLength(item.size).build()
    private val cache = factory.cache!!
    private val key = factory.cacheKeyFactory.buildCacheKey(spec)
    private val lease = StreamCache.protect(context, key)
    private val directory = context.cacheDir
    private val size = item.size
    @Volatile private var preparation: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    @Volatile private var writer: CacheWriter? = null

    suspend fun prepare(vault: Vault, progress: (Long) -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
        preparation = continuation
        continuation.invokeOnCancellation { close() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            try {
                if (!continuation.isActive) return@Runnable
                checkAccess()
                val cached = cache.getCachedBytes(key, 0, size)
                progress(cached)
                requireOriginalVideoSpace(directory.usableSpace, size - cached)
                val download = CacheWriter(factory.createDataSourceForDownloading(), spec, null) { _, bytes, _ ->
                    if (continuation.isActive) progress(bytes.coerceIn(0, size))
                }
                writer = download
                if (cancelled.get()) download.cancel()
                download.cache()
                if (!cache.isCached(key, 0, size)) throw IOException("原片缓存不完整，请重试。")
                // Recheck permission after a potentially long download, including cache hits.
                checkAccess()
                if (vault.url + "\n" + vault.token != binding) throw IOException("连接已更换")
                if (continuation.isActive) continuation.resume(Unit)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        })
    }

    fun cacheOnlySourceFactory(): DataSource.Factory {
        check(cache.isCached(key, 0, size))
        // A bounded CacheWriter request may finish without recording the resource's EOF.
        val metadata = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(metadata, size)
        cache.applyContentMetadataMutations(key, metadata)
        return factory.setUpstreamDataSourceFactory(null)
    }

    private fun checkAccess() {
        callFactory.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use {
            if (it.code !in listOf(200, 206)) throw ApiError(it.code, "原片访问失败")
            if (it.body?.source()?.readByte() == null) throw IOException("原片响应为空")
        }
    }

    override fun close() {
        if (cancelled.compareAndSet(false, true)) {
            preparation?.cancel()
            writer?.cancel()
            synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() }
            lease.close()
        }
    }
}
