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
    val timedOut: Boolean,
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

        val groupLauncher = File("/system/bin/setsid").takeIf { it.canExecute() }
        val builder = ProcessBuilder(if (groupLauncher == null) command else listOf(groupLauncher.path, "-w", "/system/bin/sh", "-c",
            "printf '__PIFFBACKUP_PROCESS_GROUP:%s\\n' \"\$\$\" >&2 || exit 125; exec \"\$@\"", "piffbackup") + command)
            .directory(workingDirectory)
            .redirectErrorStream(false)
        builder.environment().putAll(environment)
        val process = builder.start()
        return RunningNativeProcess(process, captureLimitBytes, onStdoutChunk, groupLauncher != null)
    }

    companion object {
        const val DEFAULT_CAPTURE_LIMIT_BYTES = 256 * 1024
    }
}

class RunningNativeProcess internal constructor(
    private val process: Process,
    captureLimitBytes: Int,
    onStdoutChunk: ((String) -> Unit)?,
    private val ownsProcessGroup: Boolean = false,
) {
    private val startedAtNanos = System.nanoTime()
    private val groupId = java.util.concurrent.atomic.AtomicInteger(0)
    private val groupReady = java.util.concurrent.CountDownLatch(1)
    private val cancellationRequested = AtomicBoolean(false)
    private val timeoutReached = AtomicBoolean(false)
    private val stdoutCapture = BoundedStreamCapture(
        input = process.inputStream,
        limit = captureLimitBytes,
        threadName = "piffbackup-stdout",
        observer = onStdoutChunk,
    )
    private val stderrCapture = BoundedStreamCapture(process.errorStream, captureLimitBytes, "piffbackup-stderr",
        firstLine = if (ownsProcessGroup) { line ->
            val id = line.removePrefix("__PIFFBACKUP_PROCESS_GROUP:").trim().toIntOrNull()
            if (line.startsWith("__PIFFBACKUP_PROCESS_GROUP:") && id != null && id > 1) groupId.set(id)
            groupReady.countDown()
            groupId.get() > 1
        } else null)

    init {
        stdoutCapture.start()
        stderrCapture.start()
    }

    fun cancel() {
        if (!cancellationRequested.compareAndSet(false, true)) return
        var interrupted = false
        if (ownsProcessGroup) {
            try { groupReady.await(100L, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { interrupted = true }
            signalGroup(android.system.OsConstants.SIGTERM)
        }
        process.destroy()
        val exited = try { process.waitFor(CANCEL_GRACE_SECONDS, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { interrupted = true; false }
        if (!exited) process.destroyForcibly()
        // A child can ignore TERM after its parent exits. Kill the owned group as well.
        if (ownsProcessGroup) signalGroup(android.system.OsConstants.SIGKILL)
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun signalGroup(signal: Int) {
        runCatching {
            val pid = groupId.get()
            // setsid creates a group owned by this process, never the application's group.
            if (pid > 1) android.system.Os.kill(-pid, signal)
        }
    }

    fun await(): NativeProcessResult = try {
        finishAfterExit(process.waitFor())
    } catch (interrupted: InterruptedException) {
        cancel()
        Thread.currentThread().interrupt()
        throw interrupted
    }

    fun await(timeoutMillis: Long): NativeProcessResult {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        return try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                timeoutReached.set(true)
                cancel()
            }
            finishAfterExit(process.waitFor())
        } catch (interrupted: InterruptedException) {
            cancel()
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }

    private fun finishAfterExit(exitCode: Int): NativeProcessResult {
        val stdoutDone = stdoutCapture.join(5000L)
        val stderrDone = stderrCapture.join(5000L)
        if (!stdoutDone || !stderrDone || !cancellationRequested.get() && (stdoutCapture.failed || stderrCapture.failed)) {
            timeoutReached.set(true)
            if (ownsProcessGroup) signalGroup(android.system.OsConstants.SIGKILL)
        }
        return NativeProcessResult(
            exitCode = exitCode,
            stdout = stdoutCapture.text(),
            stderr = stderrCapture.text(),
            stdoutTruncated = stdoutCapture.truncated,
            stderrTruncated = stderrCapture.truncated,
            cancelled = cancellationRequested.get(),
            timedOut = timeoutReached.get(),
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
    private val firstLine: ((String) -> Boolean)? = null,
) {
    private val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
    @Volatile var failed = false
        private set
    private val thread = Thread({
        try { drain() }
        catch (_: java.io.IOException) { failed = true; truncated = true }
    }, threadName).apply { isDaemon = true }
    @Volatile
    var truncated: Boolean = false
        private set

    fun start() = thread.start()

    fun join(timeout: Long): Boolean {
        thread.join(timeout)
        if (!thread.isAlive) return true
        truncated = true
        runCatching { input.close() }
        thread.join(500L)
        return false
    }

    fun text(): String = output.toString(StandardCharsets.UTF_8.name())

    private fun drain() {
        input.use { stream ->
            firstLine?.let { callback ->
                val header = ByteArrayOutputStream()
                while (header.size() < 128) {
                    val byte = stream.read(); if (byte < 0) break
                    header.write(byte); if (byte == 10) break
                }
                if (!callback(header.toString(StandardCharsets.US_ASCII.name()))) output.write(header.toByteArray())
            }
            val buffer = ByteArray(8 * 1024)
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
            val decoderBytes = java.nio.ByteBuffer.allocate(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) {
                    if (observer != null) {
                        decoderBytes.flip()
                        val characters = java.nio.CharBuffer.allocate(16 * 1024)
                        decoder.decode(decoderBytes, characters, true)
                        decoder.flush(characters)
                        characters.flip()
                        if (characters.hasRemaining()) runCatching { observer?.invoke(characters.toString()) }
                    }
                    return
                }
                observer?.let { callback ->
                    // Decode incrementally: a UTF-8 code point can span two pipe reads.
                    decoderBytes.put(buffer, 0, count)
                    decoderBytes.flip()
                    val characters = java.nio.CharBuffer.allocate(16 * 1024)
                    decoder.decode(decoderBytes, characters, false)
                    decoderBytes.compact()
                    characters.flip()
                    runCatching { callback(characters.toString()) }
                }
                val remaining = limit - output.size()
                if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                if (count > remaining.coerceAtLeast(0)) truncated = true
            }
        }
    }
}
