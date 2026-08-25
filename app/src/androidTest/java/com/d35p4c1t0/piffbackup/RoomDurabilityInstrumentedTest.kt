package com.d35p4c1t0.piffbackup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.data.DurableBackupStore
import com.d35p4c1t0.piffbackup.data.DurableConfigurationStore
import com.d35p4c1t0.piffbackup.data.EpochMillisClock
import com.d35p4c1t0.piffbackup.data.FolderMappingInput
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.data.PendingJobStatusValue
import com.d35p4c1t0.piffbackup.data.PendingRootStatusValue
import com.d35p4c1t0.piffbackup.data.PiffBackupDatabase
import com.d35p4c1t0.piffbackup.data.RootExecutionOutcome
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileInput
import com.d35p4c1t0.piffbackup.media.MediaGenerationWindow
import com.d35p4c1t0.piffbackup.media.MediaPlanningResult
import com.d35p4c1t0.piffbackup.media.MediaStoreCheckpoint
import com.d35p4c1t0.piffbackup.media.MediaStoreMapping
import com.d35p4c1t0.piffbackup.media.MediaStoreSnapshot
import com.d35p4c1t0.piffbackup.media.PlannedMediaTransfer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoomDurabilityInstrumentedTest {
    @Test
    fun runningJobSurvivesDatabaseReopenAndBecomesRetryable() = withFixture { fixture ->
        val pending = fixture.createPendingJob()
        fixture.store.markRootRunning(pending.job.id, MAPPING_CAMERA)
        fixture.reopenDatabase()

        val recovered = fixture.store.recoverOnLaunch()

        assertEquals(1, recovered.size)
        assertEquals(PendingJobStatusValue.RETRYABLE, recovered.single().job.status)
        assertEquals(PendingRootStatusValue.RETRYABLE, recovered.single().roots.first().status)
        assertEquals(10L, recovered.single().job.previousGeneration)
        assertEquals(20L, recovered.single().job.targetGeneration)
        assertTrue(File(recovered.single().roots.first().fileListPath).isFile)
    }

    @Test
    fun checkpointAdvancesOnlyAfterEveryRootSucceedsAndCleanupIsExact() = withFixture { fixture ->
        val pending = fixture.createPendingJob()
        fixture.store.markRootRunning(pending.job.id, MAPPING_CAMERA)
        fixture.store.recordRootOutcome(
            pending.job.id,
            MAPPING_CAMERA,
            RootExecutionOutcome.SUCCESS,
            completedFiles = 2L,
            completedBytes = 20L,
            rsyncExitCode = 0,
        )
        assertEquals(10L, fixture.store.checkpointForPlanning(PROFILE_ID, VOLUME)?.successfulGeneration)

        fixture.store.markRootRunning(pending.job.id, MAPPING_MOVIES)
        val completed = fixture.store.recordRootOutcome(
            pending.job.id,
            MAPPING_MOVIES,
            RootExecutionOutcome.SUCCESS,
            completedFiles = 1L,
            completedBytes = 30L,
            rsyncExitCode = 0,
        )

        assertEquals(PendingJobStatusValue.SUCCEEDED, completed.job.status)
        assertEquals(20L, fixture.store.checkpointForPlanning(PROFILE_ID, VOLUME)?.successfulGeneration)
        assertEquals(1, fixture.database.dao().backupRuns().size)
        assertEquals(completed.job.id, fixture.store.latestSuccessfulRun(PROFILE_ID)?.id)
        val listPaths = completed.roots.map { File(it.fileListPath) }
        assertTrue(listPaths.all { it.exists() })

        assertEquals(1, fixture.store.cleanupSucceededJobs())
        assertTrue(listPaths.none { it.exists() })
        assertNull(fixture.store.pendingJob(pending.job.id))
        assertEquals(1, fixture.database.dao().backupRuns().size)
        assertTrue(fixture.work.resolve("unrelated.keep").isFile)
    }

    @Test
    fun cancelledRootRetainsCheckpointAndPendingWindow() = withFixture { fixture ->
        val pending = fixture.createPendingJob()
        fixture.store.markRootRunning(pending.job.id, MAPPING_CAMERA)

        val paused = fixture.store.recordRootOutcome(
            pending.job.id,
            MAPPING_CAMERA,
            RootExecutionOutcome.CANCELLED,
            completedFiles = 1L,
            completedBytes = 5L,
            rsyncExitCode = 20,
            sanitizedErrorCode = "USER_CANCELLED",
        )

        assertEquals(PendingJobStatusValue.PAUSED, paused.job.status)
        assertEquals(10L, fixture.store.checkpointForPlanning(PROFILE_ID, VOLUME)?.successfulGeneration)
        assertEquals(10L, paused.job.previousGeneration)
        assertEquals(20L, paused.job.targetGeneration)
        assertTrue(paused.roots.all { File(it.fileListPath).exists() })
    }

    @Test
    fun missingPendingFileListRequiresReconciliationAfterRestart() = withFixture { fixture ->
        val pending = fixture.createPendingJob()
        File(pending.roots.first().fileListPath).delete()
        fixture.reopenDatabase()

        assertTrue(fixture.store.recoverOnLaunch().isEmpty())
        val invalid = fixture.store.pendingJob(pending.job.id)
        assertEquals(PendingJobStatusValue.NEEDS_RECONCILIATION, invalid?.job?.status)
        assertEquals("FILE_LIST_MISSING", invalid?.job?.sanitizedErrorCode)
        assertEquals(10L, fixture.store.checkpointForPlanning(PROFILE_ID, VOLUME)?.successfulGeneration)
    }

    @Test
    fun mappingsCannotChangeWhilePendingWorkExists() = withFixture { fixture ->
        fixture.createPendingJob()
        var rejected = false
        try {
            fixture.configuration.replaceMappings(PROFILE_ID, fixture.mappingInputs())
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertFalse(fixture.database.dao().activeJobs().isEmpty())
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            fixture.initialize()
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        private val context: Context,
    ) {
        private val databaseName = "phase4-${System.nanoTime()}.db"
        val work = File(context.cacheDir, "phase4-${System.nanoTime()}").apply { mkdirs() }
        private val volume = File(work, "shared").apply { mkdirs() }
        private val lists = File(work, "lists").apply { mkdirs() }
        private val clock = IncrementingClock()
        lateinit var database: PiffBackupDatabase
        lateinit var configuration: DurableConfigurationStore
        lateinit var store: DurableBackupStore

        suspend fun initialize() {
            File(work, "unrelated.keep").writeText("keep")
            openDatabase()
            configuration.saveProfile(
                StorageBoxProfileInput(
                    id = PROFILE_ID,
                    username = "u123456",
                    hostname = "u123456.your-storagebox.de",
                    remoteBasePath = "Matteo",
                    setupCompleted = true,
                ),
            )
            configuration.replaceMappings(PROFILE_ID, mappingInputs())
            store.establishCheckpoint(PROFILE_ID, MediaStoreCheckpoint(VOLUME, "v1", 10L))
        }

        fun mappingInputs(): List<FolderMappingInput> = listOf(
            mappingInput(MAPPING_CAMERA, "DCIM/Camera", "Matteo/Camera"),
            mappingInput(MAPPING_MOVIES, "Movies", "Matteo/Videos"),
        )

        suspend fun createPendingJob(): com.d35p4c1t0.piffbackup.data.DurablePendingJob {
            val cameraList = File(lists, "camera.from0").apply { writeBytes(byteArrayOf('a'.code.toByte(), 0)) }
            val moviesList = File(lists, "movies.from0").apply { writeBytes(byteArrayOf('b'.code.toByte(), 0)) }
            val plan = MediaPlanningResult.Incremental(
                snapshot = MediaStoreSnapshot(VOLUME, "v1", 20L),
                window = MediaGenerationWindow(10L, 20L),
                transfers = listOf(
                    PlannedMediaTransfer(
                        mapping = mediaMapping("DCIM/Camera", "Matteo/Camera"),
                        fileList = cameraList,
                        itemCount = 2L,
                        totalBytes = 20L,
                    ),
                    PlannedMediaTransfer(
                        mapping = mediaMapping("Movies", "Matteo/Videos"),
                        fileList = moviesList,
                        itemCount = 1L,
                        totalBytes = 30L,
                    ),
                ),
            )
            return store.persistIncrementalPlan(JOB_ID, PROFILE_ID, plan)
        }

        fun reopenDatabase() {
            database.close()
            openDatabase()
        }

        fun close() {
            if (::database.isInitialized) database.close()
            context.deleteDatabase(databaseName)
            work.deleteRecursively()
        }

        private fun openDatabase() {
            database = PiffBackupDatabase.open(context, databaseName)
            configuration = DurableConfigurationStore(database, volume, clock)
            store = DurableBackupStore(database, lists, clock)
        }

        private fun mappingInput(id: String, local: String, remote: String): FolderMappingInput {
            val localDirectory = File(volume, local).apply { mkdirs() }
            return FolderMappingInput(
                id = id,
                displayName = id,
                treeUri = "content://com.android.externalstorage.documents/tree/primary%3A${local.replace('/', '%')}",
                canonicalLocalPath = localDirectory.path,
                relativeMediaStorePrefix = "$local/",
                relativeRemotePath = remote,
                mode = MappingModeValue.MEDIA_FAST,
            )
        }

        private fun mediaMapping(local: String, remote: String): MediaStoreMapping =
            MediaStoreMapping.create(
                mapping = BackupMapping(
                    localRoot = CanonicalLocalRoot.create(File(volume, local).path, volume),
                    remoteRoot = RemoteRelativePath.create(remote),
                ),
                volumeRoot = volume,
            )
    }

    private class IncrementingClock : EpochMillisClock {
        private var value = 1_000L
        override fun now(): Long = value++
    }

    private companion object {
        const val PROFILE_ID = "profile"
        const val MAPPING_CAMERA = "camera"
        const val MAPPING_MOVIES = "movies"
        const val JOB_ID = "job"
        const val VOLUME = "external_primary"
    }
}
