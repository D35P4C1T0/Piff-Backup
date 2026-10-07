package com.d35p4c1t0.piffbackup.data
import androidx.room3.*
@Entity(tableName = "backup_selection", foreignKeys = [ForeignKey(
    entity = StorageBoxProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)])
data class BackupSelectionEntity(@PrimaryKey val profileId: String, val exclusions: String, val includeHidden: Boolean) {
    val patterns get() = exclusions.lines().filter { it.isNotBlank() }
    fun validate() { require(patterns.size <= 100); com.d35p4c1t0.piffbackup.backup.FileSelectionPolicy(patterns,includeHidden) }
}
