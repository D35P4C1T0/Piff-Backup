package com.d35p4c1t0.piffbackup.media

import android.content.Context
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class IncrementalFileListStore(
    rootDirectory: File,
) {
    private val directory: File = rootDirectory.canonicalFile

    init {
        require(rootDirectory.isAbsolute) { "File-list directory must be absolute" }
        require((directory.isDirectory || directory.mkdirs()) && directory.canWrite()) {
            "File-list directory must be writable"
        }
    }

    fun openWriter(): NulDelimitedFileListWriter {
        val file = File.createTempFile("piffbackup-", ".from0", directory)
        return NulDelimitedFileListWriter(file)
    }

    fun cleanupExactTemporaryLists(): Int {
        var deleted = 0
        directory.listFiles().orEmpty().forEach { candidate ->
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (
                canonical.parentFile == directory &&
                canonical.name.startsWith("piffbackup-") &&
                canonical.name.endsWith(".from0") &&
                canonical.isFile &&
                canonical.delete()
            ) {
                deleted++
            }
        }
        return deleted
    }

    companion object {
        fun inAppPrivateStorage(context: Context): IncrementalFileListStore =
            IncrementalFileListStore(File(context.noBackupFilesDir, "incremental-file-lists"))
    }
}

class NulDelimitedFileListWriter internal constructor(
    val file: File,
) : Closeable {
    private val output = BufferedOutputStream(FileOutputStream(file, false))
    private var closed = false

    var itemCount: Long = 0L
        private set

    @Synchronized
    fun append(path: RelativeFileListPath) {
        check(!closed) { "File-list writer is closed" }
        require(itemCount < Long.MAX_VALUE) { "File-list item count overflow" }
        output.write(path.value.toByteArray(StandardCharsets.UTF_8))
        output.write(0)
        itemCount++
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        output.close()
    }

    fun delete(): Boolean {
        close()
        return file.delete()
    }
}
