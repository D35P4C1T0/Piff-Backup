package com.d35p4c1t0.piffbackup

import android.app.Application
import com.d35p4c1t0.piffbackup.data.DurableBackupStore
import com.d35p4c1t0.piffbackup.data.PiffBackupDatabase
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

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
