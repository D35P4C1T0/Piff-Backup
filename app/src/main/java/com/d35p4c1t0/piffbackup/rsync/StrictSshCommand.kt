package com.d35p4c1t0.piffbackup.rsync

import java.io.File

data class StrictSshConfig(
    val username: String,
    val hostname: String,
    val port: Int,
    val identityFile: File,
    val sshHomeDirectory: File,
)

object StrictSshCommand {
    private val usernamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val hostnamePattern = Regex("(?=.{1,253}\\z)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")

    fun dbclientArguments(config: StrictSshConfig): List<String> {
        require(usernamePattern.matches(config.username)) { "Invalid SSH username" }
        require(hostnamePattern.matches(config.hostname)) { "Invalid SSH hostname" }
        require(config.port in 1..65535) { "Invalid SSH port" }
        require(config.identityFile.isAbsolute) { "Identity file must be absolute" }
        require(config.sshHomeDirectory.isAbsolute) { "SSH home must be absolute" }
        return listOf(
            "-p", config.port.toString(),
            "-i", config.identityFile.path,
            "-o", "StrictHostKeyChecking=yes",
            "-o", "BatchMode=yes",
            "-o", "ServerAliveInterval=15",
            "${config.username}@${config.hostname}",
        )
    }

    fun environment(config: StrictSshConfig): Map<String, String> = mapOf(
        "HOME" to config.sshHomeDirectory.path,
    )

    /** Rsync tokenizes its controlled remote-shell string itself. */
    fun rsyncRemoteShell(executable: File, config: StrictSshConfig): String {
        val tokens = listOf(executable.path) + dbclientArguments(config).dropLast(1)
        require(tokens.none { token -> token.any(Char::isWhitespace) }) {
            "SSH executable and option paths must not contain whitespace"
        }
        return tokens.joinToString(" ")
    }
}
