package com.d35p4c1t0.piffbackup.operations

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.zip.*

class ArchiveCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun encrypted(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        ArchiveCipher.encrypting(out, key).use { it.write(bytes) }
    }.toByteArray()
    @Test fun multipleFramesRoundTripAndRandomizeCiphertext() {
        val bytes = ByteArray(200000) { (it % 251).toByte() }
        val encoded = encrypted(bytes)
        assertFalse(encoded.contentEquals(encrypted(bytes)))
        val output = ByteArrayOutputStream()
        ArchiveCipher.decrypt(encoded.inputStream(), output, key)
        assertArrayEquals(bytes, output.toByteArray())
    }
    @Test fun truncationTamperingWrongKeyAndTrailingBytesAreRejected() {
        val encoded = encrypted(ByteArray(70000) { 17 })
        val corruptions = listOf(encoded.copyOf(encoded.size - 1), encoded.copyOf(65500),
            encoded + byteArrayOf(0), encoded.clone().also { it[30] = (it[30].toInt() xor 1).toByte() })
        corruptions.forEach { bytes -> assertTrue(runCatching { ArchiveCipher.decrypt(bytes.inputStream(), ByteArrayOutputStream(), key) }.isFailure) }
        assertTrue(runCatching { ArchiveCipher.decrypt(encoded.inputStream(), ByteArrayOutputStream(), ByteArray(32)) }.isFailure)
    }
    @Test fun reorderedFramesAreRejected() {
        val encoded = encrypted(ByteArray(140000))
        val width = 4 + 65536 + 16
        val swapped = encoded.copyOfRange(0,36) + encoded.copyOfRange(36+width,36+width*2) +
            encoded.copyOfRange(36,36+width) + encoded.copyOfRange(36+width*2,encoded.size)
        assertTrue(runCatching { ArchiveCipher.decrypt(swapped.inputStream(), ByteArrayOutputStream(), key) }.isFailure)
    }
    @Test fun extractionRejectsTraversalAndNeverReplacesExistingFiles() {
        val root = Files.createTempDirectory("archive-test").toFile()
        try {
            val dest = root.resolve("restored").apply { mkdir() }
            val zip = root.resolve("archive.zip")
            fun pack(path: String, content: String) { ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry(path)); it.write(content.toByteArray()); it.closeEntry()
            } }
            pack("../escaped", "bad")
            assertTrue(runCatching { EncryptedArchives.extract(zip,dest) }.isFailure)
            assertFalse(root.resolve("escaped").exists())
            pack("docs/test.txt", "original")
            EncryptedArchives.extract(zip,dest)
            EncryptedArchives.extract(zip,dest)
            pack("docs/test.txt", "new")
            assertTrue(runCatching { EncryptedArchives.extract(zip,dest) }.isFailure)
            assertEquals("original",dest.resolve("docs/test.txt").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun samplingIsBoundedAndDeterministic() {
        val file = File.createTempFile("sampling", ".list")
        try {
            val all = (0..300).joinToString("\u0000", postfix="\u0000") { "docs/file $it" }.toByteArray()
            file.writeBytes(all); FileListSampling.sample(file,"same-seed",25)
            val selected = file.readBytes(); val paths = mutableListOf<String>()
            FileListSampling.forEach(file, paths::add)
            assertEquals(25,paths.size)
            file.writeBytes(all); FileListSampling.sample(file,"same-seed",25)
            assertArrayEquals(selected,file.readBytes())
        } finally { file.delete() }
    }
}
