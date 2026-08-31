package com.d35p4c1t0.piffbackup.allfiles

import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import com.d35p4c1t0.piffbackup.data.FolderMappingEntity
import com.d35p4c1t0.piffbackup.data.LocalFileMetadataEntity
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.media.IncrementalFileListStore
import com.d35p4c1t0.piffbackup.media.RelativeFileListPath
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

fun interface LocalMetadataLookup {
    suspend fun find(mappingId: String, relativePaths: List<String>): List<LocalFileMetadataEntity>
}

data class PlannedAllFilesTransfer(
    val mapping: FolderMappingEntity,
    val fileList: File,
    val itemCount: Long,
    val totalBytes: Long,
)

/** Streams a local tree once, producing both a changed-file list and a full candidate baseline. */
class AllFilesMetadataPlanner(
    private val fileLists: IncrementalFileListStore,
    private val snapshots: AllFilesMetadataSnapshotStore,
    private val metadata: LocalMetadataLookup,
    volumeRoot: File,
) {
    private val canonicalVolumeRoot = volumeRoot.canonicalFile

    suspend fun plan(mapping: FolderMappingEntity): PlannedAllFilesTransfer {
        require(mapping.enabled && mapping.mode == MappingModeValue.ALL_FILES) { "Mapping is not enabled All files" }
        val root = CanonicalLocalRoot.create(mapping.canonicalLocalPath, canonicalVolumeRoot).file
        require(root.isDirectory) { "All-files root is unavailable" }
        val listWriter = fileLists.openWriter()
        val snapshotWriter = snapshots.openWriter(listWriter.file.path)
        var totalBytes = 0L
        return try {
            scan(root) { batch ->
                batch.forEach(snapshotWriter::append)
                val requestedPaths = batch.map { it.relativePath }.toSet()
                val persisted = metadata.find(mapping.id, requestedPaths.toList())
                require(
                    persisted.all {
                        it.folderMappingId == mapping.id && it.relativePath in requestedPaths
                    } && persisted.map { it.relativePath }.toSet().size == persisted.size
                ) {
                    "Invalid persisted metadata result"
                }
                val previous = persisted.associateBy { it.relativePath }
                batch.forEach { record ->
                    val old = previous[record.relativePath]
                    if (
                        old == null ||
                        old.sizeBytes != record.sizeBytes ||
                        old.modifiedAtEpochMillis != record.modifiedAtEpochMillis
                    ) {
                        listWriter.append(RelativeFileListPath.create(record.relativePath))
                        totalBytes = totalBytes.checkedAdd(record.sizeBytes)
                    }
                }
            }
            listWriter.close()
            snapshotWriter.close()
            PlannedAllFilesTransfer(
                mapping = mapping,
                fileList = listWriter.file,
                itemCount = listWriter.itemCount,
                totalBytes = totalBytes,
            )
        } catch (failure: Exception) {
            runCatching { listWriter.delete() }
            snapshotWriter.abort()
            throw failure
        }
    }

    fun writeSnapshotForFileList(mapping: FolderMappingEntity, fileListPath: String) {
        require(mapping.enabled && mapping.mode == MappingModeValue.ALL_FILES) { "Mapping is not enabled All files" }
        val root = CanonicalLocalRoot.create(mapping.canonicalLocalPath, canonicalVolumeRoot).file
        require(root.isDirectory) { "All-files root is unavailable" }
        val writer = snapshots.openWriter(fileListPath)
        try {
            forEachFileListPath(fileListPath) { relativePath ->
                val candidate = File(root, relativePath).canonicalFile
                require(candidate.toPath().startsWith(root.toPath())) { "File-list path escaped local root" }
                val attributes = Files.readAttributes(
                    candidate.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (attributes.isRegularFile) {
                    writer.append(
                        LocalMetadataRecord(
                            relativePath = relativePath,
                            sizeBytes = attributes.size(),
                            modifiedAtEpochMillis = attributes.lastModifiedTime().toMillis().coerceAtLeast(0L),
                        ),
                    )
                }
            }
            writer.close()
        } catch (failure: Exception) {
            writer.abort()
            throw failure
        }
    }

    private fun forEachFileListPath(fileListPath: String, consume: (String) -> Unit) {
        val pending = ByteArrayOutputStream()
        BufferedInputStream(FileInputStream(fileListPath)).use { input ->
            while (true) {
                val next = input.read()
                if (next < 0) break
                if (next == 0) {
                    require(pending.size() in 1..MAX_PATH_BYTES) { "Invalid file-list item" }
                    val relative = RelativeFileListPath.create(
                        decodeUtf8(pending.toByteArray()),
                    ).value
                    consume(relative)
                    pending.reset()
                } else {
                    require(pending.size() < MAX_PATH_BYTES) { "File-list item is too long" }
                    pending.write(next)
                }
            }
        }
        require(pending.size() == 0) { "File list is not NUL terminated" }
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString()

    private suspend fun scan(
        root: File,
        consume: suspend (List<LocalMetadataRecord>) -> Unit,
    ) {
        val rootPath = root.toPath()
        val batch = ArrayList<LocalMetadataRecord>(BATCH_SIZE)
        Files.walk(rootPath).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                val attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (!attributes.isRegularFile) continue
                val relative = rootPath.relativize(path).joinToString("/") { it.toString() }
                batch += LocalMetadataRecord(
                    relativePath = RelativeFileListPath.create(relative).value,
                    sizeBytes = attributes.size(),
                    modifiedAtEpochMillis = attributes.lastModifiedTime().toMillis().coerceAtLeast(0L),
                )
                if (batch.size == BATCH_SIZE) {
                    consume(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) consume(batch)
    }

    private fun Long.checkedAdd(value: Long): Long {
        require(value >= 0L && this <= Long.MAX_VALUE - value) { "All-files byte count overflow" }
        return this + value
    }

    private companion object {
        const val BATCH_SIZE = 256
        const val MAX_PATH_BYTES = 64 * 1024
    }
}
