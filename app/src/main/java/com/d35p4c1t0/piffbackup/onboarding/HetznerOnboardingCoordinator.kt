package com.d35p4c1t0.piffbackup.onboarding

import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
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
    @Volatile
    private var pendingConnection: OnboardingConnection? = null

    fun pendingConnection(): OnboardingConnection? = pendingConnection

    fun discardPendingConnection() {
        pendingConnection = null
    }

    suspend fun connect(
        request: OnboardingRequest,
        onProgress: (OnboardingProgress) -> Unit = {},
    ): OnboardingResult {
        pendingConnection = null
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
            onProgress(OnboardingProgress.VERIFYING_KEY)
            val verification = credentials.withPrivateKey(credential.reference) { privateKey ->
                destinationVerifier.verifyAuthentication(request.endpoint, privateKey, sshHome)
            }
            when (verification) {
                DestinationVerification.VERIFIED -> Unit
                DestinationVerification.KEY_AUTHENTICATION_FAILED -> {
                    throw OnboardingFailure(OnboardingErrorCode.KEY_VERIFICATION_FAILED)
                }
                DestinationVerification.DESTINATION_NOT_FOUND ->
                    throw OnboardingFailure(OnboardingErrorCode.KEY_VERIFICATION_FAILED)
                DestinationVerification.TIMED_OUT -> {
                    throw OnboardingFailure(OnboardingErrorCode.NETWORK_UNAVAILABLE)
                }
            }
            val connection = OnboardingConnection(
                profileId = request.profileId,
                endpoint = request.endpoint,
                hostFingerprint = capturedPin.sha256Fingerprint,
                credentialReference = credential.reference,
                pinnedHostKey = capturedPin.persistedValue,
            )
            pendingConnection = connection
            return OnboardingResult.Connected(connection)
        } catch (failure: OnboardingFailure) {
            return OnboardingResult.Failure(failure.code)
        } catch (_: Exception) {
            return OnboardingResult.Failure(OnboardingErrorCode.SECURE_STORAGE_FAILED)
        } finally {
            request.password.fill('\u0000')
        }
    }

    suspend fun selectDestination(
        remoteBasePath: RemoteRelativePath,
        onProgress: (OnboardingProgress) -> Unit = {},
    ): OnboardingResult {
        val connection = pendingConnection
            ?: return OnboardingResult.Failure(OnboardingErrorCode.SECURE_STORAGE_FAILED)
        return try {
            requireValidStorageBoxBackupRoot(remoteBasePath)
            onProgress(OnboardingProgress.VERIFYING_DESTINATION)
            val verification = credentials.withPrivateKey(connection.credentialReference) { privateKey ->
                destinationVerifier.verify(
                    connection.endpoint,
                    remoteBasePath,
                    privateKey,
                    knownHosts.write(
                        connection.profileId,
                        connection.endpoint.hostname,
                        HostKeyPin.parse(connection.pinnedHostKey),
                    ),
                )
            }
            when (verification) {
                DestinationVerification.VERIFIED -> Unit
                DestinationVerification.KEY_AUTHENTICATION_FAILED ->
                    throw OnboardingFailure(OnboardingErrorCode.KEY_VERIFICATION_FAILED)
                DestinationVerification.DESTINATION_NOT_FOUND ->
                    throw OnboardingFailure(OnboardingErrorCode.DESTINATION_NOT_FOUND)
                DestinationVerification.TIMED_OUT ->
                    throw OnboardingFailure(OnboardingErrorCode.NETWORK_UNAVAILABLE)
            }
            val pin = HostKeyPin.parse(connection.pinnedHostKey)
            onProgress(OnboardingProgress.SAVING)
            profiles.save(profileInput(connection, remoteBasePath, pin))
            pendingConnection = null
            OnboardingResult.Success(
                endpoint = connection.endpoint,
                hostFingerprint = connection.hostFingerprint,
                remoteBasePath = remoteBasePath.value,
            )
        } catch (failure: OnboardingFailure) {
            OnboardingResult.Failure(failure.code)
        } catch (_: IllegalArgumentException) {
            OnboardingResult.Failure(OnboardingErrorCode.INVALID_INPUT)
        } catch (_: Exception) {
            OnboardingResult.Failure(OnboardingErrorCode.SECURE_STORAGE_FAILED)
        }
    }

    private fun profileInput(
        connection: OnboardingConnection,
        remoteBasePath: RemoteRelativePath,
        pin: HostKeyPin,
    ) = StorageBoxProfileInput(
        id = connection.profileId,
        username = connection.endpoint.username,
        hostname = connection.endpoint.hostname,
        port = connection.endpoint.port,
        remoteBasePath = remoteBasePath.value,
        encryptedCredentialRef = connection.credentialReference,
        pinnedHostKey = pin.persistedValue,
        setupCompleted = true,
    )
}
