package com.d35p4c1t0.piffbackup.settings

import android.content.Context
import com.d35p4c1t0.piffbackup.backup.FileSelectionPolicy

class BackupPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("backup-policy", Context.MODE_PRIVATE)
    var unmetered: Boolean
        get() = preferences.getBoolean("unmetered", true)
        set(value) { preferences.edit().putBoolean("unmetered", value).apply() }
    var charging: Boolean
        get() = preferences.getBoolean("charging", false)
        set(value) { preferences.edit().putBoolean("charging", value).apply() }
    var batteryNotLow: Boolean
        get() = preferences.getBoolean("battery-not-low", true)
        set(value) { preferences.edit().putBoolean("battery-not-low", value).apply() }
    @Volatile private var coverage: com.d35p4c1t0.piffbackup.data.BackupSelectionEntity? = null
    @Volatile private var compiled: FileSelectionPolicy? = null
    val includeHidden: Boolean get() = coverage?.includeHidden ?: preferences.getBoolean("include-hidden", true)
    var preserveVersions: Boolean
        get() = preferences.getBoolean("preserve-versions", false)
        set(value) { preferences.edit().putBoolean("preserve-versions", value).apply() }
    var scheduled: Boolean
        get() = preferences.getBoolean("scheduled", false)
        set(value) { preferences.edit().putBoolean("scheduled", value).apply() }
    val exclusions: List<String> get() = coverage?.patterns ?: preferences.getString("exclusions", "").orEmpty().lines().filter { it.isNotBlank() }
    fun activateSelection(entity: com.d35p4c1t0.piffbackup.data.BackupSelectionEntity) {
        entity.validate()
        compiled = FileSelectionPolicy(entity.patterns,entity.includeHidden)
        coverage = entity
    }
    private val initialSelection by lazy { FileSelectionPolicy(exclusions, includeHidden) }
    fun selection() = compiled ?: initialSelection
}
