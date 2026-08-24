package com.d35p4c1t0.piffbackup.onboarding

import com.d35p4c1t0.piffbackup.data.StorageBoxProfileEntity
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HetznerOnboardingCoordinatorTest {
    @Test
    fun `successful setup persists incomplete state before native verification`() = runBlocking {
        val fixture = Fixture(DestinationVerification.VERIFIED)
        val password = "secret".toCharArray()

        val result = fixture.coordinator.onboard(
            OnboardingRequest(endpoint = ENDPOINT, password = password),
            fixture.progress::add,
        )

        assertTrue(result is OnboardingResult.Success)
        assertTrue(password.all { it == '\u0000' })
        assertEquals(2, fixture.profiles.saved.size)
        assertFalse(fixture.profiles.saved.first().setupCompleted)
        assertTrue(fixture.profiles.saved.last().setupCompleted)
        assertEquals("keystore:v1:primary", fixture.profiles.saved.last().encryptedCredentialRef)
        assertEquals(PIN.persistedValue, fixture.profiles.saved.last().pinnedHostKey)
        assertEquals("Bianca", fixture.profiles.saved.last().remoteBasePath)
        assertTrue(OnboardingProgress.VERIFYING_KEY_AND_DESTINATION in fixture.progress)
    }

    @Test
    fun `missing destination never marks setup complete`() = runBlocking {
        val fixture = Fixture(DestinationVerification.DESTINATION_NOT_FOUND)

        val result = fixture.coordinator.onboard(
            OnboardingRequest(endpoint = ENDPOINT, password = "secret".toCharArray()),
        )

        assertEquals(
            OnboardingResult.Failure(OnboardingErrorCode.DESTINATION_NOT_FOUND),
            result,
        )
        assertEquals(1, fixture.profiles.saved.size)
        assertFalse(fixture.profiles.saved.single().setupCompleted)
    }

    @Test
    fun `same endpoint requires its existing host pin`() = runBlocking {
        val fixture = Fixture(DestinationVerification.VERIFIED)
        fixture.profiles.current = fixture.profiles.entity(
            StorageBoxProfileInput(
                id = "primary",
                username = ENDPOINT.username,
                hostname = ENDPOINT.hostname,
                pinnedHostKey = PIN.persistedValue,
                encryptedCredentialRef = "keystore:v1:primary",
                setupCompleted = true,
            ),
        )

        fixture.coordinator.onboard(
            OnboardingRequest(endpoint = ENDPOINT, password = "secret".toCharArray()),
        )

        assertTrue(requireNotNull(fixture.installer.expectedPin).securelyMatches(PIN))
    }

    @Test
    fun `corrupt stored host pin fails closed before connecting`() = runBlocking {
        val fixture = Fixture(DestinationVerification.VERIFIED)
        fixture.profiles.current = fixture.profiles.entity(
            StorageBoxProfileInput(
                id = "primary",
                username = ENDPOINT.username,
                hostname = ENDPOINT.hostname,
                pinnedHostKey = "not-a-valid-pin",
                encryptedCredentialRef = "keystore:v1:primary",
                setupCompleted = true,
            ),
        )

        val result = fixture.coordinator.onboard(
            OnboardingRequest(endpoint = ENDPOINT, password = "secret".toCharArray()),
        )

        assertEquals(OnboardingResult.Failure(OnboardingErrorCode.HOST_KEY_CHANGED), result)
        assertFalse(fixture.installer.called)
        assertTrue(fixture.profiles.saved.isEmpty())
    }

    private class Fixture(verification: DestinationVerification) {
        val profiles = FakeProfiles()
        val installer = FakeInstaller()
        val progress = mutableListOf<OnboardingProgress>()
        val coordinator = HetznerOnboardingCoordinator(
            profiles = profiles,
            credentials = FakeCredentials(),
            passwordInstaller = installer,
            knownHosts = KnownHostWriter { _, _, _ -> WORK_DIRECTORY },
            destinationVerifier = StorageBoxDestinationVerifier { _, _, _ -> verification },
        )
    }

    private class FakeProfiles : OnboardingProfileStore {
        var current: StorageBoxProfileEntity? = null
        val saved = mutableListOf<StorageBoxProfileInput>()

        override suspend fun profile(profileId: String): StorageBoxProfileEntity? = current

        override suspend fun save(input: StorageBoxProfileInput): StorageBoxProfileEntity {
            saved += input
            return entity(input).also { current = it }
        }

        fun entity(input: StorageBoxProfileInput) = StorageBoxProfileEntity(
            id = input.id,
            username = input.username,
            hostname = input.hostname,
            port = input.port,
            remoteBasePath = input.remoteBasePath,
            encryptedCredentialRef = input.encryptedCredentialRef,
            pinnedHostKey = input.pinnedHostKey,
            setupCompleted = input.setupCompleted,
            configurationRevision = (current?.configurationRevision ?: -1L) + 1L,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
    }

    private class FakeCredentials : OnboardingCredentialManager {
        override fun ensure(profileId: String, existingReference: String?) = OnboardingCredential(
            reference = existingReference ?: "keystore:v1:$profileId",
            publicKeyLine = "ssh-ed25519 AQID piffbackup\n",
        )

        override fun <T> withPrivateKey(reference: String, block: (File) -> T): T = block(WORK_KEY)
    }

    private class FakeInstaller : PasswordKeyInstaller {
        var expectedPin: HostKeyPin? = null
        var called = false

        override fun install(
            endpoint: StorageBoxEndpoint,
            password: CharArray,
            publicKeyLine: String,
            expectedPin: HostKeyPin?,
            onProgress: (OnboardingProgress) -> Unit,
        ): HostKeyPin {
            called = true
            this.expectedPin = expectedPin
            onProgress(OnboardingProgress.CONNECTING_WITH_PASSWORD)
            onProgress(OnboardingProgress.INSTALLING_KEY)
            return PIN
        }
    }

    private companion object {
        val ENDPOINT = StorageBoxEndpoint("u123456", "u123456.your-storagebox.de")
        val PIN = HostKeyPin.parse("ssh-ed25519 AQID")
        val WORK_DIRECTORY: File = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val WORK_KEY: File = Files.createTempFile("piffbackup-fake-key", ".tmp").toFile()
    }
}
