package com.d35p4c1t0.piffbackup.onboarding

import com.d35p4c1t0.piffbackup.data.DurableConfigurationStore
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileEntity
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileInput

interface OnboardingProfileStore {
    suspend fun profile(profileId: String): StorageBoxProfileEntity?
    suspend fun save(input: StorageBoxProfileInput): StorageBoxProfileEntity
}

class RoomOnboardingProfileStore(
    private val configuration: DurableConfigurationStore,
) : OnboardingProfileStore {
    override suspend fun profile(profileId: String): StorageBoxProfileEntity? =
        configuration.profile(profileId)

    override suspend fun save(input: StorageBoxProfileInput): StorageBoxProfileEntity =
        configuration.saveProfile(input)
}

class HetznerOnboardingCoordinator(
    private val profiles: OnboardingProfileStore,
    private val credentials: OnboardingCredentialManager,
    private val passwordInstaller: PasswordKeyInstaller,
    private val knownHosts: KnownHostWriter,
    private val destinationVerifier: StorageBoxDestinationVerifier,
) {
    suspend fun onboard(
        request: OnboardingRequest,
        onProgress: (OnboardingProgress) -> Unit = {},
    ): OnboardingResult {
        try {
            onProgress(OnboardingProgress.PREPARING_KEY)
            val existing = profiles.profile(request.profileId)
            val credential = credentials.ensure(request.profileId, existing?.encryptedCredentialRef)
            val expectedPin = existing
                ?.takeIf {
                    it.hostname == request.endpoint.hostname &&
                        it.port == request.endpoint.port &&
                        it.pinnedHostKey != null
                }
                ?.pinnedHostKey
                ?.let { persistedPin ->
                    try {
                        HostKeyPin.parse(persistedPin)
                    } catch (exception: IllegalArgumentException) {
                        throw OnboardingFailure(OnboardingErrorCode.HOST_KEY_CHANGED, exception)
                    }
                }
            val capturedPin = passwordInstaller.install(
                endpoint = request.endpoint,
                password = request.password,
                publicKeyLine = credential.publicKeyLine,
                expectedPin = expectedPin,
                onProgress = onProgress,
            )
            val sshHome = knownHosts.write(request.profileId, request.endpoint.hostname, capturedPin)
            onProgress(OnboardingProgress.SAVING)
            profiles.save(profileInput(request, credential.reference, capturedPin, setupCompleted = false))

            onProgress(OnboardingProgress.VERIFYING_KEY_AND_DESTINATION)
            val verification = credentials.withPrivateKey(credential.reference) { privateKey ->
                destinationVerifier.verify(request.endpoint, request.remoteBasePath, privateKey, sshHome)
            }
            when (verification) {
                DestinationVerification.VERIFIED -> Unit
                DestinationVerification.KEY_AUTHENTICATION_FAILED -> {
                    throw OnboardingFailure(OnboardingErrorCode.KEY_VERIFICATION_FAILED)
                }
                DestinationVerification.DESTINATION_NOT_FOUND -> {
                    throw OnboardingFailure(OnboardingErrorCode.DESTINATION_NOT_FOUND)
                }
                DestinationVerification.TIMED_OUT -> {
                    throw OnboardingFailure(OnboardingErrorCode.NETWORK_UNAVAILABLE)
                }
            }

            onProgress(OnboardingProgress.SAVING)
            profiles.save(profileInput(request, credential.reference, capturedPin, setupCompleted = true))
            return OnboardingResult.Success(
                endpoint = request.endpoint,
                hostFingerprint = capturedPin.sha256Fingerprint,
                remoteBasePath = request.remoteBasePath.value,
            )
        } catch (failure: OnboardingFailure) {
            return OnboardingResult.Failure(failure.code)
        } catch (_: Exception) {
            return OnboardingResult.Failure(OnboardingErrorCode.SECURE_STORAGE_FAILED)
        } finally {
            request.password.fill('\u0000')
        }
    }

    private fun profileInput(
        request: OnboardingRequest,
        credentialReference: String,
        pin: HostKeyPin,
        setupCompleted: Boolean,
    ) = StorageBoxProfileInput(
        id = request.profileId,
        username = request.endpoint.username,
        hostname = request.endpoint.hostname,
        port = request.endpoint.port,
        remoteBasePath = request.remoteBasePath.value,
        encryptedCredentialRef = credentialReference,
        pinnedHostKey = pin.persistedValue,
        setupCompleted = setupCompleted,
    )
}
