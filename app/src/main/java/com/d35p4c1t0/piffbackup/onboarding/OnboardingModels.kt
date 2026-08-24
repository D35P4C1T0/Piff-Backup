package com.d35p4c1t0.piffbackup.onboarding

data class StorageBoxEndpoint(
    val username: String,
    val hostname: String,
    val port: Int = HETZNER_SSH_PORT,
) {
    init {
        require(USERNAME.matches(username)) { "Invalid Storage Box username" }
        require(isValidHostname(hostname)) { "Invalid Storage Box hostname" }
        require(port == HETZNER_SSH_PORT) { "Storage Box SSH must use port 23" }
    }

    companion object {
        const val HETZNER_SSH_PORT = 23
        val USERNAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val HOST_LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

        fun create(username: String, advancedHostname: String?): StorageBoxEndpoint {
            val normalizedUsername = username.trim()
            val hostname = advancedHostname?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "$normalizedUsername.your-storagebox.de"
            return StorageBoxEndpoint(normalizedUsername, hostname)
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
) {
    init {
        require(PROFILE_ID.matches(profileId)) { "Invalid profile ID" }
        require(password.isNotEmpty() && password.size <= MAX_PASSWORD_CHARS) { "Invalid password" }
    }

    companion object {
        const val DEFAULT_PROFILE_ID = "primary"
        private const val MAX_PASSWORD_CHARS = 1024
        private val PROFILE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

enum class OnboardingProgress {
    PREPARING_KEY,
    CONNECTING_WITH_PASSWORD,
    INSTALLING_KEY,
    VERIFYING_KEY_AND_DESTINATION,
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
