package com.d35p4c1t0.piffbackup.scheduling

object BackupJobKind {
    private const val RECONCILIATION_PREFIX = "reconciliation-"

    fun reconciliationId(previewId: String): String = "$RECONCILIATION_PREFIX$previewId"

    fun isReconciliation(jobId: String): Boolean = jobId.startsWith(RECONCILIATION_PREFIX)
}
