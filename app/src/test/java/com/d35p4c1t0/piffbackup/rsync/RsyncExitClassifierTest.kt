package com.d35p4c1t0.piffbackup.rsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RsyncExitClassifierTest {
    @Test
    fun `classifies success partial vanished timeout and unknown exits`() {
        assertEquals(RsyncExitKind.SUCCESS, RsyncExitClassifier.classify(0, cancelled = false))
        assertEquals(RsyncExitKind.PARTIAL_TRANSFER_ERROR, RsyncExitClassifier.classify(23, false))
        assertEquals(RsyncExitKind.VANISHED_SOURCE_FILES, RsyncExitClassifier.classify(24, false))
        assertEquals(RsyncExitKind.IO_TIMEOUT, RsyncExitClassifier.classify(30, false))
        assertEquals(RsyncExitKind.CONNECTION_TIMEOUT, RsyncExitClassifier.classify(35, false))
        assertEquals(RsyncExitKind.UNKNOWN, RsyncExitClassifier.classify(127, false))
    }

    @Test
    fun `cancellation takes precedence and partial outcomes remain retryable`() {
        assertEquals(RsyncExitKind.CANCELLED, RsyncExitClassifier.classify(0, cancelled = true))
        assertTrue(RsyncExitKind.PARTIAL_TRANSFER_ERROR.partialTransfer)
        assertTrue(RsyncExitKind.VANISHED_SOURCE_FILES.retryable)
        assertFalse(RsyncExitKind.SYNTAX_OR_USAGE.retryable)
    }
}
