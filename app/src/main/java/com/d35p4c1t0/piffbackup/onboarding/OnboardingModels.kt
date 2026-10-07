package com.d35p4c1t0.piffbackup.onboarding

import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath

data class StorageBoxEndpoint(
    val username: String,
    val hostname: String,
    val port: Int = HETZNER_SSH_PORT,
    val provider: com.d35p4c1t0.piffbackup.transport.RsyncTargetProvider = com.d35p4c1t0.piffbackup.transport.RsyncTargetProvider.HETZNER,
) {
    init {
        require(USERNAME.matches(username)) { "Invalid Storage Box username" }
        require(isValidHostname(hostname)) { "Invalid Storage Box hostname" }
        require(port in 1..65535 && (provider != com.d35p4c1t0.piffbackup.transport.RsyncTargetProvider.HETZNER || port == HETZNER_SSH_PORT)) { "Invalid SSH port for provider" }
    }

    companion object {
        const val HETZNER_SSH_PORT = 23
        val USERNAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val HOST_LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

        fun create(username: String, advancedHostname: String?,
            provider: com.d35p4c1t0.piffbackup.transport.RsyncTargetProvider = com.d35p4c1t0.piffbackup.transport.RsyncTargetProvider.HETZNER,
            port: Int = provider.defaultPort): StorageBoxEndpoint {
            val normalizedUsername = username.trim()
            val hostname = advancedHostname?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "$normalizedUsername.your-storagebox.de"
            require(provider == com.d35p4c1t0.piffbackup.transport.RsyncTargetProvider.HETZNER || !advancedHostname.isNullOrBlank())
            return StorageBoxEndpoint(normalizedUsername, hostname, port, provider)
        }

        fun isValidHostname(value: String): Boolean =
            value.length in 1..253 &&
                '\u0000' !in value &&
                value.split('.').all { label -> HOST_LABEL.matches(label) }
    }
}

data class OnboardingRequest(
    val profileId: String = DEFAULT_PROFILE_ID,
    val endpoint: StorageBoxEndpoint,
    val password: CharArray,
    val expectedFingerprint: String? = null,
    val allowHostKeyRotation: Boolean = false,
    val rotateClientKey: Boolean = false,
) {
    init {
        require(PROFILE_ID.matches(profileId)) { "Invalid profile ID" }
        require(password.isNotEmpty() && password.size <= MAX_PASSWORD_CHARS) { "Invalid password" }
        require(expectedFingerprint == null || Regex("SHA256:[A-Za-z0-9+/]{43}").matches(expectedFingerprint)) { "Invalid fingerprint" }
    }

    companion object {
        const val DEFAULT_PROFILE_ID = "primary"
        private const val MAX_PASSWORD_CHARS = 1024
        private val PROFILE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

internal fun requireValidStorageBoxBackupRoot(remoteBasePath: RemoteRelativePath) {
    require(isValidStorageBoxBackupRoot(remoteBasePath)) {
        "Backup folder must be a safe top-level Storage Box folder"
    }
}

internal fun isValidStorageBoxBackupRoot(remoteBasePath: RemoteRelativePath): Boolean =
    STORAGE_BOX_BACKUP_ROOT.matches(remoteBasePath.value)

private val STORAGE_BOX_BACKUP_ROOT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")

class OnboardingConnection internal constructor(
    val profileId: String,
    val endpoint: StorageBoxEndpoint,
    val hostFingerprint: String,
    internal val credentialReference: String,
    internal val pinnedHostKey: String,
    internal val enrollmentId: String = profileId,
)

enum class OnboardingProgress {
    PREPARING_KEY,
    CONNECTING_WITH_PASSWORD,
    INSTALLING_KEY,
    VERIFYING_KEY,
    VERIFYING_DESTINATION,
    SAVING,
}

enum class OnboardingErrorCode {
    INVALID_INPUT,
    NETWORK_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    HOST_KEY_CHANGED,
    KEY_INSTALL_FAILED,
    KEY_VERIFICATION_FAILED,
    DESTINATION_NOT_FOUND,
    SECURE_STORAGE_FAILED,
}

sealed interface OnboardingResult {
    data class Connected(val connection: OnboardingConnection) : OnboardingResult

    data class Success(
        val endpoint: StorageBoxEndpoint,
        val hostFingerprint: String,
        val remoteBasePath: String,
    ) : OnboardingResult

    data class Failure(val code: OnboardingErrorCode) : OnboardingResult
}

internal class OnboardingFailure(
    val code: OnboardingErrorCode,
    cause: Throwable? = null,
) : Exception(code.name, cause)
