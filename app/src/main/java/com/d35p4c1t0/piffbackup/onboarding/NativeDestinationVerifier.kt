package com.d35p4c1t0.piffbackup.onboarding

import android.content.Context
import com.d35p4c1t0.piffbackup.rsync.NativeProcessRunner
import com.d35p4c1t0.piffbackup.rsync.NativeTool
import com.d35p4c1t0.piffbackup.rsync.NativeToolLocator
import com.d35p4c1t0.piffbackup.rsync.StrictSshCommand
import com.d35p4c1t0.piffbackup.rsync.StrictSshConfig
import java.io.File

object StorageBoxVerificationCommands {
    val AUTHENTICATION_CHECK = listOf("pwd")
    val DESTINATION_CHECK = listOf("ls", "-d", "Bianca/")
}

enum class DestinationVerification {
    VERIFIED,
    KEY_AUTHENTICATION_FAILED,
    DESTINATION_NOT_FOUND,
    TIMED_OUT,
}

fun interface StorageBoxDestinationVerifier {
    fun verify(
        endpoint: StorageBoxEndpoint,
        privateKey: File,
        sshHomeDirectory: File,
    ): DestinationVerification
}

class NativeStorageBoxDestinationVerifier(
    context: Context,
    private val runner: NativeProcessRunner = NativeProcessRunner(),
) : StorageBoxDestinationVerifier {
    private val sshClient = NativeToolLocator(context).require(NativeTool.SSH_CLIENT)

    override fun verify(
        endpoint: StorageBoxEndpoint,
        privateKey: File,
        sshHomeDirectory: File,
    ): DestinationVerification {
        val config = StrictSshConfig(
            username = endpoint.username,
            hostname = endpoint.hostname,
            port = endpoint.port,
            identityFile = privateKey,
            sshHomeDirectory = sshHomeDirectory,
        )
        val authentication = run(config, StorageBoxVerificationCommands.AUTHENTICATION_CHECK)
        if (authentication.timedOut) return DestinationVerification.TIMED_OUT
        if (authentication.exitCode != 0 || authentication.cancelled) {
            return DestinationVerification.KEY_AUTHENTICATION_FAILED
        }
        val destination = run(config, StorageBoxVerificationCommands.DESTINATION_CHECK)
        if (destination.timedOut) return DestinationVerification.TIMED_OUT
        if (destination.exitCode != 0 || destination.cancelled) {
            return DestinationVerification.DESTINATION_NOT_FOUND
        }
        return DestinationVerification.VERIFIED
    }

    private fun run(
        config: StrictSshConfig,
        remoteCommand: List<String>,
    ) = runner.start(
        command = listOf(sshClient.path) + StrictSshCommand.dbclientArguments(config) + remoteCommand,
        workingDirectory = config.sshHomeDirectory,
        environment = StrictSshCommand.environment(config),
    ).await(COMMAND_TIMEOUT_MILLIS)

    companion object {
        const val REMOTE_BASE = "Bianca"
        private const val COMMAND_TIMEOUT_MILLIS = 30_000L
    }
}
