package com.sakura.romance

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class LivePhotoCachePolicyTest {
    @Test fun usesActualRangeTotalAndRejectsIncompleteMetadata() {
        assertEquals(3807444L, livePhotoContentSize(206, "bytes 0-0/3807444", 1))
        assertEquals(2000L, livePhotoContentSize(200, null, 2000))
        for ((status, range, length) in listOf(Triple(206, "bytes 1-1/2000", 1L), Triple(206, "bytes 0-0/*", 1L),
            Triple(200, null, -1L), Triple(200, null, 0L), Triple(206, "bytes 0-0/33554433", 1L))) {
            assertThrows(IOException::class.java) { livePhotoContentSize(status, range, length) }
        }
    }
    @Test fun boundsOnlyShortLivePreparation() {
        requireShortLivePhoto(3000)
        requireShortLivePhoto(10000)
        for (duration in listOf(null, 0L, -1L, 10001L))
            assertThrows(IOException::class.java) { requireShortLivePhoto(duration) }
    }
}
