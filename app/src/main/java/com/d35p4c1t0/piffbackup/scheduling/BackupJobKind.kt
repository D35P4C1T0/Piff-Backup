package com.d35p4c1t0.piffbackup.scheduling

object BackupJobKind {
    private const val ADOPTION_PREFIX = "adoption-"
    fun adoptionId(previewId: String): String = "$ADOPTION_PREFIX$previewId"
    fun isAdoption(jobId: String): Boolean = jobId.startsWith(ADOPTION_PREFIX)
    fun resetsCheckpoint(jobId: String): Boolean = isAdoption(jobId) || isReconciliation(jobId)

    private const val RECONCILIATION_PREFIX = "reconciliation-"

    fun reconciliationId(previewId: String): String = "$RECONCILIATION_PREFIX$previewId"

    fun isReconciliation(jobId: String): Boolean = jobId.startsWith(RECONCILIATION_PREFIX)
}
