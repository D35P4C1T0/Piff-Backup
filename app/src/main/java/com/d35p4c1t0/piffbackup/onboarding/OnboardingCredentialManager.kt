package com.d35p4c1t0.piffbackup.onboarding

import android.content.Context
import android.system.Os
import com.d35p4c1t0.piffbackup.rsync.NativeProcessRunner
import com.d35p4c1t0.piffbackup.rsync.NativeTool
import com.d35p4c1t0.piffbackup.rsync.NativeToolLocator
import com.d35p4c1t0.piffbackup.security.EncryptedCredentialVault
import java.io.File
import java.util.Base64
import java.util.UUID

data class OnboardingCredential(
    val reference: String,
    val publicKeyLine: String,
)

interface OnboardingCredentialManager {
    fun ensure(profileId: String, existingReference: String?): OnboardingCredential
    fun <T> withPrivateKey(reference: String, block: (File) -> T): T
}

class NativeOnboardingCredentialManager(
    context: Context,
    private val vault: EncryptedCredentialVault,
    private val runner: NativeProcessRunner = NativeProcessRunner(),
) : OnboardingCredentialManager {
    private val locator = NativeToolLocator(context)
    private val workRoot = File(context.noBackupFilesDir, "onboarding-keygen").canonicalFile

    init {
        require(workRoot.mkdirs() || workRoot.isDirectory) { "Key-generation storage unavailable" }
        Os.chmod(workRoot.path, OWNER_DIRECTORY_MODE)
        cleanupAbandonedGeneratedKeys()
    }

    fun cleanupAbandonedGeneratedKeys(): Int {
        var deleted = 0
        workRoot.listFiles().orEmpty().forEach { candidate ->
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (
                canonical.parentFile == workRoot &&
                canonical.name.startsWith("key-") &&
                canonical.isFile &&
                canonical.delete()
            ) {
                deleted++
            }
        }
        return deleted
    }

    override fun ensure(profileId: String, existingReference: String?): OnboardingCredential {
        val reference = existingReference?.takeIf(vault::contains) ?: vault.referenceFor(profileId)
        if (!vault.contains(reference)) generateAndStore(reference)
        val publicKey = vault.withDecryptedKey(reference, ::derivePublicKey)
        return OnboardingCredential(reference, publicKey)
    }

    override fun <T> withPrivateKey(reference: String, block: (File) -> T): T =
        vault.withDecryptedKey(reference, block)

    private fun generateAndStore(reference: String) {
        val privateKey = File(workRoot, "key-${UUID.randomUUID()}").canonicalFile
        require(privateKey.parentFile == workRoot) { "Generated key escaped storage" }
        val publicKey = File(privateKey.path + ".pub")
        try {
            val result = runner.start(
                command = listOf(
                    locator.require(NativeTool.SSH_KEYGEN).path,
                    "-t", "ed25519",
                    "-f", privateKey.path,
                    "-C", PUBLIC_KEY_COMMENT,
                ),
                workingDirectory = workRoot,
            ).await(KEY_TOOL_TIMEOUT_MILLIS)
            if (result.exitCode != 0 || result.cancelled || result.timedOut || !privateKey.isFile) {
                throw OnboardingFailure(OnboardingErrorCode.SECURE_STORAGE_FAILED)
            }
            Os.chmod(privateKey.path, OWNER_FILE_MODE)
            vault.store(reference, privateKey)
        } catch (failure: OnboardingFailure) {
            throw failure
        } catch (failure: Exception) {
            throw OnboardingFailure(OnboardingErrorCode.SECURE_STORAGE_FAILED, failure)
        } finally {
            if (privateKey.exists()) privateKey.delete()
            if (publicKey.exists()) publicKey.delete()
        }
    }

    private fun derivePublicKey(privateKey: File): String {
        val result = runner.start(
            command = listOf(
                locator.require(NativeTool.SSH_KEYGEN).path,
                "-y",
                "-f", privateKey.path,
            ),
            workingDirectory = workRoot,
        ).await(KEY_TOOL_TIMEOUT_MILLIS)
        if (result.exitCode != 0 || result.cancelled || result.timedOut || result.stdoutTruncated) {
            throw OnboardingFailure(OnboardingErrorCode.SECURE_STORAGE_FAILED)
        }
        val candidate = result.stdout.lineSequence().firstOrNull { it.startsWith("ssh-ed25519 ") }
            ?: throw OnboardingFailure(OnboardingErrorCode.SECURE_STORAGE_FAILED)
        val parts = candidate.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 2 || parts[0] != "ssh-ed25519") {
            throw OnboardingFailure(OnboardingErrorCode.SECURE_STORAGE_FAILED)
        }
        val decoded = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull()
        if (decoded == null || decoded.isEmpty() || decoded.size > MAX_PUBLIC_KEY_BYTES) {
            throw OnboardingFailure(OnboardingErrorCode.SECURE_STORAGE_FAILED)
        }
        decoded.fill(0)
        return "ssh-ed25519 ${parts[1]} $PUBLIC_KEY_COMMENT\n"
    }

    private companion object {
        const val PUBLIC_KEY_COMMENT = "piffbackup"
        const val KEY_TOOL_TIMEOUT_MILLIS = 15_000L
        const val MAX_PUBLIC_KEY_BYTES = 1024
        const val OWNER_DIRECTORY_MODE = 448 // 0700
        const val OWNER_FILE_MODE = 384 // 0600
    }
}
