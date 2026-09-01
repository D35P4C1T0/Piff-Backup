package com.d35p4c1t0.piffbackup.rsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RsyncCommandEngineTest {
    @Test
    fun `preview summary streams beyond bounded diagnostic capture`() {
        val records = List(100) {
            "${RsyncCommandBuilder.ITEM_RECORD_PREFIX}<f+++++++++:5:file-$it.jpg"
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

    @Test
    fun `transfer file events stream independently from progress`() {
        val records = buildString {
            append("${RsyncCommandBuilder.ITEM_RECORD_PREFIX}<f+++++++++:5:Camera/photo.jpg\n")
            append("  5 100%  1.00MB/s 0:00:01\r")
        }
        val observedFiles = mutableListOf<String>()
        val command = RsyncCommand(
            arguments = listOf("/usr/bin/printf", "%s", records),
            environment = emptyMap(),
            outputKind = RsyncOutputKind.INCREMENTAL_TRANSFER,
        )

        RsyncCommandEngine().start(
            command = command,
            workingDirectory = File(requireNotNull(System.getProperty("java.io.tmpdir"))),
            onFile = observedFiles::add,
        ).await()

        assertEquals(listOf("Camera/photo.jpg"), observedFiles)
    }
}
