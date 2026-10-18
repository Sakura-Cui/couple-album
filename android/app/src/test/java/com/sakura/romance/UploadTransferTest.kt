package com.sakura.romance

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UploadTransferTest {
    private val bytes = ByteArray(1025) { (it % 251).toByte() }
    private fun hash() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun newUploadReadsEachByteOnceAndHashesExactContent() {
        var read = 0
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val count = super.read(b, off, minOf(17, len))
                if (count > 0) read += count
                return count
            }
        }
        val source = UploadSource(input, true)
        source.resume(0) {}
        assertEquals(0, read)
        assertArrayEquals(bytes.copyOfRange(0, 700), source.readChunk(700) {})
        assertArrayEquals(bytes.copyOfRange(700, bytes.size), source.readChunk(bytes.size - 700) {})
        assertEquals(bytes.size, read)
        assertEquals(hash(), source.finishHash())
    }

    @Test fun resumedUploadHashesPrefixAndRemainder() {
        val source = UploadSource(ByteArrayInputStream(bytes), true)
        source.resume(400) {}
        assertArrayEquals(bytes.copyOfRange(400, bytes.size), source.readChunk(bytes.size - 400) {})
        assertEquals(hash(), source.finishHash())
    }

    @Test fun fullySentUploadCanRebuildHashAfterRestart() {
        val source = UploadSource(ByteArrayInputStream(bytes), true)
        source.resume(bytes.size.toLong()) {}
        assertEquals(hash(), source.finishHash())
    }

    @Test fun legacyResumeHandlesNonSeekableProvider() {
        val input = object : ByteArrayInputStream(bytes) { override fun skip(n: Long) = 0L }
        val source = UploadSource(input, false)
        source.resume(400) {}
        assertArrayEquals(bytes.copyOfRange(400, bytes.size), source.readChunk(bytes.size - 400) {})
        assertNull(source.finishHash())
    }

    @Test fun truncatedSourceAndCancellationStopReads() {
        assertThrows(IllegalStateException::class.java) { UploadSource(ByteArrayInputStream(bytes), true).readChunk(bytes.size + 1) {} }
        assertThrows(IllegalStateException::class.java) { UploadSource(ByteArrayInputStream(bytes), true).resume(bytes.size + 1L) {} }
        assertThrows(CancellationException::class.java) {
            UploadSource(ByteArrayInputStream(bytes), true).readChunk(10) { throw CancellationException() }
        }
    }

    @Test fun transientFailuresRetrySameChunkWithoutRehashing() = runBlocking {
        val source = UploadSource(ByteArrayInputStream(bytes), true)
        val chunk = source.readChunk(bytes.size) {}
        val waits = mutableListOf<Long>()
        var calls = 0
        val result = retryUploadRequest(wait = { waits += it }) {
            assertArrayEquals(bytes, chunk)
            if (++calls < 3) throw IOException("connection reset")
            chunk.size.toLong()
        }
        assertEquals(bytes.size.toLong(), result)
        assertEquals(listOf(1000L, 2000L), waits)
        assertEquals(hash(), source.finishHash())
    }

    @Test fun retriesHaveFiniteLimit() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        try {
            retryUploadRequest(wait = { waits += it }) { calls++; throw IOException("offline") }
            fail("must stop after four attempts")
        } catch (_: IOException) {}
        assertEquals(4, calls)
        assertEquals(listOf(1000L, 2000L, 4000L), waits)
    }

    @Test fun permanentErrorsAndCancellationNeverRetry() = runBlocking {
        for (status in listOf(401, 403, 409, 413, 422, 423, 507)) {
            var calls = 0
            try {
                retryUploadRequest(wait = { fail("must not retry $status") }) { calls++; throw ApiError(status, "rejected") }
                fail("must throw")
            } catch (_: ApiError) {}
            assertEquals(1, calls)
        }
        try {
            retryUploadRequest(wait = { fail("must not retry cancellation") }) { throw CancellationException() }
            fail("must cancel")
        } catch (_: CancellationException) {}
    }
}
