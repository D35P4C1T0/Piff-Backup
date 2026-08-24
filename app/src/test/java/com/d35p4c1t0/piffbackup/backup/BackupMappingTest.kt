package com.d35p4c1t0.piffbackup.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupMappingTest {
    @Test
    fun `canonicalizes local roots and preserves source trailing slash`() {
        val shared = Files.createTempDirectory("piffbackup-shared").toFile()
        val nested = File(shared, "Pictures/../DCIM/Camera")

        val root = CanonicalLocalRoot.create(nested.path, shared)

        assertEquals(File(shared, "DCIM/Camera").canonicalPath + "/", root.pathWithTrailingSlash)
    }

    @Test
    fun `rejects local traversal and symlink escape`() {
        val parent = Files.createTempDirectory("piffbackup-roots").toFile()
        val shared = File(parent, "shared").apply { mkdirs() }
        val outside = File(parent, "outside").apply { mkdirs() }

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalLocalRoot.create("shared/Pictures", shared)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalLocalRoot.create(File(shared, "../outside").path, shared)
        }

        val link = File(shared, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalLocalRoot.create(link.path, shared)
        }
    }

    @Test
    fun `remote path accepts ordinary unicode but rejects unsafe components`() {
        assertEquals(
            "Matteo/Family's album 😄/",
            RemoteRelativePath.create("Matteo/Family's album 😄/").pathWithTrailingSlash,
        )
        listOf("/Matteo/Camera", "Matteo/../Other", "Matteo//Camera", "Matteo/./Camera", "Matteo/a\nb").forEach {
            assertThrows(IllegalArgumentException::class.java) { RemoteRelativePath.create(it) }
        }
    }

    @Test
    fun `rejects local overlap remote overlap and paths outside configured root`() {
        val shared = Files.createTempDirectory("piffbackup-overlap").toFile()
        val remoteBase = RemoteRelativePath.create("Matteo")
        val pictures = BackupMapping(
            CanonicalLocalRoot.create(File(shared, "Pictures").path, shared),
            RemoteRelativePath.create("Matteo/Pictures"),
        )
        val screenshots = BackupMapping(
            CanonicalLocalRoot.create(File(shared, "Pictures/Screenshots").path, shared),
            RemoteRelativePath.create("Matteo/Screenshots"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupMappingValidator.validate(listOf(pictures, screenshots), remoteBase)
        }

        val camera = BackupMapping(
            CanonicalLocalRoot.create(File(shared, "DCIM/Camera").path, shared),
            RemoteRelativePath.create("Matteo/Pictures/Camera"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupMappingValidator.validate(listOf(pictures, camera), remoteBase)
        }

        val outside = BackupMapping(
            CanonicalLocalRoot.create(File(shared, "Movies").path, shared),
            RemoteRelativePath.create("SomeoneElse/Movies"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupMappingValidator.validate(listOf(outside), remoteBase)
        }
    }
}
