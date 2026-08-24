package com.d35p4c1t0.piffbackup.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class IncrementalFileListStoreTest {
    @Test
    fun `cleanup deletes only exact temporary list names`() {
        val root = Files.createTempDirectory("piffbackup-list-cleanup").toFile()
        val store = IncrementalFileListStore(root)
        val generated = store.openWriter().apply {
            append(RelativeFileListPath.create("private/name.jpg"))
            close()
        }.file
        val unrelated = root.resolve("unrelated.keep").apply { writeText("keep") }
        val similar = root.resolve("piffbackup-not-a-list.txt").apply { writeText("keep") }

        assertEquals(1, store.cleanupExactTemporaryLists())
        assertTrue(!generated.exists())
        assertTrue(unrelated.isFile)
        assertTrue(similar.isFile)
    }
}
