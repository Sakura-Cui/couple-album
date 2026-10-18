package com.sakura.romance

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ParallelUploadTest {
    private val content = ByteArray(1025) { (it % 251).toByte() }

    @Test fun completedPrefixRefillsBeforeSlowSiblingFinishes() = runBlocking {
        val source = UploadSource(ByteArrayInputStream(content), true)
        val releaseSlow = CompletableDeferred<Unit>()
        val refilled = CompletableDeferred<Unit>()
        val received = mutableMapOf<Long, ByteArray>()
        var prefix = 0L
        var slowFinished = false
        withTimeout(1500) {
            val transfer = async {
                uploadChunks(source, 0, content.size.toLong(), 256, 3, {}) { offset, bytes ->
                    if (offset == 512L) { releaseSlow.await(); slowFinished = true }
                    if (offset == 768L) refilled.complete(Unit)
                    received[offset] = bytes
                    while (received.containsKey(prefix)) prefix += received.getValue(prefix).size
                    prefix
                }
            }
            refilled.await()
            assertFalse("next chunk must start while the slow sibling is pending", slowFinished)
            releaseSlow.complete(Unit)
            assertEquals(content.size.toLong(), transfer.await())
        }
    }

    @Test fun anotherDurablePrefixReleasesRequestWithLostReply() = runBlocking {
        val source = UploadSource(ByteArrayInputStream(content), true)
        val firstStored = CompletableDeferred<Unit>()
        val neverReplied = CompletableDeferred<Unit>()
        val received = mutableMapOf<Long, ByteArray>()
        var prefix = 0L
        var cancelledConfirmedRequest = false
        val end = withTimeout(1500) {
            uploadChunks(source, 0, content.size.toLong(), 256, 3, {}) { offset, bytes ->
                if (offset != 0L) firstStored.await()
                received[offset] = bytes
                while (received.containsKey(prefix)) prefix += received.getValue(prefix).size
                if (offset == 0L) {
                    firstStored.complete(Unit)
                    try { neverReplied.await() }
                    finally { cancelledConfirmedRequest = true }
                }
                prefix
            }
        }
        assertEquals(content.size.toLong(), end)
        assertTrue(cancelledConfirmedRequest)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }, source.finishHash())
    }

    @Test fun slowMissingPrefixNeverExceedsServerWindow() = runBlocking {
        val source = UploadSource(ByteArrayInputStream(content), true)
        val releaseFirst = CompletableDeferred<Unit>()
        val tailsReceived = CompletableDeferred<Unit>()
        val received = mutableMapOf<Long, ByteArray>()
        val started = mutableListOf<Long>()
        val confirmations = mutableListOf<Long>()
        var prefix = 0L
        withTimeout(1500) {
            val transfer = async {
                uploadChunks(source, 0, content.size.toLong(), 256, 3, {}, onConfirmed = { confirmations += it }) { offset, bytes ->
                    assertTrue("must stay within the server receive window", offset <= prefix + 512)
                    started += offset
                    if (offset == 0L) releaseFirst.await()
                    received[offset] = bytes
                    if (received.size == 2 && !received.containsKey(0L)) tailsReceived.complete(Unit)
                    while (received.containsKey(prefix)) prefix += received.getValue(prefix).size
                    prefix
                }
            }
            tailsReceived.await()
            repeat(10) { kotlinx.coroutines.yield() }
            assertEquals(listOf(0L, 256L, 512L), started)
            releaseFirst.complete(Unit)
            assertEquals(content.size.toLong(), transfer.await())
            assertEquals(content.size.toLong(), confirmations.last())
            assertEquals(confirmations.sorted().distinct(), confirmations)
        }
    }

    @Test fun missingFirstChunkKeepsThreeRequestsBusyWithinAdvertisedWindow() = runBlocking {
        val bytes = ByteArray(13 * 256 + 17) { (it % 251).toByte() }
        val source = UploadSource(ByteArrayInputStream(bytes), true)
        val releaseFirst = CompletableDeferred<Unit>()
        val windowFilled = CompletableDeferred<Unit>()
        val received = mutableMapOf<Long, ByteArray>()
        val started = mutableListOf<Long>()
        var prefix = 0L
        var active = 0
        var maximum = 0
        withTimeout(1500) {
            val transfer = async {
                uploadChunks(source, 0, bytes.size.toLong(), 256, 3, {}, receiveWindow = 12) { offset, part ->
                    assertTrue(offset < prefix + 12 * 256)
                    started += offset
                    active++; maximum = maxOf(maximum, active)
                    try {
                        if (offset == 0L) releaseFirst.await() else delay(1)
                        received[offset] = part
                        while (received.containsKey(prefix)) prefix += received.getValue(prefix).size
                        if (received.size == 11 && prefix == 0L) windowFilled.complete(Unit)
                        prefix
                    } finally { active-- }
                }
            }
            windowFilled.await()
            repeat(10) { kotlinx.coroutines.yield() }
            assertEquals((0L until 12).map { it * 256 }, started)
            assertEquals(0L, prefix)
            releaseFirst.complete(Unit)
            assertEquals(bytes.size.toLong(), transfer.await())
        }
        assertEquals(3, maximum)
        assertEquals(0, active)
        assertArrayEquals(bytes, received.toSortedMap().values.flatMap { it.toList() }.toByteArray())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, source.finishHash())
    }

    @Test fun outOfOrderWindowIsBoundedAndHashesInSourceOrder() = runBlocking {
        val source = UploadSource(ByteArrayInputStream(content), true)
        val started = CompletableDeferred<Unit>()
        val received = mutableMapOf<Long, ByteArray>()
        var active = 0
        var maximum = 0
        var prefix = 0L
        suspend fun send(offset: Long, bytes: ByteArray): Long {
            active++
            maximum = maxOf(maximum, active)
            if (active == 3) started.complete(Unit)
            started.await()
            delay(if (offset == 0L) 30 else 1)
            received[offset] = bytes
            active--
            while (received.containsKey(prefix)) prefix += received.getValue(prefix).size
            return prefix
        }
        val end = withTimeout(3000) { uploadChunks(source, 0, content.size.toLong(), 256, 3, {}, send = ::send) }
        assertEquals(3, maximum)
        assertEquals(0, active)
        assertEquals(content.size.toLong(), end)
        val actual = received.toSortedMap().values.flatMap { it.toList() }.toByteArray()
        assertArrayEquals(content, actual)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }, source.finishHash())
    }

    @Test fun failureCancelsAndJoinsOtherRequestsBeforeReturning() = runBlocking {
        val source = UploadSource(ByteArrayInputStream(content), false)
        val started = CompletableDeferred<Unit>()
        var active = 0
        var finished = 0
        try {
            withTimeout(3000) {
                uploadChunks(source, 0, content.size.toLong(), 256, 3, {}) { offset, _ ->
                    active++
                    if (active == 3) started.complete(Unit)
                    try {
                        started.await()
                        if (offset == 256L) throw IOException("offline")
                        delay(10000)
                        768L
                    } finally { active--; finished++ }
                }
            }
            fail("must propagate failure")
        } catch (_: IOException) { }
        assertEquals(0, active)
        assertEquals(3, finished)
    }

    @Test fun lostResponseRetransmitsExactBytesAndHashesOnce() = runBlocking {
        val source = UploadSource(ByteArrayInputStream(content), true)
        val received = mutableMapOf<Long, ByteArray>()
        val calls = mutableMapOf<Long, Int>()
        var prefix = 0L
        val end = uploadChunks(source, 0, 768, 256, 3, {}) { offset, bytes ->
            retryUploadRequest(wait = {}) {
                calls[offset] = (calls[offset] ?: 0) + 1
                received[offset]?.let { assertArrayEquals(it, bytes) }
                received[offset] = bytes
                while (received.containsKey(prefix)) prefix += received.getValue(prefix).size
                if (offset == 256L && calls[offset] == 1) throw IOException("response lost")
                prefix
            }
        }
        assertEquals(768L, end)
        assertEquals(2, calls[256L])
        assertEquals(MessageDigest.getInstance("SHA-256").digest(content.copyOfRange(0, 768)).joinToString("") { "%02x".format(it) }, source.finishHash())
    }

    @Test fun resumeAndShortFinalPartUseExactOffsets() = runBlocking {
        val source = UploadSource(ByteArrayInputStream(content), true)
        source.resume(512) {}
        val offsets = mutableListOf<Long>()
        val end = uploadChunks(source, 512, content.size.toLong(), 256, 3, {}) { offset, bytes ->
            offsets += offset
            assertArrayEquals(content.copyOfRange(offset.toInt(), offset.toInt() + bytes.size), bytes)
            offset + bytes.size
        }
        assertEquals(listOf(512L, 768L, 1024L), offsets)
        assertEquals(content.size.toLong(), end)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }, source.finishHash())
    }

    @Test fun unknownOrOldServerAndUnalignedResumeUseSerialProtocol() {
        assertFalse(parallelUploadSupported(0, 1, 16L * 1024 * 1024, 0))
        assertFalse(parallelUploadSupported(1024L * 1024, 1, 16L * 1024 * 1024, 0))
        assertFalse(parallelUploadSupported(1024L * 1024, 3, 1024, 0))
        assertFalse(parallelUploadSupported(1024L * 1024, 3, 16L * 1024 * 1024, 123))
        assertTrue(parallelUploadSupported(1024L * 1024, 3, 16L * 1024 * 1024, 0))
    }

    @Test fun simulatedWaitingBenchmark() = runBlocking {
        suspend fun transfer(parallelism: Int): Long {
            val source = UploadSource(ByteArrayInputStream(ByteArray(6 * 1024)), false)
            var offset = 0L
            val started = System.nanoTime()
            while (offset < 6 * 1024) {
                offset = uploadChunks(source, offset, 6 * 1024, 1024, parallelism, {}) { start, bytes ->
                    delay(100)
                    start + bytes.size
                }
            }
            return (System.nanoTime() - started) / 1_000_000
        }
        val serial = transfer(1)
        val parallel = transfer(3)
        println("SIMULATED_REQUEST_WAIT serial_ms=$serial parallel_ms=$parallel")
        // Timing is reported separately; deterministic concurrency is tested above.
    }
}
