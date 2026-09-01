package com.d35p4c1t0.piffbackup.onboarding

import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileEntity
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HetznerOnboardingCoordinatorTest {
    @Test
    fun `connection stays temporary until selected destination is verified`() = runBlocking {
        val fixture = Fixture(DestinationVerification.VERIFIED)
        val password = "secret".toCharArray()

        val connected = fixture.coordinator.connect(
            OnboardingRequest(endpoint = ENDPOINT, password = password),
            fixture.progress::add,
        )

        assertTrue(connected is OnboardingResult.Connected)
        assertTrue(password.all { it == '\u0000' })
        assertTrue(fixture.profiles.saved.isEmpty())
        assertNotNull(fixture.coordinator.pendingConnection())
        assertTrue(OnboardingProgress.VERIFYING_KEY in fixture.progress)

        val completed = fixture.coordinator.selectDestination(TEST_REMOTE_BASE, fixture.progress::add)

        assertTrue(completed is OnboardingResult.Success)
        assertEquals(1, fixture.profiles.saved.size)
        assertTrue(fixture.profiles.saved.single().setupCompleted)
        assertEquals("keystore:v1:primary", fixture.profiles.saved.single().encryptedCredentialRef)
        assertEquals(PIN.persistedValue, fixture.profiles.saved.single().pinnedHostKey)
        assertEquals("Backups", fixture.profiles.saved.single().remoteBasePath)
        assertEquals(TEST_REMOTE_BASE.value, fixture.verifier.remoteBasePath?.value)
        assertTrue(OnboardingProgress.VERIFYING_DESTINATION in fixture.progress)
        assertEquals(null, fixture.coordinator.pendingConnection())
    }

    @Test
    fun `missing destination does not replace the durable profile`() = runBlocking {
        val fixture = Fixture(DestinationVerification.DESTINATION_NOT_FOUND)
        fixture.coordinator.connect(OnboardingRequest(endpoint = ENDPOINT, password = "secret".toCharArray()))

        val result = fixture.coordinator.selectDestination(TEST_REMOTE_BASE)

        assertEquals(OnboardingResult.Failure(OnboardingErrorCode.DESTINATION_NOT_FOUND), result)
        assertTrue(fixture.profiles.saved.isEmpty())
        assertNotNull(fixture.coordinator.pendingConnection())
    }

    @Test
    fun `same endpoint requires its existing host pin`() = runBlocking {
        val fixture = Fixture(DestinationVerification.VERIFIED)
        fixture.profiles.current = fixture.profiles.entity(
            StorageBoxProfileInput(
                id = "primary",
                username = ENDPOINT.username,
                hostname = ENDPOINT.hostname,
                remoteBasePath = TEST_REMOTE_BASE.value,
                pinnedHostKey = PIN.persistedValue,
                encryptedCredentialRef = "keystore:v1:primary",
                setupCompleted = true,
            ),
        )

        fixture.coordinator.connect(OnboardingRequest(endpoint = ENDPOINT, password = "secret".toCharArray()))

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
                remoteBasePath = TEST_REMOTE_BASE.value,
                pinnedHostKey = "not-a-valid-pin",
                encryptedCredentialRef = "keystore:v1:primary",
                setupCompleted = true,
            ),
        )

        val result = fixture.coordinator.connect(
            OnboardingRequest(endpoint = ENDPOINT, password = "secret".toCharArray()),
        )

        assertEquals(OnboardingResult.Failure(OnboardingErrorCode.HOST_KEY_CHANGED), result)
        assertFalse(fixture.installer.called)
        assertTrue(fixture.profiles.saved.isEmpty())
    }

    private class Fixture(destinationVerification: DestinationVerification) {
        val profiles = FakeProfiles()
        val installer = FakeInstaller()
        val verifier = FakeDestinationVerifier(destinationVerification)
        val progress = mutableListOf<OnboardingProgress>()
        val coordinator = HetznerOnboardingCoordinator(
            profiles = profiles,
            credentials = FakeCredentials(),
            passwordInstaller = installer,
            knownHosts = KnownHostWriter { _, _, _ -> WORK_DIRECTORY },
            destinationVerifier = verifier,
        )
    }

    private class FakeDestinationVerifier(
        private val destinationResult: DestinationVerification,
    ) : StorageBoxDestinationVerifier {
        var remoteBasePath: RemoteRelativePath? = null

        override fun verifyAuthentication(
            endpoint: StorageBoxEndpoint,
            privateKey: File,
            sshHomeDirectory: File,
        ) = DestinationVerification.VERIFIED

        override fun verify(
            endpoint: StorageBoxEndpoint,
            remoteBasePath: RemoteRelativePath,
            privateKey: File,
            sshHomeDirectory: File,
        ): DestinationVerification {
            this.remoteBasePath = remoteBasePath
            return destinationResult
        }
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
        val TEST_REMOTE_BASE = RemoteRelativePath.create("Backups")
        val PIN = HostKeyPin.parse("ssh-ed25519 AQID")
        val WORK_DIRECTORY: File = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val WORK_KEY: File = Files.createTempFile("piffbackup-fake-key", ".tmp").toFile()
    }
}
