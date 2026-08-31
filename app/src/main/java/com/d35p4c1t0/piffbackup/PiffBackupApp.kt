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

class PiffBackupApp : Application(), Configuration.Provider {
    private val incrementalFileListRoot: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(noBackupFilesDir, "incremental-file-lists")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setJobSchedulerJobIdRange(WORK_MANAGER_JOB_ID_MIN, WORK_MANAGER_JOB_ID_MAX)
            .build()

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
        PrimaryTreeSelectionResolver(Environment.getExternalStorageDirectory())
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
        )
    }

    val incrementalBackupCoordinator: IncrementalBackupCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        IncrementalBackupCoordinator(
            configuration = configurationStore,
            durableBackup = durableBackupStore,
            mediaSource = AndroidMediaStoreSource(applicationContext),
            fileLists = adoptionFileLists,
            allFiles = allFilesMetadataPlanner,
        )
    }

    val backupExecutor: BackupExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BackupExecutor(
            context = applicationContext,
            configuration = configurationStore,
            durableBackup = durableBackupStore,
            credentials = onboardingCredentials,
            knownHosts = knownHostStore,
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
            ),
            allFiles = allFilesMetadataPlanner,
            credentials = onboardingCredentials,
            knownHosts = knownHostStore,
            rsync = NativeAdoptionRsyncExecutor(applicationContext),
        )
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { credentialVault.cleanupAbandonedTemporaryKeys() }
        runCatching { onboardingCredentials.cleanupAbandonedGeneratedKeys() }
        runCatching { kotlinx.coroutines.runBlocking { durableBackupStore.recoverOnLaunch() } }
        runCatching { kotlinx.coroutines.runBlocking { durableBackupStore.cleanupOrphanedFileLists() } }
        BackupNotifications.createChannel(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    private companion object {
        const val WORK_MANAGER_JOB_ID_MIN = 42_000
        const val WORK_MANAGER_JOB_ID_MAX = 42_999
    }
}
