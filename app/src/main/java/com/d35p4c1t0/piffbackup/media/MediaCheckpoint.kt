package com.d35p4c1t0.piffbackup.media

data class MediaStoreCheckpoint(
    val volumeName: String,
    val version: String,
    val successfulGeneration: Long,
) {
    init {
        require(volumeName.isNotBlank()) { "Volume name must not be blank" }
        require(version.isNotBlank()) { "MediaStore version must not be blank" }
        require(successfulGeneration >= 0L) { "MediaStore generation must not be negative" }
    }
}

data class MediaStoreSnapshot(
    val volumeName: String,
    val version: String,
    val generation: Long,
    val stable: Boolean = true,
    val accessScope: MediaAccessScope = MediaAccessScope.FULL,
) {
    init {
        require(volumeName.isNotBlank()) { "Volume name must not be blank" }
        require(version.isNotBlank()) { "MediaStore version must not be blank" }
        require(generation >= 0L) { "MediaStore generation must not be negative" }
    }
}

enum class MediaAccessScope {
    FULL,
    PARTIAL,
    NONE,
}

data class MediaGenerationWindow(
    val afterExclusive: Long,
    val throughInclusive: Long,
) {
    init {
        require(afterExclusive >= 0L) { "Previous generation must not be negative" }
        require(throughInclusive >= afterExclusive) { "Target generation must not precede checkpoint" }
    }
}

enum class FullReconciliationReason {
    NO_CHECKPOINT,
    VOLUME_CHANGED,
    VERSION_CHANGED,
    GENERATION_REWOUND,
    UNSTABLE_SNAPSHOT,
    MEDIA_ACCESS_INCOMPLETE,
}

sealed interface MediaCheckpointDecision {
    data class Incremental(
        val window: MediaGenerationWindow,
    ) : MediaCheckpointDecision

    data class FullReconciliationRequired(
        val reason: FullReconciliationReason,
    ) : MediaCheckpointDecision
}

object MediaCheckpointPlanner {
    fun decide(
        checkpoint: MediaStoreCheckpoint?,
        snapshot: MediaStoreSnapshot,
    ): MediaCheckpointDecision {
        if (!snapshot.stable) {
            return MediaCheckpointDecision.FullReconciliationRequired(
                FullReconciliationReason.UNSTABLE_SNAPSHOT,
            )
        }
        if (snapshot.accessScope != MediaAccessScope.FULL) {
            return MediaCheckpointDecision.FullReconciliationRequired(
                FullReconciliationReason.MEDIA_ACCESS_INCOMPLETE,
            )
        }
        if (checkpoint == null) {
            return MediaCheckpointDecision.FullReconciliationRequired(
                FullReconciliationReason.NO_CHECKPOINT,
            )
        }
        if (checkpoint.volumeName != snapshot.volumeName) {
            return MediaCheckpointDecision.FullReconciliationRequired(
                FullReconciliationReason.VOLUME_CHANGED,
            )
        }
        if (checkpoint.version != snapshot.version) {
            return MediaCheckpointDecision.FullReconciliationRequired(
                FullReconciliationReason.VERSION_CHANGED,
            )
        }
        if (snapshot.generation < checkpoint.successfulGeneration) {
            return MediaCheckpointDecision.FullReconciliationRequired(
                FullReconciliationReason.GENERATION_REWOUND,
            )
        }
        return MediaCheckpointDecision.Incremental(
            MediaGenerationWindow(
                afterExclusive = checkpoint.successfulGeneration,
                throughInclusive = snapshot.generation,
            ),
        )
    }
}
