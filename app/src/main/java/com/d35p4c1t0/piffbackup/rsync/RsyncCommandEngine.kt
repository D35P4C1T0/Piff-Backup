package com.d35p4c1t0.piffbackup.rsync

import java.io.File

data class RsyncExecutionResult(
    val process: NativeProcessResult,
    val exitKind: RsyncExitKind,
    val latestProgress: RsyncProgress?,
    val adoptionPreviewSummary: AdoptionPreviewSummary?,
)

class RsyncCommandEngine(
    private val runner: NativeProcessRunner = NativeProcessRunner(),
) {
    fun start(
        command: RsyncCommand,
        workingDirectory: File,
        onProgress: (RsyncProgress) -> Unit = {},
        onFile: (String) -> Unit = {},
    ): RunningRsyncCommand {
        val progressTracker = RsyncProgressTracker(onProgress)
        val fileTracker = if (command.outputKind == RsyncOutputKind.ADOPTION_PREVIEW) {
            null
        } else {
            RsyncFileTracker(onFile)
        }
        val previewTracker = if (command.outputKind == RsyncOutputKind.ADOPTION_PREVIEW) {
            AdoptionPreviewTracker()
        } else {
            null
        }
        val process = runner.start(
            command = command.arguments,
            workingDirectory = workingDirectory,
            environment = command.environment,
            onStdoutChunk = { chunk ->
                progressTracker.accept(chunk)
                fileTracker?.accept(chunk)
                previewTracker?.accept(chunk)
            },
        )
        return RunningRsyncCommand(process, progressTracker, fileTracker, previewTracker)
    }
}

class RunningRsyncCommand internal constructor(
    private val process: RunningNativeProcess,
    private val progressTracker: RsyncProgressTracker,
    private val fileTracker: RsyncFileTracker?,
    private val previewTracker: AdoptionPreviewTracker?,
) {
    fun cancel() = process.cancel()

    fun await(): RsyncExecutionResult {
        val result = process.await()
        progressTracker.finish()
        fileTracker?.finish()
        previewTracker?.finish()
        return RsyncExecutionResult(
            process = result,
            exitKind = RsyncExitClassifier.classify(result.exitCode, result.cancelled),
            latestProgress = progressTracker.latest ?: RsyncOutputParser.parseLatestProgress(result.stdout),
            adoptionPreviewSummary = previewTracker?.summary(),
        )
    }
}

internal class RsyncFileTracker(
    private val observer: (String) -> Unit,
) {
    private val pending = StringBuilder()

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

    private fun parsePending() {
        if (pending.isEmpty()) return
        val record = pending.toString()
        pending.setLength(0)
        val fileName = runCatching { RsyncOutputParser.parseTransferFileName(record) }.getOrNull() ?: return
        try {
            observer(fileName)
        } catch (_: RuntimeException) {
            // A UI observer must not stop draining the native process pipe.
        }
    }

    private companion object {
        const val MAX_RECORD_LENGTH = 8 * 1024
    }
}

internal class RsyncProgressTracker(
    private val observer: (RsyncProgress) -> Unit,
) {
    private val pending = StringBuilder()
    @Volatile
    var latest: RsyncProgress? = null
        private set

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

    private fun parsePending() {
        if (pending.isEmpty()) return
        val parsed = RsyncOutputParser.parseLatestProgress(pending.toString())
        pending.setLength(0)
        if (parsed != null && parsed != latest) {
            latest = parsed
            try {
                observer(parsed)
            } catch (_: RuntimeException) {
                // A UI observer must not stop draining the native process pipe.
            }
        }
    }

    private companion object {
        const val MAX_RECORD_LENGTH = 8 * 1024
    }
}
