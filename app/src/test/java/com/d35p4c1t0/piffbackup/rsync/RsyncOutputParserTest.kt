package com.d35p4c1t0.piffbackup.rsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RsyncOutputParserTest {
    @Test
    fun `preview counts regular files without parsing filenames`() {
        val output = """
            PIFFBACKUP-ITEM:.f         :50:already-there.jpg
            PIFFBACKUP-ITEM:<f+++++++++:123:new photo.jpg
            a filename fragment that resembles output
            PIFFBACKUP-ITEM:cd+++++++++:4096:Camera/
            PIFFBACKUP-ITEM:>f.s.......:456:edited.jpg
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
    fun `preview ignores malformed records`() {
        val output = """
            PIFFBACKUP-ITEM:<f+++++++++:12
            file name
            PIFFBACKUP-ITEM:<f+++++++++:not-a-number:file.jpg
            PIFFBACKUP-ITEM:*deleting  :999:deleted.jpg
        """.trimIndent()

        assertEquals(AdoptionPreviewSummary(0L, 0L, 0L), RsyncOutputParser.parseAdoptionPreview(output))
    }

    @Test
    fun `preview rejects byte overflow`() {
        val output = """
            PIFFBACKUP-ITEM:<f+++++++++:${Long.MAX_VALUE}:huge.bin
            PIFFBACKUP-ITEM:<f+++++++++:1:one-more.bin
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            RsyncOutputParser.parseAdoptionPreview(output)
        }
    }

    @Test
    fun `transfer parser keeps relative names and escaped control characters`() {
        assertEquals(
            "Camera/line\\#012break.jpg",
            RsyncOutputParser.parseTransferFileName(
                "PIFFBACKUP-ITEM:<f+++++++++:12:Camera/line\\#012break.jpg",
            ),
        )
        assertEquals(
            null,
            RsyncOutputParser.parseTransferFileName(
                "PIFFBACKUP-ITEM:.f         :12:Camera/already-there.jpg",
            ),
        )
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
