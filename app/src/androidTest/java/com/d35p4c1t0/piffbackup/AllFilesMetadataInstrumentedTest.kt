package com.d35p4c1t0.piffbackup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d35p4c1t0.piffbackup.allfiles.AllFilesMetadataSnapshotStore
import com.d35p4c1t0.piffbackup.allfiles.LocalMetadataRecord
import com.d35p4c1t0.piffbackup.data.DurableBackupStore
import com.d35p4c1t0.piffbackup.data.DurableConfigurationStore
import com.d35p4c1t0.piffbackup.data.FolderMappingInput
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.data.PendingBackupJobDraft
import com.d35p4c1t0.piffbackup.data.PendingJobStatusValue
import com.d35p4c1t0.piffbackup.data.PendingRootDraft
import com.d35p4c1t0.piffbackup.data.PiffBackupDatabase
import com.d35p4c1t0.piffbackup.data.RootExecutionOutcome
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileInput
import com.d35p4c1t0.piffbackup.media.MediaStoreCheckpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AllFilesMetadataInstrumentedTest {
    @Test
    fun metadataBaselineAdvancesAtomicallyOnlyAfterRootSuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "phase9-all-files-${System.nanoTime()}.db"
        val work = File(context.cacheDir, "phase9-all-files-${System.nanoTime()}").apply { mkdirs() }
        val volume = File(work, "shared").apply { mkdirs() }
        val localRoot = File(volume, "Documents").apply { mkdirs() }
        val lists = File(work, "lists").apply { mkdirs() }
        val database = PiffBackupDatabase.open(context, databaseName)
        try {
            val configuration = DurableConfigurationStore(database, volume)
            val store = DurableBackupStore(database, lists)
            configuration.saveProfile(
                StorageBoxProfileInput(
                    id = "profile",
                    username = "test-user",
                    hostname = "example.invalid",
                    remoteBasePath = "Test",
                    setupCompleted = true,
                ),
            )
            configuration.replaceMappings(
                "profile",
                listOf(
                    FolderMappingInput(
                        id = "documents",
                        displayName = "Documents",
                        treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                        canonicalLocalPath = localRoot.path,
                        relativeMediaStorePrefix = "Documents/",
                        relativeRemotePath = "Test/Documents",
                        mode = MappingModeValue.ALL_FILES,
                    ),
                ),
            )
            store.establishCheckpoint(
                "profile",
                MediaStoreCheckpoint("external_primary", "v1", 10L),
            )
            val fileList = File(lists, "piffbackup-test.from0").apply {
                writeBytes(byteArrayOf('a'.code.toByte(), 0))
            }
            val snapshots = AllFilesMetadataSnapshotStore(lists)
            snapshots.openWriter(fileList.path).use { writer ->
                writer.append(LocalMetadataRecord("a.txt", 12L, 34L))
                writer.append(LocalMetadataRecord("folder/b.txt", 56L, 78L))
            }
            val pending = store.persistPendingJob(
                PendingBackupJobDraft(
                    id = "job",
                    profileId = "profile",
                    volumeName = "external_primary",
                    mediaStoreVersion = "v1",
                    configurationRevision = requireNotNull(configuration.profile("profile")).configurationRevision,
                    previousGeneration = 10L,
                    targetGeneration = 10L,
                    roots = listOf(PendingRootDraft("documents", fileList.path, 1L, 12L)),
                ),
            )

            assertTrue(store.localMetadata("documents", listOf("a.txt", "folder/b.txt")).isEmpty())
            store.markRootRunning(pending.job.id, "documents")
            val completed = store.recordRootOutcome(
                pending.job.id,
                "documents",
                RootExecutionOutcome.SUCCESS,
                completedFiles = 1L,
                completedBytes = 12L,
                rsyncExitCode = 0,
            )

            assertEquals(PendingJobStatusValue.SUCCEEDED, completed.job.status)
            val metadata = store.localMetadata("documents", listOf("a.txt", "folder/b.txt"))
            assertEquals(listOf("a.txt", "folder/b.txt"), metadata.map { it.relativePath }.sorted())
            assertEquals(listOf(12L, 56L), metadata.sortedBy { it.relativePath }.map { it.sizeBytes })
            assertTrue(File(snapshots.path(fileList.path)).isFile)
            assertEquals(1, store.cleanupSucceededJobs())
            assertFalse(fileList.exists())
            assertFalse(File(snapshots.path(fileList.path)).exists())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            work.deleteRecursively()
        }
    }
}
