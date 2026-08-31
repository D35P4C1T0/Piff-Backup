package com.d35p4c1t0.piffbackup

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d35p4c1t0.piffbackup.allfiles.AllFilesMetadataPlanner
import com.d35p4c1t0.piffbackup.allfiles.AllFilesMetadataSnapshotStore
import com.d35p4c1t0.piffbackup.allfiles.LocalMetadataLookup
import com.d35p4c1t0.piffbackup.adoption.AdoptionRsyncExecutor
import com.d35p4c1t0.piffbackup.adoption.InitialAdoptionCoordinator
import com.d35p4c1t0.piffbackup.adoption.InitialAdoptionError
import com.d35p4c1t0.piffbackup.adoption.InitialAdoptionResult
import com.d35p4c1t0.piffbackup.adoption.InitialFileListPlanner
import com.d35p4c1t0.piffbackup.adoption.InitialRootFileList
import com.d35p4c1t0.piffbackup.adoption.PrimaryTreeSelectionResolver
import com.d35p4c1t0.piffbackup.adoption.RemoteDirectory
import com.d35p4c1t0.piffbackup.adoption.RemoteDirectoryListParser
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.data.DurableBackupStore
import com.d35p4c1t0.piffbackup.data.DurableConfigurationStore
import com.d35p4c1t0.piffbackup.data.FolderMappingInput
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.data.PendingJobStatusValue
import com.d35p4c1t0.piffbackup.data.PiffBackupDatabase
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileInput
import com.d35p4c1t0.piffbackup.media.IncrementalFileListStore
import com.d35p4c1t0.piffbackup.media.MediaAccessScope
import com.d35p4c1t0.piffbackup.media.MediaGenerationWindow
import com.d35p4c1t0.piffbackup.media.MediaKind
import com.d35p4c1t0.piffbackup.media.MediaStoreRow
import com.d35p4c1t0.piffbackup.media.MediaStoreSnapshot
import com.d35p4c1t0.piffbackup.media.MediaStoreSource
import com.d35p4c1t0.piffbackup.onboarding.KnownHostStore
import com.d35p4c1t0.piffbackup.onboarding.OnboardingCredential
import com.d35p4c1t0.piffbackup.onboarding.OnboardingCredentialManager
import com.d35p4c1t0.piffbackup.rsync.AdoptionPreviewSummary
import com.d35p4c1t0.piffbackup.rsync.NativeProcessResult
import com.d35p4c1t0.piffbackup.rsync.NativeProcessRunner
import com.d35p4c1t0.piffbackup.rsync.NativeTool
import com.d35p4c1t0.piffbackup.rsync.NativeToolLocator
import com.d35p4c1t0.piffbackup.rsync.RsyncExecutionResult
import com.d35p4c1t0.piffbackup.rsync.RsyncExitKind
import com.d35p4c1t0.piffbackup.rsync.RsyncProgress
import com.d35p4c1t0.piffbackup.rsync.StrictSshConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InitialAdoptionInstrumentedTest {
    @Test
    fun primaryTreeTokenResolvesOnlyInsideConfiguredSharedRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shared = File(context.cacheDir, "picker-${System.nanoTime()}").apply { mkdirs() }
        val photos = File(shared, "Photos").apply { mkdirs() }
        try {
            val resolver = PrimaryTreeSelectionResolver(shared)
            val result = resolver.resolve(
                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APhotos"),
            )

            assertEquals("Photos", result.displayName)
            assertEquals(photos.canonicalPath, result.canonicalPath)
            assertEquals("Photos/", result.relativeMediaStorePrefix)
            assertThrows(IllegalArgumentException::class.java) {
                resolver.resolve(Uri.parse("content://cloud.example/tree/primary%3APhotos"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                resolver.resolve(
                    Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata"),
                )
            }
        } finally {
            shared.deleteRecursively()
        }
    }

    @Test
    fun packagedRsyncProducesParseableOneLevelDirectoryRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "remote-list-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(root, "Camera").mkdirs()
            File(root, "Trips 😄").mkdirs()
            File(root, "ordinary.txt").writeText("not a directory")
            val result = NativeProcessRunner().start(
                command = listOf(
                    NativeToolLocator(context).require(NativeTool.RSYNC).path,
                    "--list-only",
                    "--dirs",
                    "--",
                    root.path.trimEnd('/') + "/",
                ),
                workingDirectory = context.cacheDir,
                environment = mapOf("LC_ALL" to "C"),
            ).await(10_000L)

            assertEquals(0, result.exitCode)
            assertEquals(
                result.stdout,
                listOf(
                    RemoteDirectory("Camera", "Matteo/Camera"),
                    RemoteDirectory("Trips 😄", "Matteo/Trips 😄"),
                ),
                RemoteDirectoryListParser.parse(RemoteRelativePath.create("Matteo"), result.stdout),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun confirmedPreviewRecordsCheckpointOnlyAfterTransferSuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = Fixture(context)
        try {
            fixture.initialize()
            val preview = fixture.coordinator.preview(PROFILE_ID, fixture.mappingInputs)
            assertTrue(preview is InitialAdoptionResult.Success)
            assertEquals(1L, (preview as InitialAdoptionResult.Success).value.summary.itemsToUpload)
            assertEquals(null, fixture.store.checkpointForPlanning(PROFILE_ID, VOLUME))

            val completed = fixture.coordinator.confirm(preview.value.id)

            assertTrue(completed is InitialAdoptionResult.Success)
            assertEquals(10L, fixture.store.checkpointForPlanning(PROFILE_ID, VOLUME)?.successfulGeneration)
            val run = fixture.database.dao().backupRuns().single()
            assertEquals(1L, run.discoveredFiles)
            assertEquals(1L, run.uploadedFiles)
            assertEquals(5L, run.uploadedBytes)
            assertTrue(fixture.fileLists.listFiles().orEmpty().isEmpty())
            assertNotNull(fixture.rsync.lastTransferFile)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedTransferRetainsPreviewAndDoesNotAdvanceCheckpoint() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = Fixture(context, failTransfer = true)
        try {
            fixture.initialize()
            val preview = fixture.coordinator.preview(PROFILE_ID, fixture.mappingInputs)
                as InitialAdoptionResult.Success

            val result = fixture.coordinator.confirm(preview.value.id)

            assertEquals(
                InitialAdoptionResult.Failure(InitialAdoptionError.TRANSFER_FAILED),
                result,
            )
            assertEquals(null, fixture.store.checkpointForPlanning(PROFILE_ID, VOLUME))
            assertTrue(fixture.database.dao().backupRuns().isEmpty())
            assertTrue(fixture.fileLists.listFiles().orEmpty().isNotEmpty())
            assertNotNull(fixture.coordinator.currentPreview())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unchangedAllFilesPreviewCanBecomeDurableBackgroundWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = Fixture(context)
        try {
            fixture.initialize()
            val initial = fixture.coordinator.preview(PROFILE_ID, fixture.mappingInputs)
                as InitialAdoptionResult.Success
            fixture.coordinator.confirm(initial.value.id)
            val revision = fixture.configuration.profile(PROFILE_ID)?.configurationRevision

            val later = fixture.coordinator.preview(PROFILE_ID, fixture.mappingInputs)
                as InitialAdoptionResult.Success
            val prepared = fixture.coordinator.prepareDurableConfirmation(later.value.id)
                as InitialAdoptionResult.Success

            assertEquals(revision, fixture.configuration.profile(PROFILE_ID)?.configurationRevision)
            assertEquals(PendingJobStatusValue.PLANNED, prepared.value.job.status)
            assertTrue(prepared.value.job.id.startsWith("reconciliation-"))
            assertEquals(1L, prepared.value.job.totalFiles)
            assertTrue(prepared.value.roots.single().let { File(it.fileListPath).isFile })
            assertEquals(null, fixture.coordinator.currentPreview())
            assertEquals(10L, fixture.store.checkpointForPlanning(PROFILE_ID, VOLUME)?.successfulGeneration)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(private val context: Context, failTransfer: Boolean = false) {
        private val databaseName = "phase6-${System.nanoTime()}.db"
        private val root = File(context.cacheDir, "phase6-${System.nanoTime()}").apply { mkdirs() }
        private val volume = File(root, "shared").apply { mkdirs() }
        private val camera = File(volume, "Camera").apply { mkdirs() }
        private val empty = File(volume, "Empty").apply { mkdirs() }
        val fileLists = File(root, "lists").apply { mkdirs() }
        val database = PiffBackupDatabase.open(context, databaseName)
        val configuration = DurableConfigurationStore(database, volume)
        val store = DurableBackupStore(database, fileLists)
        val rsync = FakeRsync(failTransfer)
        val mappingInputs = listOf(
            FolderMappingInput(
                id = "z-camera",
                displayName = "Camera",
                treeUri = "content://com.android.externalstorage.documents/tree/primary%3ACamera",
                canonicalLocalPath = camera.path,
                relativeMediaStorePrefix = "Camera/",
                relativeRemotePath = "Matteo/Camera",
                mode = MappingModeValue.MEDIA_FAST,
            ),
            FolderMappingInput(
                id = "a-empty",
                displayName = "Empty",
                treeUri = "content://com.android.externalstorage.documents/tree/primary%3AEmpty",
                canonicalLocalPath = empty.path,
                relativeMediaStorePrefix = "Empty/",
                relativeRemotePath = "Matteo/Empty",
                mode = MappingModeValue.ALL_FILES,
            ),
        )
        val coordinator = InitialAdoptionCoordinator(
            configuration = configuration,
            durableBackup = store,
            mediaSource = FakeMediaSource(),
            fileLists = InitialFileListPlanner(
                source = FakeMediaSource(),
                store = IncrementalFileListStore(fileLists),
                volumeRoot = volume,
            ),
            allFiles = AllFilesMetadataPlanner(
                fileLists = IncrementalFileListStore(fileLists),
                snapshots = AllFilesMetadataSnapshotStore(fileLists),
                metadata = LocalMetadataLookup(store::localMetadata),
                volumeRoot = volume,
            ),
            credentials = FakeCredentials(File(root, "key").apply { writeText("fake") }),
            knownHosts = KnownHostStore(context),
            rsync = rsync,
            clock = { 1_000L },
        )

        suspend fun initialize() {
            configuration.saveProfile(
                StorageBoxProfileInput(
                    id = PROFILE_ID,
                    username = "u123456",
                    hostname = "u123456.your-storagebox.de",
                    remoteBasePath = "Matteo",
                    encryptedCredentialRef = "fake-ref",
                    pinnedHostKey = "ssh-ed25519 AQID",
                    setupCompleted = true,
                ),
            )
        }

        fun close() {
            coordinator.discardPreview()
            database.close()
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    }

    private class FakeMediaSource : MediaStoreSource {
        override fun snapshot(volumeName: String) = MediaStoreSnapshot(
            volumeName = VOLUME,
            version = "v1",
            generation = 10L,
            accessScope = MediaAccessScope.FULL,
        )

        override fun forEachChangedMedia(
            volumeName: String,
            window: MediaGenerationWindow,
            consumer: (MediaStoreRow) -> Unit,
        ) {
            consumer(MediaStoreRow(MediaKind.IMAGE, "Camera/", "photo.jpg", 2L, 2L, 5L))
        }
    }

    private class FakeCredentials(private val key: File) : OnboardingCredentialManager {
        override fun ensure(profileId: String, existingReference: String?) =
            OnboardingCredential("fake-ref", "ssh-ed25519 AQID piffbackup\n")

        override fun <T> withPrivateKey(reference: String, block: (File) -> T): T = block(key)
    }

    private class FakeRsync(private val failTransfer: Boolean) : AdoptionRsyncExecutor {
        var lastTransferFile: File? = null

        override fun preview(root: InitialRootFileList, ssh: StrictSshConfig) = result(
            AdoptionPreviewSummary(0L, 1L, 5L),
        )

        override fun transfer(
            root: InitialRootFileList,
            ssh: StrictSshConfig,
            onProgress: (RsyncProgress) -> Unit,
        ): RsyncExecutionResult {
            lastTransferFile = root.file
            onProgress(RsyncProgress(5L, 100))
            return if (failTransfer) result(null, RsyncExitKind.PARTIAL_TRANSFER_ERROR) else result(null)
        }

        override fun cancel() = Unit

        private fun result(
            summary: AdoptionPreviewSummary?,
            exitKind: RsyncExitKind = RsyncExitKind.SUCCESS,
        ) = RsyncExecutionResult(
            process = NativeProcessResult(
                exitCode = if (exitKind == RsyncExitKind.SUCCESS) 0 else 23,
                stdout = "",
                stderr = "",
                stdoutTruncated = false,
                stderrTruncated = false,
                cancelled = false,
                timedOut = false,
                durationMillis = 1L,
            ),
            exitKind = exitKind,
            latestProgress = null,
            adoptionPreviewSummary = summary,
        )
    }

    private companion object {
        const val PROFILE_ID = "primary"
        const val VOLUME = "external_primary"
    }
}
