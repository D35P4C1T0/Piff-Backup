package com.d35p4c1t0.piffbackup.rsync

data class AdoptionPreviewSummary(
    val alreadyBackedUpItems: Long,
    val itemsToUpload: Long,
    val bytesToUpload: Long,
)

object RsyncOutputParser {
    private val itemRecord = Regex(
        "^${Regex.escape(RsyncCommandBuilder.ITEM_RECORD_PREFIX)}(.{11}):([0-9]+)\\r?$",
    )
    private val progressRecord = Regex(
        "^\\s*([0-9][0-9,]*)\\s+([0-9]{1,3})%\\s+.*$",
    )

    fun parseAdoptionPreview(stdout: String): AdoptionPreviewSummary {
        val tracker = AdoptionPreviewTracker()
        tracker.accept(stdout)
        tracker.finish()
        return tracker.summary()
    }

    fun parseLatestProgress(stdout: String): RsyncProgress? {
        var latest: RsyncProgress? = null
        stdout.split('\r', '\n').forEach { record ->
            val match = progressRecord.matchEntire(record) ?: return@forEach
            val transferredBytes = match.groupValues[1].replace(",", "").toLongOrNull()
                ?: return@forEach
            val percentage = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (percentage !in 0..100) return@forEach
            latest = RsyncProgress(transferredBytes, percentage)
        }
        return latest
    }

    internal fun parseAdoptionItemRecord(record: String): AdoptionItemRecord? {
        val match = itemRecord.matchEntire(record) ?: return null
        val itemizedChange = match.groupValues[1]
        if (itemizedChange[1] != 'f') return null
        val length = match.groupValues[2].toLongOrNull()
            ?: throw IllegalArgumentException("Invalid rsync item length")
        return AdoptionItemRecord(
            requiresUpload = itemizedChange[0] == '<' || itemizedChange[0] == '>',
            length = length,
        )
    }
}

internal data class AdoptionItemRecord(
    val requiresUpload: Boolean,
    val length: Long,
)

internal class AdoptionPreviewTracker {
    private val pending = StringBuilder()
    private var totalRegularFiles = 0L
    private var uploadFiles = 0L
    private var uploadBytes = 0L
    private var failure: IllegalArgumentException? = null

    @Synchronized
    fun accept(chunk: String) {
        for (character in chunk) {
            if (character == '\r' || character == '\n') {
                parsePending()
            } else if (pending.length < MAX_RECORD_LENGTH) {
                pending.append(character)
            }
        }
    }

    @Synchronized
    fun finish() = parsePending()

    @Synchronized
    fun summary(): AdoptionPreviewSummary {
        failure?.let { throw it }
        require(uploadFiles <= totalRegularFiles) { "Invalid rsync preview counts" }
        return AdoptionPreviewSummary(
            alreadyBackedUpItems = totalRegularFiles - uploadFiles,
            itemsToUpload = uploadFiles,
            bytesToUpload = uploadBytes,
        )
    }

    private fun parsePending() {
        if (pending.isEmpty()) return
        val record = pending.toString()
        pending.setLength(0)
        if (failure != null) return
        try {
            val item = RsyncOutputParser.parseAdoptionItemRecord(record) ?: return
            totalRegularFiles = totalRegularFiles.checkedIncrement("Regular-file count overflow")
            if (item.requiresUpload) {
                uploadFiles = uploadFiles.checkedIncrement("Upload-file count overflow")
                uploadBytes = uploadBytes.checkedAdd(item.length, "Upload-byte count overflow")
            }
        } catch (exception: IllegalArgumentException) {
            failure = exception
        }
    }

    private fun Long.checkedIncrement(message: String): Long = checkedAdd(1L, message)

    private fun Long.checkedAdd(value: Long, message: String): Long {
        require(value >= 0L) { message }
        require(this <= Long.MAX_VALUE - value) { message }
        return this + value
    }

    private companion object {
        const val MAX_RECORD_LENGTH = 128
    }
}

data class RsyncProgress(
    val transferredBytes: Long,
    val percentage: Int,
)
