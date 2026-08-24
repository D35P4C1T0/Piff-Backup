package com.d35p4c1t0.piffbackup.media

import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaStoreMappingTest {
    private val volume = Files.createTempDirectory("piffbackup-volume").toFile()
    private val mapping = MediaStoreMapping.create(
        BackupMapping(
            CanonicalLocalRoot.create(File(volume, "DCIM/Camera").path, volume),
            RemoteRelativePath.create("Bianca/Camera"),
        ),
        volume,
    )

    @Test
    fun `maps provider paths relative to selected root without losing special names`() {
        val direct = row("DCIM/Camera/", "-line\nbreak 😄's.jpg")
        val nested = row("DCIM/Camera/Trips/2026/", "clip.mp4")

        assertEquals("-line\nbreak 😄's.jpg", mapping.relativeFilePath(direct)?.value)
        assertEquals("Trips/2026/clip.mp4", mapping.relativeFilePath(nested)?.value)
        assertNull(mapping.relativeFilePath(row("Pictures/Screenshots/", "outside.png")))
    }

    @Test
    fun `rejects absolute traversal empty and slash-bearing provider values`() {
        listOf(
            row("/DCIM/Camera/", "bad.jpg"),
            row("DCIM/Camera/../Other/", "bad.jpg"),
            row("DCIM/Camera/", "../bad.jpg"),
            row("DCIM/Camera/", "bad/name.jpg"),
            row("DCIM/Camera/", "bad\u0000name.jpg"),
            row("DCIM/Camera/", ""),
        ).forEach { unsafe ->
            assertThrows(IllegalArgumentException::class.java) {
                mapping.relativeFilePath(unsafe)
            }
        }
    }

    @Test
    fun `mapping itself must remain within the selected volume`() {
        val outside = Files.createTempDirectory("piffbackup-outside-volume").toFile()
        val backupMapping = BackupMapping(
            CanonicalLocalRoot.create(File(outside, "DCIM").path, outside),
            RemoteRelativePath.create("Bianca/Outside"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            MediaStoreMapping.create(backupMapping, volume)
        }
    }

    private fun row(relativePath: String, displayName: String) = MediaStoreRow(
        kind = MediaKind.IMAGE,
        relativePath = relativePath,
        displayName = displayName,
        generationAdded = 11L,
        generationModified = 0L,
        sizeBytes = 1L,
    )
}
