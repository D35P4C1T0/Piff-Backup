package com.d35p4c1t0.piffbackup.rsync

import android.content.Context
import java.io.File

data class ProbeReport(
    val successful: Boolean,
    val summary: String,
)

class NativeFeasibilityProbe(
    private val context: Context,
    private val runner: NativeProcessRunner = NativeProcessRunner(),
) {
    fun runLocalOnly(): ProbeReport {
        val locator = NativeToolLocator(context)
        val work = File(context.cacheDir, "native-spike").apply { mkdirs() }
        val source = File(work, "empty-source").apply { mkdirs() }
        val destination = File(work, "empty-destination").apply { mkdirs() }
        val report = StringBuilder()
        if (!runCheck(
                label = "rsync version",
                command = listOf(locator.require(NativeTool.RSYNC).path, "--version"),
                work = work,
                report = report,
            )
        ) {
            return ProbeReport(false, report.toString())
        }
        if (!runCheck(
                label = "SSH client version",
                command = listOf(locator.require(NativeTool.SSH_CLIENT).path, "-V"),
                work = work,
                report = report,
            )
        ) {
            return ProbeReport(false, report.toString())
        }

        val testKey = File(work, "disposable-ed25519-key")
        val testPublicKey = File(work, "disposable-ed25519-key.pub")
        if (!deleteIfPresent(testKey) || !deleteIfPresent(testPublicKey)) {
            report.append("key generation: could not clean an earlier disposable key\n")
            return ProbeReport(false, report.toString())
        }
        val keyCheckSucceeded = try {
            runCheck(
                label = "Ed25519 key generation",
                command = listOf(
                    locator.require(NativeTool.SSH_KEYGEN).path,
                    "-t", "ed25519",
                    "-f", testKey.path,
                ),
                work = work,
                report = report,
                includeFirstOutputLine = false,
            )
        } finally {
            deleteIfPresent(testKey)
            deleteIfPresent(testPublicKey)
        }
        if (!keyCheckSucceeded) return ProbeReport(false, report.toString())
        if (testKey.exists() || testPublicKey.exists()) {
            report.append("disposable key cleanup: failed\n")
            return ProbeReport(false, report.toString())
        }

        if (!runCheck(
                label = "isolated dry run",
                command = listOf(locator.require(NativeTool.RSYNC).path) +
                    AdoptionDryRunArguments.local(source, destination),
                work = work,
                report = report,
            )
        ) {
            return ProbeReport(false, report.toString())
        }
        return ProbeReport(true, report.toString())
    }

    private fun runCheck(
        label: String,
        command: List<String>,
        work: File,
        report: StringBuilder,
        includeFirstOutputLine: Boolean = true,
    ): Boolean {
        val result = runner.start(command, work).await()
        report.append(label).append(": exit ").append(result.exitCode)
            .append(" (").append(result.durationMillis).append(" ms)\n")
        if (includeFirstOutputLine) {
            val firstOutputLine = (result.stdout + result.stderr).lineSequence()
                .firstOrNull { it.isNotBlank() }
            if (firstOutputLine != null) report.append("  ").append(firstOutputLine).append('\n')
        }
        return result.exitCode == 0
    }

    private fun deleteIfPresent(file: File): Boolean = !file.exists() || file.delete()
}
