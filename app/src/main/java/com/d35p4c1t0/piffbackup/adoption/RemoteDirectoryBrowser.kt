package com.d35p4c1t0.piffbackup.adoption

import android.content.Context
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileEntity
import com.d35p4c1t0.piffbackup.onboarding.KnownHostStore
import com.d35p4c1t0.piffbackup.onboarding.OnboardingConnection
import com.d35p4c1t0.piffbackup.onboarding.OnboardingCredentialManager
import com.d35p4c1t0.piffbackup.onboarding.isValidStorageBoxBackupRoot
import com.d35p4c1t0.piffbackup.rsync.NativeProcessRunner
import com.d35p4c1t0.piffbackup.rsync.NativeTool
import com.d35p4c1t0.piffbackup.rsync.NativeToolLocator
import com.d35p4c1t0.piffbackup.rsync.RunningNativeProcess
import com.d35p4c1t0.piffbackup.rsync.StrictSshCommand
import com.d35p4c1t0.piffbackup.rsync.StrictSshConfig
import java.io.File

data class RemoteDirectory(
    val name: String,
    val relativePath: String,
)

interface RemoteDirectoryBrowser {
    fun listTopLevel(connection: OnboardingConnection): List<RemoteDirectory>
    fun list(profile: StorageBoxProfileEntity, parent: RemoteRelativePath): List<RemoteDirectory>
    fun cancel()
}

class NativeRemoteDirectoryBrowser(
    context: Context,
    private val credentials: OnboardingCredentialManager,
    private val knownHosts: KnownHostStore,
    private val runner: NativeProcessRunner = NativeProcessRunner(),
) : RemoteDirectoryBrowser {
    private val locator = NativeToolLocator(context)

    @Volatile
    private var running: RunningNativeProcess? = null

    override fun listTopLevel(connection: OnboardingConnection): List<RemoteDirectory> =
        listWithConnection(
            username = connection.endpoint.username,
            hostname = connection.endpoint.hostname,
            port = connection.endpoint.port,
            profileId = connection.profileId,
            credentialReference = connection.credentialReference,
            parent = null,
        ) { output -> RemoteDirectoryListParser.parseTopLevel(output) }

    override fun list(
        profile: StorageBoxProfileEntity,
        parent: RemoteRelativePath,
    ): List<RemoteDirectory> {
        require(profile.setupCompleted) { "Storage Box setup is incomplete" }
        require(RemoteRelativePath.create(profile.remoteBasePath).isSameOrAncestorOf(parent)) {
            "Remote browser escaped the configured backup root"
        }
        return listWithConnection(
            username = profile.username,
            hostname = profile.hostname,
            port = profile.port,
            profileId = profile.id,
            credentialReference = requireNotNull(profile.encryptedCredentialRef) {
                "Credential reference is missing"
            },
            parent = parent,
        ) { output -> RemoteDirectoryListParser.parse(parent, output) }
    }

    private fun listWithConnection(
        username: String,
        hostname: String,
        port: Int,
        profileId: String,
        credentialReference: String,
        parent: RemoteRelativePath?,
        parse: (String) -> List<RemoteDirectory>,
    ): List<RemoteDirectory> = credentials.withPrivateKey(credentialReference) { privateKey ->
            val ssh = StrictSshConfig(
                username = username,
                hostname = hostname,
                port = port,
                identityFile = privateKey,
                sshHomeDirectory = knownHosts.homeDirectory(profileId),
            )
            val process = runner.start(
                command = if (parent == null) {
                    RemoteDirectoryListCommand.buildTopLevel(
                        rsyncExecutable = locator.require(NativeTool.RSYNC),
                        sshExecutable = locator.require(NativeTool.SSH_CLIENT),
                        ssh = ssh,
                    )
                } else {
                    RemoteDirectoryListCommand.build(
                        rsyncExecutable = locator.require(NativeTool.RSYNC),
                        sshExecutable = locator.require(NativeTool.SSH_CLIENT),
                        ssh = ssh,
                        parent = parent,
                    )
                },
                workingDirectory = ssh.sshHomeDirectory,
                environment = StrictSshCommand.environment(ssh) + mapOf("LC_ALL" to "C"),
            )
            running = process
            val result = try {
                process.await(COMMAND_TIMEOUT_MILLIS)
            } finally {
                running = null
            }
            require(!result.timedOut && !result.cancelled && result.exitCode == 0) {
                "Remote folder listing failed"
            }
            require(!result.stdoutTruncated) { "Remote folder listing was too large" }
            parse(result.stdout)
        }

    override fun cancel() {
        running?.cancel()
    }

    private companion object {
        const val COMMAND_TIMEOUT_MILLIS = 60_000L
    }
}

object RemoteDirectoryListCommand {
    fun build(
        rsyncExecutable: File,
        sshExecutable: File,
        ssh: StrictSshConfig,
        parent: RemoteRelativePath,
    ): List<String> = buildForPath(rsyncExecutable, sshExecutable, ssh, parent.pathWithTrailingSlash)

    fun buildTopLevel(
        rsyncExecutable: File,
        sshExecutable: File,
        ssh: StrictSshConfig,
    ): List<String> = buildForPath(rsyncExecutable, sshExecutable, ssh, "./")

    private fun buildForPath(
        rsyncExecutable: File,
        sshExecutable: File,
        ssh: StrictSshConfig,
        remotePath: String,
    ): List<String> = listOf(
        rsyncExecutable.path,
        "--list-only",
        "--dirs",
        "--protect-args",
        "--outbuf=L",
        "--timeout=60",
        "--rsh=${StrictSshCommand.rsyncRemoteShell(sshExecutable, ssh)}",
        "--",
        "${ssh.username}@${ssh.hostname}:$remotePath",
    )
}

object RemoteDirectoryListParser {
    private val record = Regex(
        "^d.{9}\\s+[0-9][0-9,]*\\s+[0-9]{4}/[0-9]{2}/[0-9]{2}\\s+" +
            "[0-9]{2}:[0-9]{2}:[0-9]{2} (.*)\\r?$",
    )

    fun parse(parent: RemoteRelativePath, output: String): List<RemoteDirectory> =
        records(output)
            .map { name ->
                val path = RemoteRelativePath.create("${parent.value}/$name")
                RemoteDirectory(name, path.value)
            }
            .distinctBy { it.relativePath }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()

    fun parseTopLevel(output: String): List<RemoteDirectory> = records(output)
        .mapNotNull { name ->
            val path = RemoteRelativePath.create(name)
            path.takeIf(::isValidStorageBoxBackupRoot)?.let { RemoteDirectory(name, it.value) }
        }
        .distinctBy { it.relativePath }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .toList()

    private fun records(output: String): Sequence<String> = output.lineSequence()
            .mapNotNull { line ->
                val match = record.matchEntire(line) ?: return@mapNotNull null
                val name = match.groupValues[1].removeSuffix("/")
                if (!isSafeChildName(name)) return@mapNotNull null
                name
            }

    private fun isSafeChildName(value: String): Boolean =
        value.isNotEmpty() && value != "." && value != ".." && '/' !in value && '\u0000' !in value &&
            '\r' !in value && '\n' !in value
}
