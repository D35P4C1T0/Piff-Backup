package com.d35p4c1t0.piffbackup

import android.app.Application
import android.os.Environment
import com.d35p4c1t0.piffbackup.data.DurableBackupStore
import com.d35p4c1t0.piffbackup.data.DurableConfigurationStore
import com.d35p4c1t0.piffbackup.data.PiffBackupDatabase
import com.d35p4c1t0.piffbackup.onboarding.HetznerOnboardingCoordinator
import com.d35p4c1t0.piffbackup.onboarding.KnownHostStore
import com.d35p4c1t0.piffbackup.onboarding.NativeOnboardingCredentialManager
import com.d35p4c1t0.piffbackup.onboarding.NativeStorageBoxDestinationVerifier
import com.d35p4c1t0.piffbackup.onboarding.RoomOnboardingProfileStore
import com.d35p4c1t0.piffbackup.onboarding.SshjPasswordKeyInstaller
import com.d35p4c1t0.piffbackup.security.EncryptedCredentialVault
import com.google.android.material.color.DynamicColors
import java.io.File

class PiffBackupApp : Application() {
    val database: PiffBackupDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PiffBackupDatabase.open(applicationContext)
    }

    val durableBackupStore: DurableBackupStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DurableBackupStore(
            database = database,
            fileListRoot = File(noBackupFilesDir, "incremental-file-lists"),
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

    val onboardingCoordinator: HetznerOnboardingCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HetznerOnboardingCoordinator(
            profiles = RoomOnboardingProfileStore(configurationStore),
            credentials = onboardingCredentials,
            passwordInstaller = SshjPasswordKeyInstaller(),
            knownHosts = KnownHostStore(applicationContext),
            destinationVerifier = NativeStorageBoxDestinationVerifier(applicationContext),
        )
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { credentialVault.cleanupAbandonedTemporaryKeys() }
        runCatching { onboardingCredentials.cleanupAbandonedGeneratedKeys() }
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
