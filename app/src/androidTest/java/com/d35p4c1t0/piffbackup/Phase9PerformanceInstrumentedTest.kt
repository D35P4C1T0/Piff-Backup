package com.d35p4c1t0.piffbackup

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d35p4c1t0.piffbackup.allfiles.AllFilesMetadataPlanner
import com.d35p4c1t0.piffbackup.allfiles.AllFilesMetadataSnapshotStore
import com.d35p4c1t0.piffbackup.allfiles.LocalMetadataLookup
import com.d35p4c1t0.piffbackup.allfiles.LocalMetadataRecord
import com.d35p4c1t0.piffbackup.data.FolderMappingEntity
import com.d35p4c1t0.piffbackup.data.LocalFileMetadataEntity
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.media.IncrementalFileListStore
import com.d35p4c1t0.piffbackup.rsync.NativeProcessRunner
import com.d35p4c1t0.piffbackup.rsync.NativeTool
import com.d35p4c1t0.piffbackup.rsync.NativeToolLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Phase9PerformanceInstrumentedTest {
    @Test
    fun recordLocalOnlyDiscoveryAndRsyncOverhead() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val externalFiles = requireNotNull(context.getExternalFilesDir(null))
            val root = File(externalFiles, "phase9-performance-${System.nanoTime()}").apply { mkdirs() }
            val lists = File(context.cacheDir, "phase9-performance-lists-${System.nanoTime()}").apply { mkdirs() }
            val rsyncWork = File(context.cacheDir, "phase9-performance-rsync-${System.nanoTime()}").apply { mkdirs() }
            try {
                repeat(DISCOVERY_FILE_COUNT) { index ->
                    val directory = File(root, "folder-${index / 25}").apply { mkdirs() }
                    File(directory, "item-$index.bin").writeBytes(byteArrayOf(index.toByte()))
                }
                val snapshots = AllFilesMetadataSnapshotStore(lists)
                val mapping = mapping(root)
                val coldPlanner = planner(lists, snapshots, externalFiles, emptyList())
                val coldStart = SystemClock.elapsedRealtimeNanos()
                val coldPlan = coldPlanner.plan(mapping)
                val coldMillis = elapsedMillis(coldStart)

                val snapshotRecords = mutableListOf<LocalMetadataRecord>()
                snapshots.forEachBatch(coldPlan.fileList.path) { snapshotRecords += it }
                val baseline = snapshotRecords.map { record ->
                    LocalFileMetadataEntity(
                        folderMappingId = mapping.id,
                        relativePath = record.relativePath,
                        sizeBytes = record.sizeBytes,
                        modifiedAtEpochMillis = record.modifiedAtEpochMillis,
                        observedAtEpochMillis = 1L,
                    )
                }
                snapshots.delete(coldPlan.fileList.path)
                coldPlan.fileList.delete()

                val unchangedPlanner = planner(lists, snapshots, externalFiles, baseline)
                val unchangedStart = SystemClock.elapsedRealtimeNanos()
                val unchangedPlan = unchangedPlanner.plan(mapping)
                val unchangedMillis = elapsedMillis(unchangedStart)
                assertEquals(0L, unchangedPlan.itemCount)

                val source = File(rsyncWork, "source").apply { mkdirs() }
                val destination = File(rsyncWork, "destination").apply { mkdirs() }
                repeat(RSYNC_FILE_COUNT) { index ->
                    File(source, "item-$index.bin").writeBytes(ByteArray(1024) { index.toByte() })
                }
                val rsync = NativeToolLocator(context).require(NativeTool.RSYNC)
                val command = listOf(
                    rsync.path,
                    "--archive",
                    "--",
                    source.path.trimEnd('/') + "/",
                    destination.path.trimEnd('/') + "/",
                )
                val copyStart = SystemClock.elapsedRealtimeNanos()
                val copyResult = NativeProcessRunner().start(command, rsyncWork).await()
                val copyMillis = elapsedMillis(copyStart)
                assertEquals(0, copyResult.exitCode)

                val noOpStart = SystemClock.elapsedRealtimeNanos()
                val noOpResult = NativeProcessRunner().start(command, rsyncWork).await()
                val noOpMillis = elapsedMillis(noOpStart)
                assertEquals(0, noOpResult.exitCode)

                val metrics = """
                    device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} API ${android.os.Build.VERSION.SDK_INT}
                    all_files_cold_count=$DISCOVERY_FILE_COUNT
                    all_files_cold_ms=$coldMillis
                    all_files_unchanged_count=$DISCOVERY_FILE_COUNT
                    all_files_unchanged_ms=$unchangedMillis
                    metadata_snapshot_bytes=${File(snapshots.path(unchangedPlan.fileList.path)).length()}
                    rsync_copy_files=$RSYNC_FILE_COUNT
                    rsync_copy_bytes=${RSYNC_FILE_COUNT * 1024}
                    rsync_copy_ms=$copyMillis
                    rsync_noop_ms=$noOpMillis
                    """.trimIndent()
                File(context.filesDir, OUTPUT_FILE).writeText(metrics + "\n")
                Log.i(LOG_TAG, metrics.replace('\n', ' '))
                snapshots.delete(unchangedPlan.fileList.path)
                unchangedPlan.fileList.delete()
            } finally {
                root.deleteRecursively()
                lists.deleteRecursively()
                rsyncWork.deleteRecursively()
            }
        }
    }

    private fun planner(
        lists: File,
        snapshots: AllFilesMetadataSnapshotStore,
        volumeRoot: File,
        baseline: List<LocalFileMetadataEntity>,
    ) = AllFilesMetadataPlanner(
        fileLists = IncrementalFileListStore(lists),
        snapshots = snapshots,
        metadata = LocalMetadataLookup { mappingId, paths ->
            baseline.filter { it.folderMappingId == mappingId && it.relativePath in paths }
        },
        volumeRoot = volumeRoot,
    )

    private fun mapping(root: File) = FolderMappingEntity(
        id = "performance",
        profileId = "profile",
        displayName = "Performance",
        treeUri = "content://performance",
        canonicalLocalPath = root.canonicalPath,
        relativeMediaStorePrefix = "",
        relativeRemotePath = "Test/Performance",
        mode = MappingModeValue.ALL_FILES,
        enabled = true,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun elapsedMillis(startNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L

    private companion object {
        const val DISCOVERY_FILE_COUNT = 1_000
        const val RSYNC_FILE_COUNT = 100
        const val OUTPUT_FILE = "phase9-performance.txt"
        const val LOG_TAG = "PiffBackupPerf"
    }
}
