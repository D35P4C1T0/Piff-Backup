package com.d35p4c1t0.piffbackup.onboarding

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

fun interface KnownHostWriter {
    fun write(profileId: String, hostname: String, pin: HostKeyPin): File
}

class KnownHostStore(context: Context) : KnownHostWriter {
    private val root = File(context.noBackupFilesDir, "ssh-homes").canonicalFile

    init {
        require(root.mkdirs() || root.isDirectory) { "SSH home storage unavailable" }
        Os.chmod(root.path, OWNER_DIRECTORY_MODE)
    }

    override fun write(profileId: String, hostname: String, pin: HostKeyPin): File {
        val home = homeDirectory(profileId)
        val sshDirectory = File(home, ".ssh").canonicalFile
        require(sshDirectory.parentFile == home) { "SSH directory escaped profile home" }
        require(sshDirectory.mkdirs() || sshDirectory.isDirectory) { "SSH directory unavailable" }
        Os.chmod(sshDirectory.path, OWNER_DIRECTORY_MODE)
        val destination = File(sshDirectory, "known_hosts").canonicalFile
        require(destination.parentFile == sshDirectory) { "Known-host file escaped SSH directory" }
        val temporary = File(sshDirectory, ".known_hosts.${UUID.randomUUID()}.tmp").canonicalFile
        try {
            FileOutputStream(temporary).use { output ->
                output.write(pin.knownHostsLine(hostname).toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            Os.chmod(temporary.path, OWNER_FILE_MODE)
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Os.chmod(destination.path, OWNER_FILE_MODE)
            return home
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun homeDirectory(profileId: String): File {
        require(SAFE_ID.matches(profileId)) { "Invalid profile ID" }
        val home = File(root, profileId).canonicalFile
        require(home.parentFile == root) { "SSH home escaped storage" }
        require(home.mkdirs() || home.isDirectory) { "SSH home unavailable" }
        Os.chmod(home.path, OWNER_DIRECTORY_MODE)
        return home
    }

    private companion object {
        const val OWNER_DIRECTORY_MODE = 448 // 0700
        const val OWNER_FILE_MODE = 384 // 0600
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
