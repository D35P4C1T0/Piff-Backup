package com.d35p4c1t0.piffbackup

import android.app.Application
import android.os.Environment
import androidx.work.Configuration
import com.d35p4c1t0.piffbackup.allfiles.AllFilesMetadataPlanner
import com.d35p4c1t0.piffbackup.allfiles.AllFilesMetadataSnapshotStore
import com.d35p4c1t0.piffbackup.allfiles.LocalMetadataLookup
import com.d35p4c1t0.piffbackup.adoption.InitialAdoptionCoordinator
import com.d35p4c1t0.piffbackup.adoption.InitialFileListPlanner
import com.d35p4c1t0.piffbackup.adoption.NativeAdoptionRsyncExecutor
import com.d35p4c1t0.piffbackup.adoption.NativeRemoteDirectoryBrowser
import com.d35p4c1t0.piffbackup.adoption.PrimaryTreeSelectionResolver
import com.d35p4c1t0.piffbackup.data.DurableBackupStore
import com.d35p4c1t0.piffbackup.data.DurableConfigurationStore
import com.d35p4c1t0.piffbackup.data.PiffBackupDatabase
import com.d35p4c1t0.piffbackup.onboarding.HetznerOnboardingCoordinator
import com.d35p4c1t0.piffbackup.onboarding.KnownHostStore
import com.d35p4c1t0.piffbackup.onboarding.NativeOnboardingCredentialManager
import com.d35p4c1t0.piffbackup.onboarding.NativeStorageBoxDestinationVerifier
import com.d35p4c1t0.piffbackup.onboarding.RoomOnboardingProfileStore
import com.d35p4c1t0.piffbackup.onboarding.SshjPasswordKeyInstaller
import com.d35p4c1t0.piffbackup.media.AndroidMediaStoreSource
import com.d35p4c1t0.piffbackup.media.IncrementalFileListStore
import com.d35p4c1t0.piffbackup.security.EncryptedCredentialVault
import com.d35p4c1t0.piffbackup.scheduling.BackupExecutor
import com.d35p4c1t0.piffbackup.scheduling.BackupNotifications
import com.d35p4c1t0.piffbackup.scheduling.BackupScheduler
import com.d35p4c1t0.piffbackup.scheduling.IncrementalBackupCoordinator
import com.google.android.material.color.DynamicColors
import java.io.File
import kotlinx.coroutines.async

class PiffBackupApp : Application(), Configuration.Provider {
    private val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private lateinit var initialization: kotlinx.coroutines.Deferred<Unit>

    suspend fun awaitReady() = initialization.await()
    val storageVolumes: Map<String, File> get() {
        val manager = getSystemService(android.os.storage.StorageManager::class.java)
        return manager.storageVolumes.mapNotNull { volume -> volume.directory?.let { directory ->
            (if (volume.isPrimary) "primary" else volume.uuid ?: return@mapNotNull null) to directory
        } }.toMap()
    }
    val allowedStorageRoots: List<File> get() = storageVolumes.values.toList()

    // Resolve mounted volumes at use time, including cards inserted after app startup.
    private val liveStorageRoots = object : AbstractList<File>() {
        override val size get() = allowedStorageRoots.size
        override fun get(index: Int) = allowedStorageRoots[index]
    }

    private val incrementalFileListRoot: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(noBackupFilesDir, "incremental-file-lists")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setJobSchedulerJobIdRange(WORK_MANAGER_JOB_ID_MIN, WORK_MANAGER_JOB_ID_MAX)
            .build()

    val encryptedArchives by lazy { com.d35p4c1t0.piffbackup.operations.EncryptedArchives(this) }

    val remoteOperations by lazy { com.d35p4c1t0.piffbackup.operations.RemoteOperationExecutor(this) }

    suspend fun executeJob(id: String, reporter: com.d35p4c1t0.piffbackup.scheduling.BackupExecutionReporter):
        com.d35p4c1t0.piffbackup.scheduling.BackupExecutionResult {
        awaitReady()
        return if (id.startsWith("operation-")) remoteOperations.execute(id, reporter)
            else backupExecutor.execute(id, reporter)
    }

    val backgroundMessages by lazy { com.d35p4c1t0.piffbackup.scheduling.BackgroundMessages(this) }

    val selectionMutex = kotlinx.coroutines.sync.Mutex()

    val backupPreferences by lazy { com.d35p4c1t0.piffbackup.settings.BackupPreferences(this) }

    val database: PiffBackupDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PiffBackupDatabase.open(applicationContext)
    }

    val durableBackupStore: DurableBackupStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DurableBackupStore(
            database = database,
            fileListRoot = incrementalFileListRoot,
        )
    }

    val configurationStore: DurableConfigurationStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DurableConfigurationStore(
            database = database,
            allowedSharedStorageRoot = Environment.getExternalStorageDirectory(),
            allowedRoots = liveStorageRoots,
            selectionMutex = selectionMutex,
            activateSelection = backupPreferences::activateSelection,
        )
    }

    val credentialVault: EncryptedCredentialVault by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EncryptedCredentialVault(applicationContext)
    }

    val onboardingCredentials: NativeOnboardingCredentialManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeOnboardingCredentialManager(applicationContext, credentialVault)
    }

    val knownHostStore: KnownHostStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KnownHostStore(applicationContext)
    }

    val onboardingCoordinator: HetznerOnboardingCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HetznerOnboardingCoordinator(
            profiles = RoomOnboardingProfileStore(configurationStore),
            credentials = onboardingCredentials,
            passwordInstaller = SshjPasswordKeyInstaller(),
            knownHosts = knownHostStore,
            destinationVerifier = NativeStorageBoxDestinationVerifier(applicationContext),
        )
    }

    val treeSelectionResolver: PrimaryTreeSelectionResolver by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PrimaryTreeSelectionResolver(Environment.getExternalStorageDirectory(), volumesProvider = { storageVolumes })
    }

    val remoteDirectoryBrowser: NativeRemoteDirectoryBrowser by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeRemoteDirectoryBrowser(applicationContext, onboardingCredentials, knownHostStore)
    }

    val adoptionFileLists: IncrementalFileListStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        IncrementalFileListStore(incrementalFileListRoot)
    }

    val allFilesMetadataPlanner: AllFilesMetadataPlanner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AllFilesMetadataPlanner(
            fileLists = adoptionFileLists,
            snapshots = AllFilesMetadataSnapshotStore(incrementalFileListRoot),
            metadata = LocalMetadataLookup(durableBackupStore::localMetadata),
            volumeRoot = Environment.getExternalStorageDirectory(),
            selection = backupPreferences::selection,
            allowedRoots = liveStorageRoots,
        )
    }

    val incrementalBackupCoordinator: IncrementalBackupCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        IncrementalBackupCoordinator(
            configuration = configurationStore,
            durableBackup = durableBackupStore,
            mediaSource = AndroidMediaStoreSource(applicationContext),
            fileLists = adoptionFileLists,
            allFiles = allFilesMetadataPlanner,
            selection = backupPreferences::selection,
            selectionMutex = selectionMutex,
        )
    }

    val backupExecutor: BackupExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BackupExecutor(
            context = applicationContext,
            configuration = configurationStore,
            durableBackup = durableBackupStore,
            credentials = onboardingCredentials,
            knownHosts = knownHostStore,
            allowedRoots = liveStorageRoots,
        )
    }

    val backupScheduler: BackupScheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BackupScheduler(applicationContext, durableBackupStore, backupExecutor)
    }

    val initialAdoptionCoordinator: InitialAdoptionCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val mediaSource = AndroidMediaStoreSource(applicationContext)
        InitialAdoptionCoordinator(
            configuration = configurationStore,
            durableBackup = durableBackupStore,
            mediaSource = mediaSource,
            fileLists = InitialFileListPlanner(
                source = mediaSource,
                store = adoptionFileLists,
                volumeRoot = Environment.getExternalStorageDirectory(),
            selection = backupPreferences::selection,
            allowedRoots = liveStorageRoots,
            ),
            allFiles = allFilesMetadataPlanner,
            credentials = onboardingCredentials,
            knownHosts = knownHostStore,
            rsync = NativeAdoptionRsyncExecutor(applicationContext),
            selectionMutex = selectionMutex,
        )
    }

    override fun onCreate() {
        super.onCreate()
        initialization = applicationScope.async {
            credentialVault.cleanupAbandonedTemporaryKeys()
            onboardingCredentials.cleanupAbandonedGeneratedKeys()
            database.dao().selectionPolicy("primary")?.let(backupPreferences::activateSelection)
            database.dao().recoverOperations()
            encryptedArchives.cleanupAbandoned()
            durableBackupStore.recoverOnLaunch()
            durableBackupStore.cleanupSucceededJobs()
            durableBackupStore.cleanupOrphanedFileLists()
        }
        BackupNotifications.createChannel(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    private companion object {
        const val WORK_MANAGER_JOB_ID_MIN = 42_000
        const val WORK_MANAGER_JOB_ID_MAX = 42_999
    }
}
