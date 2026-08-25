package com.d35p4c1t0.piffbackup.scheduling

import java.util.concurrent.CopyOnWriteArraySet

enum class BackupProgressStatus {
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
}

data class BackupProgressEvent(
    val jobId: String,
    val status: BackupProgressStatus,
    val percentage: Int,
)

object BackupProgressEvents {
    private val listeners = CopyOnWriteArraySet<(BackupProgressEvent) -> Unit>()

    fun addListener(listener: (BackupProgressEvent) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (BackupProgressEvent) -> Unit) {
        listeners -= listener
    }

    fun publish(event: BackupProgressEvent) {
        listeners.forEach { listener -> runCatching { listener(event) } }
    }
}
