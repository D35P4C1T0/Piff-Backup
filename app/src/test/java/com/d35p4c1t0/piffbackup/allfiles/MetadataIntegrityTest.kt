package com.d35p4c1t0.piffbackup.allfiles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class MetadataIntegrityTest {
    @Test fun `truncated or altered snapshots never validate`() = runBlocking {
        val root = Files.createTempDirectory("metadata-integrity").toFile()
        try {
            val store = AllFilesMetadataSnapshotStore(root)
            val list = File(root, "piffbackup-list.from0").apply { writeBytes(byteArrayOf(0)) }
            store.openWriter(list.path).use { it.append(LocalMetadataRecord("file.txt", 4, 1000)) }
            val file = File(store.path(list.path))
            val original = file.readBytes()
            assertTrue(store.isValid(list.path))
            list.writeBytes("changed-path\u0000".toByteArray())
            assertFalse(store.isValid(list.path))
            list.writeBytes(byteArrayOf(0))
            for (length in original.indices) {
                file.writeBytes(original.copyOf(length))
                assertFalse("truncation at $length", store.isValid(list.path))
            }
            for (index in original.indices) {
                val changed = original.copyOf(); changed[index] = (changed[index].toInt() xor 1).toByte()
                file.writeBytes(changed)
                assertFalse("alteration at $index", store.isValid(list.path))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `editing or removing a previewed file invalidates its snapshot`() = runBlocking {
        val root = Files.createTempDirectory("preview-integrity").toFile()
        try {
            val local = File(root, "Documents").apply { mkdirs() }
            val file = File(local, "file.txt").apply { writeText("old") }
            val lists = File(root, "lists").apply { mkdirs() }
            val store = AllFilesMetadataSnapshotStore(lists)
            val planner = AllFilesMetadataPlanner(
                com.d35p4c1t0.piffbackup.media.IncrementalFileListStore(lists), store,
                LocalMetadataLookup { _, _ -> emptyList() }, root)
            val mapping = com.d35p4c1t0.piffbackup.data.FolderMappingEntity(
                "mapping", "profile", "Documents", "content://documents", local.path,
                "Documents/", "Backup/Documents", "ALL_FILES", true, 0, 0)
            val list = File(lists, "piffbackup-list.from0").apply { writeBytes("file.txt\u0000".toByteArray()) }
            planner.writeSnapshotForFileList(mapping, list.path)
            assertTrue(planner.matchesSnapshot(mapping, list.path))
            file.writeText("edited contents")
            assertFalse(planner.matchesSnapshot(mapping, list.path))
            file.delete()
            assertFalse(planner.matchesSnapshot(mapping, list.path))
        } finally { root.deleteRecursively() }
    }
}
