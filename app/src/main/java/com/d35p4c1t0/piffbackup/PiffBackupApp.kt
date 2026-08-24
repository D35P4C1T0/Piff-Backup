package com.d35p4c1t0.piffbackup

import android.app.Application
import com.google.android.material.color.DynamicColors

class PiffBackupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
