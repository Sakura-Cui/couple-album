package com.sakura.romance

import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class OriginalVideoCacheTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun vault(): Vault {
        val id = UUID.randomUUID().toString()
        return Vault(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("qa-original-$id-$name", mode)
        }).apply { url = "https://10.0.2.2:18449" }
    }
    private suspend fun bound(): Pair<Vault, Api> {
        val vault = vault()
        val api = Api(vault)
        api.obj("/qa/reset", "POST")
        val invite = api.obj("/qa/invite").getString("invite")
        vault.token = api.obj("/api/bind", "POST", json("invite" to invite, "name" to "原片缓存回归")).getString("token")
        return vault to api
    }
    private suspend fun item(api: Api) = Media.parse(api.array("/api/albums/${"a".repeat(32)}/media").getJSONObject(0))

    @Test fun cancelledAndTruncatedDownloadsResumeAndCachedBytesRemainExact() = runBlocking<Unit> {
        val (vault, api) = bound()
        val item = item(api)
        val factory = StreamCache.factory(context, vault, OkHttpDataSource.Factory(api.client))
        val spec = DataSpec.Builder().setUri(api.mediaUrl(item.id)).setLength(item.size).build()
        val cache = factory.cache!!
        val key = factory.cacheKeyFactory.buildCacheKey(spec)
        cache.removeResource(key)
        api.obj("/qa/stream?bytes_per_second=32768", "POST")
        val progress = AtomicLong()
        val first = OriginalVideoDownload(context, vault, api, item)
        val job = launch { first.prepare(vault) { progress.set(it) } }
        try {
            withTimeout(10000) { while (progress.get() < 32768) delay(25) }
            withTimeout(3000) { job.cancelAndJoin() }
            // Closing the cancelled HTTP body must commit its reusable encrypted fragment.
            withTimeout(3000) { while (cache.getCachedBytes(key, 0, item.size) == 0L) delay(25) }
            assertTrue(api.obj("/qa/state").has("requests"))
            assertFalse(cache.isCached(key, 0, item.size))
            api.obj("/qa/stream?truncate_after=65536", "POST")
            OriginalVideoDownload(context, vault, api, item).use { download ->
                try { download.prepare(vault) {}; fail("A truncated response must never be marked complete") }
                catch (_: IOException) { }
            }
            assertFalse(cache.isCached(key, 0, item.size))
            api.obj("/qa/stream", "POST")
            OriginalVideoDownload(context, vault, api, item).use { download ->
                download.prepare(vault) {}
                assertTrue(cache.isCached(key, 0, item.size))
                val ranges = api.obj("/qa/ranges").getJSONArray("ranges")
                assertTrue("Retry must start after cached bytes", (0 until ranges.length()).any {
                    val value = ranges.getString(it)
                    value.startsWith("bytes=") && !value.startsWith("bytes=0-")
                })
                val expected = api.array("/api/albums/${item.albumId}/media").getJSONObject(0).getString("sha256")
                val digest = MessageDigest.getInstance("SHA-256")
                val source = factory.setUpstreamDataSourceFactory(null).createDataSource()
                try {
                    source.open(spec)
                    val buffer = ByteArray(8192)
                    while (true) { val count = source.read(buffer, 0, buffer.size); if (count == C.RESULT_END_OF_INPUT) break; digest.update(buffer, 0, count) }
                } finally { source.close() }
                assertEquals(expected, digest.digest().joinToString("") { "%02x".format(it) })
                val span = cache.getCachedSpans(key).first()
                assertFalse("Original cache must remain encrypted", span.file!!.inputStream().use {
                    it.readNBytes(12).toString(Charsets.ISO_8859_1).contains("ftyp")
                })
            }
            val requests = api.obj("/qa/state").getLong("requests")
            OriginalVideoDownload(context, vault, api, item).use { it.prepare(vault) {} }
            assertEquals("A cache hit should only recheck access, not download content", requests + 2,
                api.obj("/qa/state").getLong("requests"))
            val reuseRanges = api.obj("/qa/ranges").getJSONArray("ranges")
            assertEquals("bytes=0-0", reuseRanges.getString(reuseRanges.length() - 1))
            assertEquals("bytes=0-0", reuseRanges.getString(reuseRanges.length() - 2))
            // A fully cached original still needs a valid current device credential.
            val oldToken = vault.token
            vault.token = "revoked-credential"
            OriginalVideoDownload(context, vault, api, item).use { download ->
                try { download.prepare(vault) {}; fail("Cached playback bypassed device authorization") }
                catch (error: ApiError) { assertEquals(401, error.status) }
            }
            vault.token = oldToken
            api.obj("/api/password", "PUT", json("new_password" to "qa-original-password"))
            api.unlockToken = api.obj("/api/unlock", "POST", json("password" to "qa-original-password")).getString("unlock_token")
            api.obj("/api/albums/${item.albumId}", "PATCH", json("name" to "原片播放验收", "protected" to true))
            api.unlockToken = api.obj("/api/unlock", "POST", json("password" to "qa-original-password")).getString("unlock_token")
            api.obj("/api/lock", "POST")
            assertTrue("The locked-album test must use the same fully cached binding", cache.isCached(key, 0, item.size))
            OriginalVideoDownload(context, vault, api, item).use { download ->
                try { download.prepare(vault) {}; fail("Fully cached original bypassed album lock") }
                catch (error: ApiError) { assertEquals(423, error.status) }
            }
            api.unlockToken = api.obj("/api/unlock", "POST", json("password" to "qa-original-password")).getString("unlock_token")
            api.obj("/api/albums/${item.albumId}", "PATCH", json("name" to "原片播放验收", "protected" to false))
            api.unlockToken = ""
            api.obj("/api/password", "PUT", json("current_password" to "qa-original-password", "new_password" to ""))
        } finally {
            job.cancelAndJoin(); first.close()
            api.obj("/qa/stream", "POST")
        }
    }

    @Test fun protectedOriginalCanExceed256MiBAndEvictsAfterRelease() {
        val vault = vault().apply { token = UUID.randomUUID().toString() }
        val length = 257L * 1024 * 1024
        val uri = vault.url + "/api/media/${"e".repeat(32)}/content"
        val upstream = DataSource.Factory {
            object : BaseDataSource(false) {
                var position = 0L
                var end = 0L
                override fun open(spec: DataSpec): Long {
                    position = spec.position
                    end = if (spec.length == C.LENGTH_UNSET.toLong()) length else position + spec.length
                    return end - position
                }
                override fun getUri() = android.net.Uri.parse(uri)
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    if (position >= end) return C.RESULT_END_OF_INPUT
                    val size = minOf(count.toLong(), end - position).toInt()
                    for (i in 0 until size) buffer[offset + i] = ((position + i) % 239).toByte()
                    position += size
                    return size
                }
                override fun close() = Unit
            }
        }
        val factory = StreamCache.factory(context, vault, upstream)
        val spec = DataSpec.Builder().setUri(uri).setLength(length).build()
        val cache = factory.cache!!
        val key = factory.cacheKeyFactory.buildCacheKey(spec)
        StreamCache.protect(context, key).use {
            CacheWriter(factory.createDataSourceForDownloading(), spec, null, null).cache()
            assertTrue(cache.isCached(key, 0, length))
            val reader = factory.setUpstreamDataSourceFactory(null).createDataSource()
            try {
                reader.open(spec.buildUpon().setPosition(length - 32).setLength(32).build())
                val bytes = ByteArray(32)
                assertEquals(32, reader.read(bytes, 0, 32))
                assertArrayEquals(ByteArray(32) { ((length - 32 + it) % 239).toByte() }, bytes)
            } finally { reader.close() }
        }
        assertTrue("Releasing the original must restore the 256MiB cache budget", cache.cacheSpace <= 256L * 1024 * 1024)
        assertFalse(cache.isCached(key, 0, length))
        cache.removeResource(key)
    }
}
