package com.d35p4c1t0.piffbackup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d35p4c1t0.piffbackup.allfiles.AllFilesMetadataPlanner
import com.d35p4c1t0.piffbackup.allfiles.AllFilesMetadataSnapshotStore
import com.d35p4c1t0.piffbackup.allfiles.LocalMetadataLookup
import com.d35p4c1t0.piffbackup.data.DurableBackupStore
import com.d35p4c1t0.piffbackup.data.DurableConfigurationStore
import com.d35p4c1t0.piffbackup.data.FolderMappingInput
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.data.PiffBackupDatabase
import com.d35p4c1t0.piffbackup.data.RootExecutionOutcome
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileInput
import com.d35p4c1t0.piffbackup.media.IncrementalFileListStore
import com.d35p4c1t0.piffbackup.media.MediaGenerationWindow
import com.d35p4c1t0.piffbackup.media.MediaStoreCheckpoint
import com.d35p4c1t0.piffbackup.media.MediaStoreRow
import com.d35p4c1t0.piffbackup.media.MediaStoreSnapshot
import com.d35p4c1t0.piffbackup.media.MediaStoreSource
import com.d35p4c1t0.piffbackup.scheduling.BackupDiscoveryResult
import com.d35p4c1t0.piffbackup.scheduling.IncrementalBackupCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IncrementalBackupCoordinatorInstrumentedTest {
    @Test
    fun allFilesMappingUsesMetadataPlanThenBecomesUpToDate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = Fixture(context)
        try {
            fixture.initialize()

            val first = fixture.coordinator.discover(PROFILE_ID)

            assertTrue(first is BackupDiscoveryResult.Ready)
            val pending = (first as BackupDiscoveryResult.Ready).pending
            assertEquals(listOf(ALL_FILES_MAPPING), pending.roots.map { it.folderMappingId })
            fixture.store.markRootRunning(pending.job.id, ALL_FILES_MAPPING)
            fixture.store.recordRootOutcome(
                pending.job.id,
                ALL_FILES_MAPPING,
                RootExecutionOutcome.SUCCESS,
                completedFiles = 1L,
                completedBytes = fixture.allFilesItem.length(),
                rsyncExitCode = 0,
            )

            assertEquals(BackupDiscoveryResult.UpToDate, fixture.coordinator.discover(PROFILE_ID))
            assertEquals(
                listOf("item.txt"),
                fixture.store.localMetadata(ALL_FILES_MAPPING, listOf("item.txt")).map { it.relativePath },
            )
        } finally {
            fixture.close()
        }
    }

    private class Fixture(private val context: Context) {
        private val databaseName = "phase9-coordinator-${System.nanoTime()}.db"
        private val work = File(context.cacheDir, "phase9-coordinator-${System.nanoTime()}").apply { mkdirs() }
        private val volume = File(work, "shared").apply { mkdirs() }
        private val mediaRoot = File(volume, "DCIM/Camera").apply { mkdirs() }
        private val allFilesRoot = File(volume, "Documents").apply { mkdirs() }
        private val lists = File(work, "lists").apply { mkdirs() }
        private val database = PiffBackupDatabase.open(context, databaseName)
        private val configuration = DurableConfigurationStore(database, volume)
        val store = DurableBackupStore(database, lists)
        val allFilesItem = File(allFilesRoot, "item.txt").apply { writeText("local only") }
        val coordinator = IncrementalBackupCoordinator(
            configuration = configuration,
            durableBackup = store,
            mediaSource = StableMediaSource(),
            fileLists = IncrementalFileListStore(lists),
            allFiles = AllFilesMetadataPlanner(
                fileLists = IncrementalFileListStore(lists),
                snapshots = AllFilesMetadataSnapshotStore(lists),
                metadata = LocalMetadataLookup(store::localMetadata),
                volumeRoot = volume,
            ),
            volumeRoot = volume,
        )

        suspend fun initialize() {
            configuration.saveProfile(
                StorageBoxProfileInput(
                    id = PROFILE_ID,
                    username = "test-user",
                    hostname = "example.invalid",
                    remoteBasePath = "Test",
                    setupCompleted = true,
                ),
            )
            configuration.replaceMappings(
                PROFILE_ID,
                listOf(
                    mapping(MEDIA_MAPPING, mediaRoot, "DCIM/Camera/", MappingModeValue.MEDIA_FAST),
                    mapping(ALL_FILES_MAPPING, allFilesRoot, "Documents/", MappingModeValue.ALL_FILES),
                ),
            )
            store.establishCheckpoint(PROFILE_ID, MediaStoreCheckpoint(VOLUME, "v1", 10L))
        }

        fun close() {
            database.close()
            context.deleteDatabase(databaseName)
            work.deleteRecursively()
        }

        private fun mapping(id: String, root: File, prefix: String, mode: String) = FolderMappingInput(
            id = id,
            displayName = id,
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3A${prefix.trimEnd('/').replace("/", "%2F")}",
            canonicalLocalPath = root.path,
            relativeMediaStorePrefix = prefix,
            relativeRemotePath = "Test/$id",
            mode = mode,
        )
    }

    private class StableMediaSource : MediaStoreSource {
        override fun snapshot(volumeName: String) = MediaStoreSnapshot(volumeName, "v1", 10L)

        override fun forEachChangedMedia(
            volumeName: String,
            window: MediaGenerationWindow,
            consumer: (MediaStoreRow) -> Unit,
        ) = Unit
    }

    private companion object {
        const val PROFILE_ID = "profile"
        const val MEDIA_MAPPING = "camera"
        const val ALL_FILES_MAPPING = "documents"
        const val VOLUME = "external_primary"
    }
}
