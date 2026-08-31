package com.d35p4c1t0.piffbackup.allfiles

import com.d35p4c1t0.piffbackup.media.RelativeFileListPath
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class LocalMetadataRecord(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long,
)

/** App-private, atomic sidecar used to advance metadata only after its rsync root succeeds. */
class AllFilesMetadataSnapshotStore(rootDirectory: File) {
    private val directory = rootDirectory.canonicalFile

    init {
        require(rootDirectory.isAbsolute) { "Metadata snapshot directory must be absolute" }
        require((directory.isDirectory || directory.mkdirs()) && directory.canWrite()) {
            "Metadata snapshot directory must be writable"
        }
    }

    fun openWriter(fileListPath: String): LocalMetadataSnapshotWriter {
        val target = metadataFile(fileListPath)
        val temporary = File.createTempFile("piffbackup-metadata-", ".tmp", directory)
        return LocalMetadataSnapshotWriter(temporary, target)
    }

    suspend fun forEachBatch(
        fileListPath: String,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        consumer: suspend (List<LocalMetadataRecord>) -> Unit,
    ) {
        require(batchSize in 1..MAX_BATCH_SIZE) { "Invalid metadata batch size" }
        val file = requireValid(fileListPath)
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == MAGIC) { "Invalid metadata snapshot header" }
            val batch = ArrayList<LocalMetadataRecord>(batchSize)
            while (true) {
                val pathLength = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                require(pathLength in 1..MAX_PATH_BYTES) { "Invalid metadata path length" }
                val pathBytes = ByteArray(pathLength)
                input.readFully(pathBytes)
                val relativePath = RelativeFileListPath.create(
                    decodeUtf8(pathBytes),
                ).value
                val sizeBytes = input.readLong()
                val modifiedAt = input.readLong()
                require(sizeBytes >= 0L && modifiedAt >= 0L) { "Invalid local metadata" }
                batch += LocalMetadataRecord(relativePath, sizeBytes, modifiedAt)
                if (batch.size == batchSize) {
                    consumer(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) consumer(batch)
        }
    }

    fun isValid(fileListPath: String): Boolean = runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(requireValid(fileListPath)))).use { input ->
            require(input.readInt() == MAGIC) { "Invalid metadata snapshot header" }
        }
    }.isSuccess

    fun delete(fileListPath: String): Boolean {
        val file = runCatching { metadataFile(fileListPath) }.getOrNull() ?: return false
        return !file.exists() || file.delete()
    }

    fun path(fileListPath: String): String = metadataFile(fileListPath).path

    private fun requireValid(fileListPath: String): File = metadataFile(fileListPath).also { file ->
        require(file.isFile && file.canRead() && file.length() >= Int.SIZE_BYTES) {
            "Metadata snapshot is missing"
        }
    }

    private fun metadataFile(fileListPath: String): File {
        require(fileListPath.isNotBlank() && '\u0000' !in fileListPath) { "Invalid file-list path" }
        val list = File(fileListPath)
        require(list.isAbsolute) { "File-list path must be absolute" }
        val canonicalList = list.canonicalFile
        require(canonicalList.parentFile == directory) { "File list escaped app-private storage" }
        return File(directory, canonicalList.name + METADATA_SUFFIX).canonicalFile.also { metadata ->
            require(metadata.parentFile == directory) { "Metadata path escaped app-private storage" }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString()

    companion object {
        const val METADATA_SUFFIX = ".metadata"
        private const val MAGIC = 0x50424D31
        private const val DEFAULT_BATCH_SIZE = 256
        private const val MAX_BATCH_SIZE = 1_000
        private const val MAX_PATH_BYTES = 64 * 1024

        internal fun magic(): Int = MAGIC
    }
}

class LocalMetadataSnapshotWriter internal constructor(
    private val temporary: File,
    val file: File,
) : Closeable {
    private val fileOutput = FileOutputStream(temporary, false)
    private val output = DataOutputStream(BufferedOutputStream(fileOutput))
    private var closed = false

    init {
        output.writeInt(AllFilesMetadataSnapshotStore.magic())
    }

    @Synchronized
    fun append(record: LocalMetadataRecord) {
        check(!closed) { "Metadata snapshot writer is closed" }
        val safePath = RelativeFileListPath.create(record.relativePath).value
        val bytes = safePath.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024) { "Metadata path is too long" }
        require(record.sizeBytes >= 0L && record.modifiedAtEpochMillis >= 0L) { "Invalid local metadata" }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.writeLong(record.sizeBytes)
        output.writeLong(record.modifiedAtEpochMillis)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            output.flush()
            fileOutput.fd.sync()
            output.close()
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Exception) {
            runCatching { output.close() }
            temporary.delete()
            throw failure
        }
    }

    fun abort() {
        if (!closed) {
            closed = true
            runCatching { output.close() }
        }
        temporary.delete()
        file.delete()
    }
}
