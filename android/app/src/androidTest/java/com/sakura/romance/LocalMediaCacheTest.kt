package com.sakura.romance

import android.content.Context
import android.content.ContextWrapper
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LocalMediaCacheTest {
    private fun context(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("qa-cache-$id-$name", mode)
            override fun getCacheDir() = File(base.cacheDir, "qa-cache-$id").apply { mkdirs() }
        }
    }

    @Test fun previewsPersistEncryptedAndStayIsolatedByBinding() {
        val context = context()
        val vault = Vault(context).apply { url = "https://qa.invalid"; token = "first-binding" }
        val payload = ByteArray(4096) { (it % 251).toByte() }
        var calls = 0
        var status = 200
        fun client() = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${vault.token}").build())
        }.addInterceptor(PreviewCache(context, vault)).addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("QA")
                .body(payload.toResponseBody("image/jpeg".toMediaType())).build()
        }.build()
        fun read(client: OkHttpClient, id: String = "a".repeat(32)) = client.newCall(Request.Builder()
            .url(vault.url + "/api/media/$id/thumbnail").build()).execute().use { it.body!!.bytes() }
        try {
            assertArrayEquals(payload, read(client()))
            assertArrayEquals(payload, read(client()))
            assertEquals("A recreated image client must reuse disk cache", 1, calls)
            val files = File(context.cacheDir, "album-previews").listFiles()!!
            assertEquals(1, files.size)
            assertFalse("Cached thumbnail must be encrypted", payload.contentEquals(files.single().readBytes()))
            vault.token = "second-binding"
            assertArrayEquals(payload, read(client()))
            assertEquals("Different device bindings must not share cache entries", 2, calls)
            vault.token = "first-binding"
            files.single().writeBytes(byteArrayOf(1, 2, 3))
            assertArrayEquals(payload, read(client()))
            assertEquals("Damaged entries must fall back to the network", 3, calls)
            status = 503
            read(client(), "b".repeat(32)); read(client(), "b".repeat(32))
            assertEquals("Error responses must not be cached", 5, calls)
        } finally { context.cacheDir.deleteRecursively() }
    }

    @Test fun videoRangesAreCachedWhileReadingAndReuseEncryptedBytes() {
        val context = context()
        val vault = Vault(context).apply { url = "https://qa.invalid"; token = UUID.randomUUID().toString() }
        val payload = ByteArray(256 * 1024) { (it % 239).toByte() }
        var networkOpens = 0
        val upstream = DataSource.Factory {
            val bytes = ByteArrayDataSource(payload)
            object : DataSource by bytes {
                override fun open(spec: DataSpec): Long { networkOpens++; return bytes.open(spec) }
            }
        }
        val uri = vault.url + "/api/media/" + "c".repeat(32) + "/content"
        fun read(position: Long, length: Long): ByteArray {
            val source = StreamCache.factory(context, vault, upstream).createDataSource()
            return try {
                source.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build())
                ByteArrayOutputStream().use { out ->
                    val buffer = ByteArray(8192)
                    while (true) { val size = source.read(buffer, 0, buffer.size); if (size == -1) break; out.write(buffer, 0, size) }
                    out.toByteArray()
                }
            } finally { source.close() }
        }
        assertArrayEquals(payload.copyOfRange(0, 65536), read(0, 65536))
        assertEquals(1, networkOpens)
        assertArrayEquals(payload.copyOfRange(0, 65536), read(0, 65536))
        assertEquals("Replaying a downloaded fragment must not request the network", 1, networkOpens)
        assertArrayEquals(payload.copyOfRange(65536, 131072), read(65536, 65536))
        assertEquals(2, networkOpens)
        assertArrayEquals(payload.copyOfRange(0, 131072), read(0, 131072))
        assertEquals("Seeking over downloaded ranges must use cached fragments", 2, networkOpens)
        val spans = File(context.cacheDir, "video-streams").walkTopDown().filter { it.name.endsWith(".exo") }.toList()
        assertTrue(spans.isNotEmpty())
        assertTrue(spans.none { it.readBytes().contentEquals(payload.copyOfRange(0, 65536)) })
        vault.token = "another-binding"
        assertArrayEquals(payload.copyOfRange(0, 65536), read(0, 65536))
        assertEquals("Video cache must be isolated by binding", 3, networkOpens)
    }
}
