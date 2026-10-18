package com.sakura.romance

import org.junit.Assert.assertThrows
import org.junit.Test

class OriginalVideoSpaceTest {
    @Test fun cachedOriginalNeedsNoAdditionalDiskButMissingBytesKeepReserve() {
        requireOriginalVideoSpace(0, 0)
        requireOriginalVideoSpace(20L * 1024 * 1024, 1024 * 1024)
        assertThrows(OriginalVideoSpaceException::class.java) {
            requireOriginalVideoSpace(16L * 1024 * 1024, 1024 * 1024)
        }
    }
}
