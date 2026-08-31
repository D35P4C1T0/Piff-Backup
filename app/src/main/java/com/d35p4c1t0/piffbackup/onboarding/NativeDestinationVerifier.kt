package com.d35p4c1t0.piffbackup.onboarding

import android.content.Context
import android.util.Log
import com.d35p4c1t0.piffbackup.BuildConfig
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.rsync.NativeProcessRunner
import com.d35p4c1t0.piffbackup.rsync.NativeProcessResult
import com.d35p4c1t0.piffbackup.rsync.NativeTool
import com.d35p4c1t0.piffbackup.rsync.NativeToolLocator
import com.d35p4c1t0.piffbackup.rsync.StrictSshCommand
import com.d35p4c1t0.piffbackup.rsync.StrictSshConfig
import java.io.File

object StorageBoxVerificationCommands {
    val AUTHENTICATION_CHECK = listOf("pwd")

    fun destinationCheck(remoteBasePath: RemoteRelativePath): List<String> {
        requireValidStorageBoxBackupRoot(remoteBasePath)
        return listOf(
            "ls",
            "-d",
            remoteBasePath.pathWithTrailingSlash,
        )
    }
}

enum class DestinationVerification {
    VERIFIED,
    KEY_AUTHENTICATION_FAILED,
    DESTINATION_NOT_FOUND,
    TIMED_OUT,
}

interface StorageBoxDestinationVerifier {
    fun verifyAuthentication(
        endpoint: StorageBoxEndpoint,
        privateKey: File,
        sshHomeDirectory: File,
    ): DestinationVerification

    fun verify(
        endpoint: StorageBoxEndpoint,
        remoteBasePath: RemoteRelativePath,
        privateKey: File,
        sshHomeDirectory: File,
    ): DestinationVerification
}

class NativeStorageBoxDestinationVerifier(
    context: Context,
    private val runner: NativeProcessRunner = NativeProcessRunner(),
) : StorageBoxDestinationVerifier {
    private val sshClient = NativeToolLocator(context).require(NativeTool.SSH_CLIENT)

    override fun verifyAuthentication(
        endpoint: StorageBoxEndpoint,
        privateKey: File,
        sshHomeDirectory: File,
    ): DestinationVerification {
        val config = strictConfig(endpoint, privateKey, sshHomeDirectory)
        val authentication = run(config, StorageBoxVerificationCommands.AUTHENTICATION_CHECK)
        if (authentication.timedOut) return DestinationVerification.TIMED_OUT
        if (authentication.exitCode != 0 || authentication.cancelled) {
            logSafeFailure("authentication", authentication, config)
            return DestinationVerification.KEY_AUTHENTICATION_FAILED
        }
        return DestinationVerification.VERIFIED
    }

    override fun verify(
        endpoint: StorageBoxEndpoint,
        remoteBasePath: RemoteRelativePath,
        privateKey: File,
        sshHomeDirectory: File,
    ): DestinationVerification {
        val authentication = verifyAuthentication(endpoint, privateKey, sshHomeDirectory)
        if (authentication != DestinationVerification.VERIFIED) return authentication
        val config = strictConfig(endpoint, privateKey, sshHomeDirectory)
        val destination = run(config, StorageBoxVerificationCommands.destinationCheck(remoteBasePath))
        if (destination.timedOut) return DestinationVerification.TIMED_OUT
        if (destination.exitCode != 0 || destination.cancelled) {
            logSafeFailure("destination", destination, config)
            return DestinationVerification.DESTINATION_NOT_FOUND
        }
        return DestinationVerification.VERIFIED
    }

    private fun strictConfig(
        endpoint: StorageBoxEndpoint,
        privateKey: File,
        sshHomeDirectory: File,
    ) = StrictSshConfig(
        username = endpoint.username,
        hostname = endpoint.hostname,
        port = endpoint.port,
        identityFile = privateKey,
        sshHomeDirectory = sshHomeDirectory,
    )

    private fun run(
        config: StrictSshConfig,
        remoteCommand: List<String>,
    ) = runner.start(
        command = listOf(sshClient.path) + StrictSshCommand.dbclientArguments(config) + remoteCommand,
        workingDirectory = config.sshHomeDirectory,
        environment = StrictSshCommand.environment(config),
    ).await(COMMAND_TIMEOUT_MILLIS)

    private fun logSafeFailure(
        stage: String,
        result: NativeProcessResult,
        config: StrictSshConfig,
    ) {
        if (!BuildConfig.DEBUG) return
        val diagnostic = safeNativeSshDiagnostic(result.stderr, config)
        Log.w(
            LOG_TAG,
            "Native SSH $stage failed: exit=${result.exitCode}, " +
                "cancelled=${result.cancelled}, timedOut=${result.timedOut}, stderr=$diagnostic",
        )
    }

    companion object {
        const val LOG_TAG = "PiffBackupNativeSsh"
        private const val COMMAND_TIMEOUT_MILLIS = 30_000L
    }
}

internal fun safeNativeSshDiagnostic(stderr: String, config: StrictSshConfig): String {
    val redacted = stderr
        .replace(config.hostname, "<hostname>")
        .replace(config.username, "<username>")
        .replace(config.identityFile.path, "<identity>")
        .replace(config.sshHomeDirectory.path, "<ssh-home>")
    return redacted
        .take(512)
        .map { character ->
            if (character.isLetterOrDigit() || character in SAFE_DIAGNOSTIC_PUNCTUATION) {
                character
            } else {
                '?'
            }
        }
        .joinToString("")
        .ifBlank { "<empty>" }
}

private val SAFE_DIAGNOSTIC_PUNCTUATION = setOf(
    ' ', '\n', '\r', '\t', '.', ',', ':', ';', '-', '_', '/', '[', ']', '(', ')', '<', '>', '=', '+', '@',
)
