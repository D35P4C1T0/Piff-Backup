package com.d35p4c1t0.piffbackup.rsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RsyncCommandEngineTest {
    @Test
    fun `preview summary streams beyond bounded diagnostic capture`() {
        val records = List(100) {
            "${RsyncCommandBuilder.ITEM_RECORD_PREFIX}<f+++++++++:5"
        }.joinToString("\n")
        val command = RsyncCommand(
            arguments = listOf("/usr/bin/printf", "%s", records),
            environment = emptyMap(),
            outputKind = RsyncOutputKind.ADOPTION_PREVIEW,
        )
        val result = RsyncCommandEngine(
            runner = NativeProcessRunner(captureLimitBytes = 32),
        ).start(
            command = command,
            workingDirectory = File(requireNotNull(System.getProperty("java.io.tmpdir"))),
        ).await()

        assertTrue(result.process.stdoutTruncated)
        assertEquals(
            AdoptionPreviewSummary(0L, 100L, 500L),
            result.adoptionPreviewSummary,
        )
    }
}
