package com.d35p4c1t0.piffbackup.allfiles

import com.d35p4c1t0.piffbackup.data.FolderMappingEntity
import com.d35p4c1t0.piffbackup.data.LocalFileMetadataEntity
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.media.IncrementalFileListStore
import com.d35p4c1t0.piffbackup.media.RelativeFileListPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class AllFilesMetadataPlannerTest {
    private val volume = Files.createTempDirectory("piffbackup-all-files-volume").toFile()
    private val root = File(volume, "Documents").apply { mkdirs() }
    private val lists = Files.createTempDirectory("piffbackup-all-files-lists").toFile()
    private val snapshots = AllFilesMetadataSnapshotStore(lists)

    @Test
    fun `plans only new and modified files while snapshotting the complete current tree`() = runBlocking {
        val unchanged = File(root, "unchanged.txt").apply { writeText("same") }
        val modified = File(root, "nested/modified.txt").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("new contents")
        }
        File(root, "new file.txt").writeText("new")
        val old = listOf(
            metadata("unchanged.txt", unchanged.length(), unchanged.lastModified()),
            metadata("nested/modified.txt", 1L, modified.lastModified() - 1L),
            metadata("deleted.txt", 99L, 1L),
        )
        val planner = planner(old)

        val result = planner.plan(mapping())

        assertEquals(listOf("nested/modified.txt", "new file.txt"), decode(result.fileList).sorted())
        assertEquals(2L, result.itemCount)
        assertEquals(modified.length() + File(root, "new file.txt").length(), result.totalBytes)
        val snapshotRecords = mutableListOf<LocalMetadataRecord>()
        snapshots.forEachBatch(result.fileList.path) { snapshotRecords += it }
        assertEquals(
            listOf("nested/modified.txt", "new file.txt", "unchanged.txt"),
            snapshotRecords.map { it.relativePath }.sorted(),
        )
        assertTrue(snapshotRecords.none { it.relativePath == "deleted.txt" })
    }

    @Test
    fun `unchanged tree creates an empty transfer list and a valid baseline snapshot`() = runBlocking {
        val file = File(root, "already-there.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val planner = planner(listOf(metadata(file.name, file.length(), file.lastModified())))

        val result = planner.plan(mapping())

        assertEquals(0L, result.itemCount)
        assertEquals(0L, result.fileList.length())
        assertTrue(snapshots.isValid(result.fileList.path))
        val records = mutableListOf<LocalMetadataRecord>()
        snapshots.forEachBatch(result.fileList.path) { records += it }
        assertEquals(listOf(file.name), records.map { it.relativePath })
    }

    @Test
    fun `adoption baseline contains only files present in its immutable preview list`() = runBlocking {
        File(root, "previewed.txt").writeText("included")
        val fileListStore = IncrementalFileListStore(lists)
        val writer = fileListStore.openWriter()
        writer.append(RelativeFileListPath.create("previewed.txt"))
        writer.close()
        File(root, "created-after-preview.txt").writeText("must remain new")

        planner(emptyList()).writeSnapshotForFileList(mapping(), writer.file.path)

        val records = mutableListOf<LocalMetadataRecord>()
        snapshots.forEachBatch(writer.file.path) { records += it }
        assertEquals(listOf("previewed.txt"), records.map { it.relativePath })
    }

    private fun planner(previous: List<LocalFileMetadataEntity>) = AllFilesMetadataPlanner(
        fileLists = IncrementalFileListStore(lists),
        snapshots = snapshots,
        metadata = LocalMetadataLookup { mappingId, paths ->
            previous.filter { it.folderMappingId == mappingId && it.relativePath in paths }
        },
        volumeRoot = volume,
    )

    private fun mapping() = FolderMappingEntity(
        id = "documents",
        profileId = "profile",
        displayName = "Documents",
        treeUri = "content://documents",
        canonicalLocalPath = root.canonicalPath,
        relativeMediaStorePrefix = "Documents/",
        relativeRemotePath = "Test/Documents",
        mode = MappingModeValue.ALL_FILES,
        enabled = true,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun metadata(path: String, size: Long, modifiedAt: Long) = LocalFileMetadataEntity(
        folderMappingId = "documents",
        relativePath = path,
        sizeBytes = size,
        modifiedAtEpochMillis = modifiedAt,
        observedAtEpochMillis = 1L,
    )

    private fun decode(file: File): List<String> {
        if (file.length() == 0L) return emptyList()
        val bytes = file.readBytes()
        assertEquals(0, bytes.last().toInt())
        return bytes.dropLast(1).toByteArray().toString(StandardCharsets.UTF_8).split('\u0000')
    }
}
