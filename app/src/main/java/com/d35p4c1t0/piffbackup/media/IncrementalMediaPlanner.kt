package com.d35p4c1t0.piffbackup.media

import com.d35p4c1t0.piffbackup.backup.BackupMappingValidator
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath

sealed interface MediaPlanningResult {
    data class FullReconciliationRequired(
        val snapshot: MediaStoreSnapshot,
        val reason: FullReconciliationReason,
    ) : MediaPlanningResult

    data class Incremental(
        val snapshot: MediaStoreSnapshot,
        val window: MediaGenerationWindow,
        val transfers: List<PlannedMediaTransfer>,
    ) : MediaPlanningResult {
        val proposedCheckpoint: MediaStoreCheckpoint = MediaStoreCheckpoint(
            volumeName = snapshot.volumeName,
            version = snapshot.version,
            successfulGeneration = snapshot.generation,
        )

        val hasWork: Boolean = transfers.isNotEmpty()

        fun checkpointAfter(completions: List<PlannedTransferCompletion>): MediaStoreCheckpoint? {
            require(completions.size == transfers.size) {
                "Every planned transfer must have exactly one completion"
            }
            return proposedCheckpoint.takeIf {
                completions.all { completion -> completion == PlannedTransferCompletion.SUCCESS }
            }
        }
    }
}

enum class PlannedTransferCompletion {
    SUCCESS,
    FAILED,
    CANCELLED,
}

data class PlannedMediaTransfer(
    val mapping: MediaStoreMapping,
    val fileList: java.io.File,
    val itemCount: Long,
    val totalBytes: Long,
) {
    init {
        require(itemCount > 0L) { "A planned transfer must contain at least one item" }
        require(fileList.isFile && fileList.length() > 0L) { "A planned file list must not be empty" }
        require(totalBytes >= 0L) { "Planned bytes must not be negative" }
    }
}

class IncrementalMediaPlanner(
    private val source: MediaStoreSource,
    private val fileListStore: IncrementalFileListStore,
    private val requiredRemoteBase: RemoteRelativePath,
) {
    fun plan(
        volumeName: String,
        checkpoint: MediaStoreCheckpoint?,
        mappings: List<MediaStoreMapping>,
    ): MediaPlanningResult {
        BackupMappingValidator.validate(mappings.map { it.mapping }, requiredRemoteBase)
        val snapshot = source.snapshot(volumeName)
        return when (val decision = MediaCheckpointPlanner.decide(checkpoint, snapshot)) {
            is MediaCheckpointDecision.FullReconciliationRequired -> {
                MediaPlanningResult.FullReconciliationRequired(snapshot, decision.reason)
            }

            is MediaCheckpointDecision.Incremental -> {
                buildIncrementalPlan(snapshot, decision.window, mappings)
            }
        }
    }

    private fun buildIncrementalPlan(
        snapshot: MediaStoreSnapshot,
        window: MediaGenerationWindow,
        mappings: List<MediaStoreMapping>,
    ): MediaPlanningResult.Incremental {
        if (window.afterExclusive == window.throughInclusive || mappings.isEmpty()) {
            return MediaPlanningResult.Incremental(snapshot, window, emptyList())
        }
        val roots = MutableList<RootAccumulator?>(mappings.size) { null }
        try {
            source.forEachChangedMedia(snapshot.volumeName, window) { row ->
                if (!row.changedWithin(window)) return@forEachChangedMedia
                var matchedIndex: Int? = null
                var matchedPath: RelativeFileListPath? = null
                mappings.forEachIndexed { index, mapping ->
                    val relativePath = mapping.relativeFilePath(row) ?: return@forEachIndexed
                    require(matchedIndex == null) { "A MediaStore row matched overlapping mappings" }
                    matchedIndex = index
                    matchedPath = relativePath
                }
                val index = matchedIndex ?: return@forEachChangedMedia
                val root = roots[index] ?: RootAccumulator(fileListStore.openWriter()).also { roots[index] = it }
                root.writer.append(requireNotNull(matchedPath))
                root.totalBytes = root.totalBytes.checkedAdd(row.sizeBytes)
            }
            roots.filterNotNull().forEach { it.writer.close() }
            val transfers = roots.mapIndexedNotNull { index, root ->
                root?.let {
                    PlannedMediaTransfer(
                        mapping = mappings[index],
                        fileList = it.writer.file,
                        itemCount = it.writer.itemCount,
                        totalBytes = it.totalBytes,
                    )
                }
            }
            return MediaPlanningResult.Incremental(snapshot, window, transfers)
        } catch (exception: Exception) {
            roots.filterNotNull().forEach { root ->
                runCatching { root.writer.delete() }
            }
            throw exception
        }
    }

    private data class RootAccumulator(
        val writer: NulDelimitedFileListWriter,
        var totalBytes: Long = 0L,
    )

    private fun Long.checkedAdd(value: Long): Long {
        require(value >= 0L && this <= Long.MAX_VALUE - value) { "Planned byte count overflow" }
        return this + value
    }
}
