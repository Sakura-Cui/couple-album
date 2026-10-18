package com.sakura.romance

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.AesCipherDataSink
import androidx.media3.datasource.AesCipherDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.security.MessageDigest

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StreamCache {
    private val evictor = ProtectedVideoEvictor(256L * 1024 * 1024)
    private var cache: SimpleCache? = null
    @Synchronized private fun cache(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.cacheDir, "video-streams"), evictor,
        StandaloneDatabaseProvider(context.applicationContext)).also { cache = it }

    fun protect(context: Context, key: String): java.io.Closeable {
        val cache = cache(context)
        return evictor.protect(cache, key)
    }

    fun factory(context: Context, vault: Vault, upstream: DataSource.Factory): CacheDataSource.Factory {
        val cache = cache(context)
        val secret = vault.videoCacheKey()
        val binding = vault.url + "\n" + vault.token
        return CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> MessageDigest.getInstance("SHA-256")
                .digest((binding + "\n" + spec.uri).toByteArray()).joinToString("") { "%02x".format(it) } }
            .setCacheReadDataSourceFactory { AesCipherDataSource(secret, FileDataSource()) }
            .setCacheWriteDataSinkFactory {
                AesCipherDataSink(secret, CacheDataSink.Factory().setCache(cache).setFragmentSize(1024 * 1024).createDataSink(), ByteArray(16 * 1024))
            }.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}

// SimpleCache invokes these callbacks under its own monitor. Use the same monitor
// for leases, so a download cannot race eviction or invert the cache lock order.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ProtectedVideoEvictor(private val maxBytes: Long) : CacheEvictor {
    private val spans = java.util.TreeSet(compareBy<CacheSpan> { it.lastTouchTimestamp }
        .thenBy { it.key }.thenBy { it.position })
    private val protected = mutableMapOf<String, Int>()
    private var bytes = 0L

    fun protect(cache: Cache, key: String): java.io.Closeable {
        synchronized(cache) { protected[key] = (protected[key] ?: 0) + 1 }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return java.io.Closeable {
            synchronized(cache) {
                if (closed.compareAndSet(false, true)) {
                    val count = protected.getValue(key)
                    if (count == 1) protected.remove(key) else protected[key] = count - 1
                    evict(cache, 0)
                }
            }
        }
    }

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length >= 0) evict(cache, length)
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        if (spans.add(span)) bytes += span.length
        evict(cache, 0)
    }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) bytes -= span.length
    }
    override fun onSpanTouched(cache: Cache, old: CacheSpan, new: CacheSpan) {
        onSpanRemoved(cache, old)
        onSpanAdded(cache, new)
    }
    private fun evict(cache: Cache, incoming: Long) {
        while (bytes + incoming > maxBytes) {
            val oldest = spans.firstOrNull { it.key !in protected } ?: break
            cache.removeSpan(oldest)
        }
    }
}

// EVENT playlists grow during preparation. Cache encrypted media segments only.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaylistDataSourceFactory(
    private val playlists: DataSource.Factory,
    private val media: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = object : DataSource {
        private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
        private var current: DataSource? = null
        override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {
            listeners.add(listener)
            current?.addTransferListener(listener)
        }
        override fun open(spec: androidx.media3.datasource.DataSpec): Long {
            current = (if (spec.uri.path?.endsWith(".m3u8") == true) playlists else media).createDataSource()
            listeners.forEach { current!!.addTransferListener(it) }
            return current!!.open(spec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = current!!.read(buffer, offset, length)
        override fun getUri() = current?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
        override fun close() { try { current?.close() } finally { current = null } }
    }
}
