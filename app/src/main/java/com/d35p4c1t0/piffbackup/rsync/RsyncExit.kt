package com.d35p4c1t0.piffbackup.rsync

enum class RsyncExitKind(
    val retryable: Boolean,
    val partialTransfer: Boolean = false,
) {
    SUCCESS(retryable = false),
    SYNTAX_OR_USAGE(retryable = false),
    PROTOCOL_INCOMPATIBLE(retryable = false),
    FILE_SELECTION_ERROR(retryable = false),
    PROTOCOL_ERROR(retryable = true),
    CLIENT_SERVER_START_ERROR(retryable = true),
    DAEMON_LOG_ERROR(retryable = false),
    SOCKET_IO_ERROR(retryable = true),
    FILE_IO_ERROR(retryable = true),
    PROTOCOL_STREAM_ERROR(retryable = true),
    MESSAGE_STREAM_ERROR(retryable = true),
    DIAGNOSTIC_ERROR(retryable = true),
    SIGNAL_TERMINATED(retryable = true),
    WAITPID_ERROR(retryable = true),
    MEMORY_ERROR(retryable = true),
    PARTIAL_TRANSFER_ERROR(retryable = true, partialTransfer = true),
    VANISHED_SOURCE_FILES(retryable = true, partialTransfer = true),
    MAX_DELETE_LIMIT(retryable = false),
    IO_TIMEOUT(retryable = true),
    CONNECTION_TIMEOUT(retryable = true),
    CANCELLED(retryable = true, partialTransfer = true),
    UNKNOWN(retryable = false),
}

object RsyncExitClassifier {
    fun classify(exitCode: Int, cancelled: Boolean): RsyncExitKind {
        if (cancelled) return RsyncExitKind.CANCELLED
        return when (exitCode) {
            0 -> RsyncExitKind.SUCCESS
            1 -> RsyncExitKind.SYNTAX_OR_USAGE
            2 -> RsyncExitKind.PROTOCOL_INCOMPATIBLE
            3 -> RsyncExitKind.FILE_SELECTION_ERROR
            4 -> RsyncExitKind.PROTOCOL_ERROR
            5 -> RsyncExitKind.CLIENT_SERVER_START_ERROR
            6 -> RsyncExitKind.DAEMON_LOG_ERROR
            10 -> RsyncExitKind.SOCKET_IO_ERROR
            11 -> RsyncExitKind.FILE_IO_ERROR
            12 -> RsyncExitKind.PROTOCOL_STREAM_ERROR
            13 -> RsyncExitKind.MESSAGE_STREAM_ERROR
            14 -> RsyncExitKind.DIAGNOSTIC_ERROR
            20 -> RsyncExitKind.SIGNAL_TERMINATED
            21 -> RsyncExitKind.WAITPID_ERROR
            22 -> RsyncExitKind.MEMORY_ERROR
            23 -> RsyncExitKind.PARTIAL_TRANSFER_ERROR
            24 -> RsyncExitKind.VANISHED_SOURCE_FILES
            25 -> RsyncExitKind.MAX_DELETE_LIMIT
            30 -> RsyncExitKind.IO_TIMEOUT
            35 -> RsyncExitKind.CONNECTION_TIMEOUT
            else -> RsyncExitKind.UNKNOWN
        }
    }
}
