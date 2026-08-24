package com.d35p4c1t0.piffbackup.rsync

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class NativeProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val cancelled: Boolean,
    val durationMillis: Long,
)

class NativeProcessRunner(
    private val captureLimitBytes: Int = DEFAULT_CAPTURE_LIMIT_BYTES,
) {
    init {
        require(captureLimitBytes > 0) { "captureLimitBytes must be positive" }
    }

    fun start(
        command: List<String>,
        workingDirectory: File,
        environment: Map<String, String> = emptyMap(),
        onStdoutChunk: ((String) -> Unit)? = null,
    ): RunningNativeProcess {
        require(command.isNotEmpty()) { "command must not be empty" }
        require(command.none { '\u0000' in it }) { "command arguments must not contain NUL" }
        require(workingDirectory.isDirectory) { "workingDirectory must be a directory" }

        val builder = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(false)
        builder.environment().putAll(environment)
        val process = builder.start()
        return RunningNativeProcess(process, captureLimitBytes, onStdoutChunk)
    }

    companion object {
        const val DEFAULT_CAPTURE_LIMIT_BYTES = 256 * 1024
    }
}

class RunningNativeProcess internal constructor(
    private val process: Process,
    captureLimitBytes: Int,
    onStdoutChunk: ((String) -> Unit)?,
) {
    private val startedAtNanos = System.nanoTime()
    private val cancellationRequested = AtomicBoolean(false)
    private val stdoutCapture = BoundedStreamCapture(
        input = process.inputStream,
        limit = captureLimitBytes,
        threadName = "piffbackup-stdout",
        observer = onStdoutChunk,
    )
    private val stderrCapture = BoundedStreamCapture(process.errorStream, captureLimitBytes, "piffbackup-stderr")

    init {
        stdoutCapture.start()
        stderrCapture.start()
    }

    fun cancel() {
        if (!cancellationRequested.compareAndSet(false, true)) return
        process.destroy()
        if (!process.waitFor(CANCEL_GRACE_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }

    fun await(): NativeProcessResult {
        val exitCode = process.waitFor()
        stdoutCapture.join()
        stderrCapture.join()
        return NativeProcessResult(
            exitCode = exitCode,
            stdout = stdoutCapture.text(),
            stderr = stderrCapture.text(),
            stdoutTruncated = stdoutCapture.truncated,
            stderrTruncated = stderrCapture.truncated,
            cancelled = cancellationRequested.get(),
            durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos),
        )
    }

    private companion object {
        const val CANCEL_GRACE_SECONDS = 2L
    }
}

private class BoundedStreamCapture(
    private val input: InputStream,
    private val limit: Int,
    threadName: String,
    private val observer: ((String) -> Unit)? = null,
) {
    private val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
    private val thread = Thread({ drain() }, threadName).apply { isDaemon = true }
    @Volatile
    var truncated: Boolean = false
        private set

    fun start() = thread.start()

    fun join() = thread.join()

    fun text(): String = output.toString(StandardCharsets.UTF_8.name())

    private fun drain() {
        input.use { stream ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) return
                observer?.invoke(String(buffer, 0, count, StandardCharsets.UTF_8))
                val remaining = limit - output.size()
                if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                if (count > remaining.coerceAtLeast(0)) truncated = true
            }
        }
    }
}
