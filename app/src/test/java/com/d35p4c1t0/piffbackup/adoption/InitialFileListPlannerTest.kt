package com.d35p4c1t0.piffbackup.adoption

import com.d35p4c1t0.piffbackup.data.FolderMappingEntity
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.media.IncrementalFileListStore
import com.d35p4c1t0.piffbackup.media.MediaGenerationWindow
import com.d35p4c1t0.piffbackup.media.MediaKind
import com.d35p4c1t0.piffbackup.media.MediaStoreRow
import com.d35p4c1t0.piffbackup.media.MediaStoreSnapshot
import com.d35p4c1t0.piffbackup.media.MediaStoreSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class InitialFileListPlannerTest {
    @Test
    fun `freezes media and all-files roots into NUL lists`() {
        val volume = Files.createTempDirectory("piffbackup-adoption-volume").toFile()
        val lists = Files.createTempDirectory("piffbackup-adoption-lists").toFile()
        val camera = File(volume, "DCIM/Camera").apply { mkdirs() }
        val documents = File(volume, "Documents").apply { mkdirs() }
        File(documents, "ordinary.txt").writeText("one")
        File(documents, "line\nbreak.txt").writeText("two")
        val source = FakeSource(
            listOf(
                MediaStoreRow(MediaKind.IMAGE, "DCIM/Camera/", "photo 😄.jpg", 2L, 2L, 5L),
                MediaStoreRow(MediaKind.IMAGE, "Pictures/", "outside.jpg", 2L, 2L, 5L),
            ),
        )
        val planner = InitialFileListPlanner(source, IncrementalFileListStore(lists), volume)

        val result = planner.plan(
            MediaStoreSnapshot("external_primary", "v1", 5L),
            listOf(
                entity("camera", camera, "Bianca/Camera", MappingModeValue.MEDIA_FAST),
                entity("documents", documents, "Bianca/Documents", MappingModeValue.ALL_FILES),
            ),
        )

        assertEquals(MediaGenerationWindow(0L, 5L), source.window)
        assertEquals(listOf("photo 😄.jpg"), decode(result[0].file))
        assertEquals(listOf("line\nbreak.txt", "ordinary.txt").sorted(), decode(result[1].file).sorted())
        assertEquals(1L, result[0].itemCount)
        assertEquals(2L, result[1].itemCount)
        assertTrue(result.all { it.file.isFile })
    }

    private fun entity(id: String, local: File, remote: String, mode: String) = FolderMappingEntity(
        id = id,
        profileId = "profile",
        displayName = id,
        treeUri = "content://com.android.externalstorage.documents/tree/primary%3A$id",
        canonicalLocalPath = local.path,
        relativeMediaStorePrefix = "$id/",
        relativeRemotePath = remote,
        mode = mode,
        enabled = true,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun decode(file: File): List<String> {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return emptyList()
        assertEquals(0, bytes.last().toInt())
        return bytes.dropLast(1).toByteArray().toString(StandardCharsets.UTF_8).split('\u0000')
    }

    private class FakeSource(private val rows: List<MediaStoreRow>) : MediaStoreSource {
        var window: MediaGenerationWindow? = null

        override fun snapshot(volumeName: String) = MediaStoreSnapshot(volumeName, "v1", 5L)

        override fun forEachChangedMedia(
            volumeName: String,
            window: MediaGenerationWindow,
            consumer: (MediaStoreRow) -> Unit,
        ) {
            this.window = window
            rows.forEach(consumer)
        }
    }
}
