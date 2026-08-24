package com.d35p4c1t0.piffbackup.media

import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class IncrementalMediaPlannerTest {
    private val volume = Files.createTempDirectory("piffbackup-planner-volume").toFile()
    private val fileLists = Files.createTempDirectory("piffbackup-file-lists").toFile()
    private val camera = mediaMapping("DCIM/Camera", "Bianca/Camera")
    private val videos = mediaMapping("Movies", "Bianca/Videos")
    private val checkpoint = MediaStoreCheckpoint("external_primary", "v1", 10L)

    @Test
    fun `streams changed rows into one NUL list per matching root`() {
        val source = FakeMediaStoreSource(
            snapshot = MediaStoreSnapshot("external_primary", "v1", 20L),
            rows = listOf(
                row(MediaKind.IMAGE, "DCIM/Camera/", "-leading.jpg", added = 11L),
                row(MediaKind.IMAGE, "DCIM/Camera/Trips/", "line\nbreak 😄.jpg", modified = 15L),
                row(MediaKind.VIDEO, "Movies/", "family's clip.mp4", added = 20L),
                row(MediaKind.IMAGE, "Pictures/", "outside.jpg", added = 12L),
                row(MediaKind.IMAGE, "DCIM/Camera/", "future.jpg", added = 21L),
                row(MediaKind.IMAGE, "DCIM/Camera/", "old.jpg", modified = 10L),
            ),
        )
        val result = planner(source).plan(
            volumeName = "external_primary",
            checkpoint = checkpoint,
            mappings = listOf(camera, videos),
        ) as MediaPlanningResult.Incremental

        assertEquals(MediaGenerationWindow(10L, 20L), source.requestedWindow)
        assertEquals(2, result.transfers.size)
        assertEquals(
            listOf("-leading.jpg", "Trips/line\nbreak 😄.jpg"),
            decode(result.transfers[0].fileList),
        )
        assertEquals(listOf("family's clip.mp4"), decode(result.transfers[1].fileList))
        assertEquals(2L, result.transfers[0].itemCount)
        assertEquals(1L, result.transfers[1].itemCount)
        assertEquals(MediaStoreCheckpoint("external_primary", "v1", 20L), result.proposedCheckpoint)
        assertEquals(
            result.proposedCheckpoint,
            result.checkpointAfter(listOf(PlannedTransferCompletion.SUCCESS, PlannedTransferCompletion.SUCCESS)),
        )
        assertNull(
            result.checkpointAfter(listOf(PlannedTransferCompletion.SUCCESS, PlannedTransferCompletion.CANCELLED)),
        )
        assertTrue(result.hasWork)
    }

    @Test
    fun `empty generation window does not query or create file lists`() {
        val source = FakeMediaStoreSource(MediaStoreSnapshot("external_primary", "v1", 10L), emptyList())
        val result = planner(source).plan(
            "external_primary",
            checkpoint,
            listOf(camera),
        ) as MediaPlanningResult.Incremental

        assertFalse(source.queried)
        assertFalse(result.hasWork)
        assertTrue(fileLists.listFiles().orEmpty().isEmpty())
        assertEquals(checkpoint, result.proposedCheckpoint)
        assertEquals(checkpoint, result.checkpointAfter(emptyList()))
    }

    @Test
    fun `version mismatch requires reconciliation without querying or creating lists`() {
        val source = FakeMediaStoreSource(MediaStoreSnapshot("external_primary", "v2", 20L), emptyList())
        val result = planner(source).plan("external_primary", checkpoint, listOf(camera))

        assertEquals(
            MediaPlanningResult.FullReconciliationRequired(
                MediaStoreSnapshot("external_primary", "v2", 20L),
                FullReconciliationReason.VERSION_CHANGED,
            ),
            result,
        )
        assertFalse(source.queried)
        assertTrue(fileLists.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `unsafe provider row aborts planning and removes partial lists`() {
        val source = FakeMediaStoreSource(
            MediaStoreSnapshot("external_primary", "v1", 20L),
            listOf(
                row(MediaKind.IMAGE, "DCIM/Camera/", "safe.jpg", added = 11L),
                row(MediaKind.IMAGE, "DCIM/Camera/../Other/", "unsafe.jpg", added = 12L),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            planner(source).plan("external_primary", checkpoint, listOf(camera))
        }
        assertTrue(fileLists.listFiles().orEmpty().isEmpty())
    }

    private fun planner(source: MediaStoreSource) = IncrementalMediaPlanner(
        source = source,
        fileListStore = IncrementalFileListStore(fileLists),
        requiredRemoteBase = RemoteRelativePath.create("Bianca"),
    )

    private fun mediaMapping(local: String, remote: String): MediaStoreMapping {
        val localDirectory = File(volume, local).apply { mkdirs() }
        return MediaStoreMapping.create(
            BackupMapping(
                CanonicalLocalRoot.create(localDirectory.path, volume),
                RemoteRelativePath.create(remote),
            ),
            volume,
        )
    }

    private fun row(
        kind: MediaKind,
        relativePath: String,
        displayName: String,
        added: Long = 0L,
        modified: Long = 0L,
    ) = MediaStoreRow(kind, relativePath, displayName, added, modified)

    private fun decode(file: File): List<String> {
        val bytes = file.readBytes()
        assertTrue(bytes.isNotEmpty())
        assertEquals(0, bytes.last().toInt())
        return bytes.dropLast(1)
            .toByteArray()
            .toString(StandardCharsets.UTF_8)
            .split('\u0000')
    }

    private class FakeMediaStoreSource(
        private val snapshot: MediaStoreSnapshot,
        private val rows: List<MediaStoreRow>,
    ) : MediaStoreSource {
        var queried = false
        var requestedWindow: MediaGenerationWindow? = null

        override fun snapshot(volumeName: String): MediaStoreSnapshot = snapshot

        override fun forEachChangedMedia(
            volumeName: String,
            window: MediaGenerationWindow,
            consumer: (MediaStoreRow) -> Unit,
        ) {
            queried = true
            requestedWindow = window
            rows.forEach(consumer)
        }
    }
}
