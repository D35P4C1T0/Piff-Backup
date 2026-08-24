package com.d35p4c1t0.piffbackup.rsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RsyncOutputParserTest {
    @Test
    fun `preview counts regular files without parsing filenames`() {
        val output = """
            PIFFBACKUP-ITEM:.f         :50
            PIFFBACKUP-ITEM:<f+++++++++:123
            a filename fragment that resembles output
            PIFFBACKUP-ITEM:cd+++++++++:4096
            PIFFBACKUP-ITEM:>f.s.......:456
            Number of regular files transferred: 2
        """.trimIndent()

        assertEquals(
            AdoptionPreviewSummary(
                alreadyBackedUpItems = 1L,
                itemsToUpload = 2L,
                bytesToUpload = 579L,
            ),
            RsyncOutputParser.parseAdoptionPreview(output),
        )
    }

    @Test
    fun `preview ignores malformed and filename-injected records`() {
        val output = """
            PIFFBACKUP-ITEM:<f+++++++++:12:filename
            file name
            PIFFBACKUP-ITEM:<f+++++++++:not-a-number
            PIFFBACKUP-ITEM:*deleting  :999
        """.trimIndent()

        assertEquals(AdoptionPreviewSummary(0L, 0L, 0L), RsyncOutputParser.parseAdoptionPreview(output))
    }

    @Test
    fun `preview rejects byte overflow`() {
        val output = """
            PIFFBACKUP-ITEM:<f+++++++++:${Long.MAX_VALUE}
            PIFFBACKUP-ITEM:<f+++++++++:1
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            RsyncOutputParser.parseAdoptionPreview(output)
        }
    }

    @Test
    fun `progress parser returns latest carriage-return record`() {
        val output = "  1,024  25%  1.00MB/s 0:00:01\r  4,096 100%  2.00MB/s 0:00:02\n"

        assertEquals(RsyncProgress(4_096L, 100), RsyncOutputParser.parseLatestProgress(output))
    }

    @Test
    fun `progress tracker handles records split across chunks`() {
        val observed = mutableListOf<RsyncProgress>()
        val tracker = RsyncProgressTracker(observed::add)

        tracker.accept("  1,0")
        tracker.accept("24  25%  1.00MB/s\rnoise\n  4,096 100%")
        tracker.accept("  2.00MB/s\r")
        tracker.finish()

        assertEquals(
            listOf(RsyncProgress(1_024L, 25), RsyncProgress(4_096L, 100)),
            observed,
        )
        assertEquals(RsyncProgress(4_096L, 100), tracker.latest)
    }
}
