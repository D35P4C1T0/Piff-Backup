package com.d35p4c1t0.piffbackup.ui

enum class HomeBackupStatus {
    EVERYTHING_BACKED_UP,
    LOOKING_FOR_CHANGES,
    NEW_ITEMS_READY,
    BACKING_UP,
    PAUSED,
    NEEDS_ATTENTION,
}

data class HomeScreenState(
    val status: HomeBackupStatus,
    val mappingCount: Int,
    val lastSuccessfulBackupAtEpochMillis: Long?,
    val changedItems: Long = 0L,
    val changedBytes: Long = 0L,
    val progressPercentage: Int? = null,
) {
    init {
        require(mappingCount >= 0) { "Mapping count must not be negative" }
        require(lastSuccessfulBackupAtEpochMillis == null || lastSuccessfulBackupAtEpochMillis >= 0L) {
            "Last successful backup time must not be negative"
        }
        require(changedItems >= 0L && changedBytes >= 0L) { "Changed totals must not be negative" }
        require(progressPercentage == null || progressPercentage in 0..100) {
            "Progress must be a percentage"
        }
        if (status == HomeBackupStatus.NEW_ITEMS_READY) {
            require(changedItems > 0L) { "Ready state requires changed items" }
        }
        if (status == HomeBackupStatus.BACKING_UP) {
            require(progressPercentage != null) { "Backing-up state requires progress" }
        }
    }

    companion object {
        fun loaded(
            mappingCount: Int,
            lastSuccessfulBackupAtEpochMillis: Long?,
            hasCurrentCheckpoint: Boolean,
        ) = HomeScreenState(
            status = if (hasCurrentCheckpoint) {
                HomeBackupStatus.EVERYTHING_BACKED_UP
            } else {
                HomeBackupStatus.NEEDS_ATTENTION
            },
            mappingCount = mappingCount,
            lastSuccessfulBackupAtEpochMillis = lastSuccessfulBackupAtEpochMillis,
        )
    }
}
