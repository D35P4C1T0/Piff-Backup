package com.d35p4c1t0.piffbackup.adoption

import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.data.FolderMappingEntity
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.media.IncrementalFileListStore
import com.d35p4c1t0.piffbackup.media.MediaGenerationWindow
import com.d35p4c1t0.piffbackup.media.MediaStoreMapping
import com.d35p4c1t0.piffbackup.media.MediaStoreSnapshot
import com.d35p4c1t0.piffbackup.media.MediaStoreSource
import com.d35p4c1t0.piffbackup.media.NulDelimitedFileListWriter
import com.d35p4c1t0.piffbackup.media.RelativeFileListPath
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class InitialRootFileList(
    val entity: FolderMappingEntity,
    val mapping: BackupMapping,
    val file: File,
    val itemCount: Long,
)

class InitialFileListPlanner(
    private val source: MediaStoreSource,
    private val store: IncrementalFileListStore,
    volumeRoot: File,
) {
    private val canonicalVolumeRoot = volumeRoot.canonicalFile

    fun plan(
        snapshot: MediaStoreSnapshot,
        mappings: List<FolderMappingEntity>,
    ): List<InitialRootFileList> {
        require(snapshot.stable) { "MediaStore snapshot is unstable" }
        val accumulators = mappings.map { entity ->
            val mapping = BackupMapping(
                localRoot = CanonicalLocalRoot.create(entity.canonicalLocalPath, canonicalVolumeRoot),
                remoteRoot = RemoteRelativePath.create(entity.relativeRemotePath),
            )
            RootAccumulator(entity, mapping, store.openWriter())
        }
        try {
            appendMedia(snapshot, accumulators.filter { it.entity.mode == MappingModeValue.MEDIA_FAST })
            accumulators.filter { it.entity.mode == MappingModeValue.ALL_FILES }.forEach(::appendAllFiles)
            return accumulators.map { accumulator ->
                accumulator.writer.close()
                InitialRootFileList(
                    entity = accumulator.entity,
                    mapping = accumulator.mapping,
                    file = accumulator.writer.file,
                    itemCount = accumulator.writer.itemCount,
                )
            }
        } catch (exception: Exception) {
            accumulators.forEach { runCatching { it.writer.delete() } }
            throw exception
        }
    }

    private fun appendMedia(snapshot: MediaStoreSnapshot, roots: List<RootAccumulator>) {
        if (roots.isEmpty() || snapshot.generation == 0L) return
        val mediaMappings = roots.map { MediaStoreMapping.create(it.mapping, canonicalVolumeRoot) }
        val window = MediaGenerationWindow(afterExclusive = 0L, throughInclusive = snapshot.generation)
        source.forEachChangedMedia(
            snapshot.volumeName,
            window,
        ) { row ->
            if (!row.changedWithin(window)) return@forEachChangedMedia
            var matchedIndex: Int? = null
            var relativePath: RelativeFileListPath? = null
            mediaMappings.forEachIndexed { index, mapping ->
                val candidate = mapping.relativeFilePath(row) ?: return@forEachIndexed
                require(matchedIndex == null) { "A media item matched overlapping folders" }
                matchedIndex = index
                relativePath = candidate
            }
            matchedIndex?.let { roots[it].writer.append(requireNotNull(relativePath)) }
        }
    }

    private fun appendAllFiles(root: RootAccumulator) {
        val localRoot = root.mapping.localRoot.file.toPath()
        Files.walkFileTree(
            localRoot,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        val relative = localRoot.relativize(file).joinToString("/") { it.toString() }
                        root.writer.append(RelativeFileListPath.create(relative))
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exception: java.io.IOException): FileVisitResult {
                    throw exception
                }
            },
        )
    }

    private data class RootAccumulator(
        val entity: FolderMappingEntity,
        val mapping: BackupMapping,
        val writer: NulDelimitedFileListWriter,
    )
}
