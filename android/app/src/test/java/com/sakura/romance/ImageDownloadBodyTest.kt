package com.sakura.romance

import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ImageDownloadBodyTest {
    @Test fun progressFollowsReadBytesInsteadOfTimeOrResponseHeaders() {
        val values = mutableListOf<Float?>()
        val bytes = ByteArray(65536) { it.toByte() }
        ImageDownloadBody(bytes.toResponseBody(), values::add).use { body ->
            assertEquals(listOf(0f), values)
            val sink = Buffer()
            assertEquals(8192L, body.source().read(sink, 8192))
            assertEquals(.125f, values.last()!!, .0001f)
            assertTrue(values.none { it == 1f })
            while (body.source().read(sink, 8192) != -1L) { }
            assertEquals(1f, values.last()!!, 0f)
            assertArrayEquals(bytes, sink.readByteArray())
        }
    }

    @Test fun unknownLengthNeverInventsAPercentage() {
        val values = mutableListOf<Float?>()
        val raw = object : ResponseBody() {
            val bytes = Buffer().write(ByteArray(32768))
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = bytes
        }
        ImageDownloadBody(raw, values::add).use { it.bytes() }
        assertTrue(values.isNotEmpty())
        assertTrue(values.all { it == null })
    }

    @Test fun truncatedResponseNeverReportsDownloadComplete() {
        val values = mutableListOf<Float?>()
        val raw = object : ResponseBody() {
            val bytes = Buffer().write(ByteArray(32768))
            override fun contentType() = null
            override fun contentLength() = 65536L
            override fun source() = bytes
        }
        ImageDownloadBody(raw, values::add).use { body ->
            val sink = Buffer()
            while (body.source().read(sink, 8192) != -1L) { }
        }
        assertEquals(.5f, values.last()!!, 0f)
        assertTrue(values.none { it == 1f })
    }

    @Test fun videoBufferTargetMatchesStartupAndShortClips() {
        assertEquals(8000L, videoStartBufferMs(-1, 0))
        assertEquals(8000L, videoStartBufferMs(84000, 0))
        assertEquals(3500L, videoStartBufferMs(3500, 0))
        assertEquals(2000L, videoStartBufferMs(84000, 82000))
    }
}
