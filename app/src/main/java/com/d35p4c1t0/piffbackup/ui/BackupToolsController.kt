package com.d35p4c1t0.piffbackup.ui

import android.net.Uri
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.d35p4c1t0.piffbackup.PiffBackupApp
import com.d35p4c1t0.piffbackup.R
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.data.*
import com.d35p4c1t0.piffbackup.settings.ConfigurationTransfer
import com.d35p4c1t0.piffbackup.scheduling.ScheduledBackupWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import java.io.File

/** UI for optional tools; transfer ownership always moves to durable execution before upload/download. */
class BackupToolsController(private val activity: AppCompatActivity, private val app: PiffBackupApp,
    private val schedule: (String) -> Unit, private val reload: () -> Unit) {
    private var restorePath: String? = null
    private var restoreFile = false
    private var restoreArchive = false
    private val restorePicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) background {
            val selection = app.treeSelectionResolver.resolve(uri)
            val operation = app.remoteOperations.prepareRestore(requireNotNull(restorePath), File(selection.canonicalPath), restoreFile, restoreArchive)
            onMain { schedule(operation.id) }
        }
    }
    private val exportPicker = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) background {
            val profile = requireNotNull(app.configurationStore.profile("primary"))
            val json = ConfigurationTransfer.encode(profile, app.configurationStore.mappings(profile.id),
                app.database.dao().selectionPolicy(profile.id) ?: BackupSelectionEntity(profile.id,app.backupPreferences.exclusions.joinToString("\n"),app.backupPreferences.includeHidden))
            requireNotNull(activity.contentResolver.openOutputStream(uri)).use { it.write(json.toByteArray()) }
            onMain { message(R.string.configuration_exported, R.string.configuration_export_help) }
        }
    }
    private val importPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background {
            val bytes = requireNotNull(activity.contentResolver.openInputStream(uri)).use { it.readNBytes(1024 * 1024 + 1) }
            require(bytes.size <= 1024 * 1024)
            val json = bytes.toString(Charsets.UTF_8)
            val (profile, mappings) = ConfigurationTransfer.decode(json)
            val policy = ConfigurationTransfer.decodeSelection(json)
            onMain {
                MaterialAlertDialogBuilder(activity).setTitle(R.string.import_configuration)
                    .setMessage(activity.getString(R.string.configuration_import_review, profile.hostname, mappings.size))
                    .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.import_configuration) { _, _ ->
                        background {
                            app.configurationStore.importConfiguration(profile, mappings, policy)
                            onMain { reload() }
                        }
                    }.show()
            }
        }
    }

    private val safPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) background {
            activity.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val op = app.remoteOperations.prepareSafArchive(uri.toString())
            onMain { schedule(op.id) }
        }
    }
    private val notificationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val recoveryExport = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) background {
            val key = app.encryptedArchives.keyForExport()
            try {
                requireNotNull(activity.contentResolver.openOutputStream(uri)).use { output ->
                    output.write(("PiffBackup archive recovery key v1\n" + android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP) + "\n").toByteArray())
                }
                app.encryptedArchives.installKey(key)
                onMain { message(R.string.archive_key_export, R.string.archive_key_help) }
            } finally { key.fill(0) }
        }
    }
    private val recoveryImport = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background {
            val text = requireNotNull(activity.contentResolver.openInputStream(uri)).use { it.readNBytes(4097) }
            require(text.size <= 4096)
            val lines = text.toString(Charsets.UTF_8).trim().lines()
            require(lines.size == 2 && lines[0] == "PiffBackup archive recovery key v1")
            val key = android.util.Base64.decode(lines[1], android.util.Base64.NO_WRAP)
            try {
                require(!app.encryptedArchives.hasKey()) { "An archive key is already installed" }
                app.encryptedArchives.installKey(key)
                onMain { message(R.string.archive_key_import, R.string.archive_key_help) }
            } finally { key.fill(0) }
        }
    }

    private fun archives() {
        val choices = intArrayOf(R.string.archive_create, R.string.archive_key_export, R.string.archive_key_import, R.string.archive_saf)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.encrypted_archives).setMessage(R.string.archive_help)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                MaterialAlertDialogBuilder(activity).setTitle(R.string.encrypted_archives)
                    .setItems(choices.map(activity::getString).toTypedArray()) { _, index -> when (index) {
                        0 -> background { val op = app.remoteOperations.prepareArchive(); onMain { schedule(op.id) } }
                        1 -> recoveryExport.launch("PiffBackup-archive-recovery-key.txt")
                        2 -> recoveryImport.launch(arrayOf("text/plain"))
                        3 -> safPicker.launch(null)
                    } }.setNegativeButton(R.string.cancel, null).show()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    fun show() {
        val choices = intArrayOf(R.string.restore_backup, R.string.verify_backup, R.string.backup_history,
            R.string.folder_status, R.string.export_configuration, R.string.import_configuration, R.string.local_storage_usage, R.string.encrypted_archives, R.string.manage_ssh_key)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.backup_tools)
            .setItems(choices.map(activity::getString).toTypedArray()) { _, which ->
                when (which) {
                    0 -> background { val profile = requireNotNull(app.configurationStore.profile("primary"))
                        onMain { browseRestore(RemoteRelativePath.create(profile.remoteBasePath)) } }
                    1 -> chooseVerification()
                    2 -> history()
                    3 -> folderStatus()
                    4 -> exportPicker.launch("PiffBackup-configuration.json")
                    5 -> importPicker.launch(arrayOf("application/json", "text/plain"))
                    6 -> storageUsage()
                    7 -> archives()
                    8 -> keyManagement()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    fun editPolicy() {
        val p = app.backupPreferences
        val labels = intArrayOf(R.string.unmetered_only, R.string.only_charging, R.string.battery_not_low,
            R.string.preserve_versions, R.string.daily_backup)
        val values = booleanArrayOf(p.unmetered, p.charging, p.batteryNotLow, p.preserveVersions, p.scheduled)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.backup_constraints)
            .setMultiChoiceItems(labels.map(activity::getString).toTypedArray(), values) { _, index, selected -> values[index] = selected }
            .setPositiveButton(R.string.save_preferences) { _, _ ->
                p.unmetered = values[0]; p.charging = values[1]; p.batteryNotLow = values[2]
                p.preserveVersions = values[3]; p.scheduled = values[4]
                ScheduledBackupWorker.update(app)
                if (p.scheduled && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }.setNegativeButton(R.string.cancel, null).show()
    }

    fun editExclusions() {
        val p = app.backupPreferences
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.exclusions_hint); setText(p.exclusions.joinToString("\n"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val hidden = CheckBox(activity).apply { text = activity.getString(R.string.include_hidden); isChecked = p.includeHidden }
        container.addView(input); container.addView(hidden)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.exclusions).setMessage(R.string.exclusions_help)
            .setView(container).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_preferences) { _, _ ->
                val rules = input.text.toString().lines().map(String::trim).filter(String::isNotEmpty)
                val includeHidden = hidden.isChecked
                background {
                    app.configurationStore.updateSelectionPolicy(BackupSelectionEntity("primary",rules.joinToString("\n"),includeHidden))
                    onMain { reload() }
                }
            }.show()
    }

    private fun browseRestore(parent: RemoteRelativePath): Unit = background {
        val profile = requireNotNull(app.configurationStore.profile("primary"))
        val directories = app.remoteDirectoryBrowser.listEntries(profile, parent)
        onMain {
            MaterialAlertDialogBuilder(activity).setTitle(parent.value)
                .setItems((listOf(activity.getString(R.string.restore_this_folder)) + directories.map { (if (it.isDirectory) "▸ " else "") + it.name }).toTypedArray()) { _, which ->
                    if (which == 0) {
                        restoreFile = false; restoreArchive = false
                        restorePath = parent.value
                        MaterialAlertDialogBuilder(activity).setTitle(R.string.restore_backup).setMessage(R.string.restore_destination_help)
                            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.choose_destination) { _, _ -> restorePicker.launch(null) }.show()
                    } else {
                        val entry = directories[which - 1]
                        if (entry.isDirectory) browseRestore(RemoteRelativePath.create(entry.relativePath)) else {
                            restorePath = entry.relativePath; restoreFile = true
                            restoreArchive = entry.name.endsWith(".pba") && entry.relativePath.startsWith("${profile.remoteBasePath}/Encrypted/")
                            MaterialAlertDialogBuilder(activity).setTitle(entry.name).setMessage(R.string.restore_destination_help)
                                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.choose_destination) { _, _ -> restorePicker.launch(null) }.show()
                        }
                    }
                }.setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.remote_folder_up) { _, _ ->
                    val base = RemoteRelativePath.create(profile.remoteBasePath)
                    if (parent.value != base.value) browseRestore(RemoteRelativePath.create(parent.value.substringBeforeLast('/')))
                }.show()
        }
    }

    private fun chooseVerification() = background {
        val mappings = app.configurationStore.mappings("primary").filter { it.enabled }
        onMain {
            MaterialAlertDialogBuilder(activity).setTitle(R.string.verify_backup)
                .setItems(mappings.map { it.displayName }.toTypedArray()) { _, index ->
                    MaterialAlertDialogBuilder(activity).setTitle(R.string.verify_backup).setMessage(R.string.verify_help)
                        .setNegativeButton(R.string.cancel, null)
                        .setNeutralButton(R.string.verify_sample) { _, _ -> verification(mappings[index], true) }
                        .setPositiveButton(R.string.verify_full) { _, _ -> verification(mappings[index], false) }.show()
                }.setNegativeButton(R.string.cancel, null).show()
        }
    }
    private fun verification(mapping: FolderMappingEntity, sampled: Boolean) = background {
        val operation = app.remoteOperations.prepareVerification(mapping, sampled)
        onMain { schedule(operation.id) }
    }

    private fun keyManagement() = background {
        val profile = requireNotNull(app.configurationStore.profile("primary"))
        val publicKey = app.onboardingCredentials.withPrivateKey(requireNotNull(profile.encryptedCredentialRef)) { key ->
            // The public key is derived by the bundled SSH tool; the private key stays in the vault.
            val result = com.d35p4c1t0.piffbackup.rsync.NativeProcessRunner().start(listOf(
                com.d35p4c1t0.piffbackup.rsync.NativeToolLocator(app).require(com.d35p4c1t0.piffbackup.rsync.NativeTool.SSH_KEYGEN).path,
                "-y", "-f", key.path), key.parentFile!!).await(10000)
            require(result.exitCode == 0); result.stdout.lineSequence().first { it.startsWith("ssh-ed25519 ") }.trim()
        }
        onMain { textMessage(R.string.manage_ssh_key, activity.getString(R.string.ssh_key_management_help) + "\n\n" + publicKey) }
    }

    private fun history() = background {
        val backups = app.durableBackupStore.history("primary")
        val operations = app.database.dao().operations("primary")
        val entries = backups.map { run -> run.finishedAtEpochMillis to
            "${date(run.finishedAtEpochMillis)} · ${if (run.result == "SUCCEEDED") activity.getString(R.string.backup_completed) else activity.getString(R.string.needs_attention)}\n" +
                activity.getString(R.string.history_upload_summary,run.discoveredFiles.toString(),run.uploadedFiles.toString(),com.d35p4c1t0.piffbackup.backup.UserFacingFormat.bytes(run.uploadedBytes)) +
                run.sanitizedErrorCode?.let { "\n${errorHelp(it)} ($it)" }.orEmpty() } +
            operations.map { op -> (op.finishedAtEpochMillis ?: op.startedAtEpochMillis) to
                "${date(op.finishedAtEpochMillis ?: op.startedAtEpochMillis)} · ${operationName(op.kind)}\n${operationStatus(op.status)} · ${activity.getString(R.string.history_comparison_summary,op.comparedFiles.toString(),op.changedFiles.toString())}${op.errorCode?.let { "\n${errorHelp(it)} ($it)" }.orEmpty()}${op.localDestination?.let { "\n$it" }.orEmpty()}" }
        val text = entries.sortedByDescending { it.first }.joinToString("\n\n") { it.second }
        onMain { textMessage(R.string.backup_history, text.ifEmpty { activity.getString(R.string.no_history) }) }
    }
    private fun folderStatus() = background {
        val mappings = app.configurationStore.mappings("primary")
        val health = app.durableBackupStore.health("primary")
        val checkpoint = app.durableBackupStore.checkpointForPlanning("primary", "external_primary")
        val pending = app.durableBackupStore.activeJob("primary")
        val folderHealth = mappings.associate { it.id to app.database.dao().folderHealth(it.id) }
        val text = mappings.joinToString("\n\n") { mapping ->
            val root = pending?.roots?.find { it.folderMappingId == mapping.id }
            "${mapping.displayName}\n${mapping.relativeRemotePath}/\n${activity.getString(if (mapping.mode == "MEDIA_FAST") R.string.media_only_fast else R.string.all_files_slower)}\n" +
                activity.getString(R.string.last_checked_format, folderHealth[mapping.id]?.lastCheckedAtEpochMillis?.let(::date) ?: activity.getString(R.string.last_backup_never)) + "\n" +
                activity.getString(R.string.last_verified_format, folderHealth[mapping.id]?.lastVerifiedAtEpochMillis?.let(::date) ?: activity.getString(R.string.last_backup_never)) +
                folderHealth[mapping.id]?.verificationScope?.let { " (${operationName(it)})" }.orEmpty() + "\n" +
                activity.getString(if (!File(mapping.canonicalLocalPath).isDirectory) R.string.folder_unavailable else if (root != null) R.string.folder_pending else if (checkpoint == null) R.string.folder_needs_check else R.string.folder_last_completed)
        } + "\n\n" + activity.getString(R.string.last_checked_format, health?.lastCheckedAtEpochMillis?.let(::date) ?: activity.getString(R.string.last_backup_never)) +
            "\n" + activity.getString(R.string.last_verified_format, health?.lastVerifiedAtEpochMillis?.let(::date) ?: activity.getString(R.string.last_backup_never))
        onMain { textMessage(R.string.folder_status, text) }
    }
    private fun storageUsage() = background {
        val bytes = app.noBackupFilesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        app.durableBackupStore.cleanupSucceededJobs()
        onMain { textMessage(R.string.local_storage_usage, com.d35p4c1t0.piffbackup.backup.UserFacingFormat.bytes(bytes)) }
    }
    private fun operationStatus(status: String) = activity.getString(when (status) {
        "SUCCEEDED" -> R.string.operation_completed
        "PLANNED", "QUEUED" -> R.string.backup_queued
        "RUNNING" -> R.string.operation_running
        "PAUSED" -> R.string.backup_paused
        "SUPERSEDED" -> R.string.operation_discarded
        else -> R.string.needs_attention
    })
    private fun errorHelp(code: String) = activity.getString(when {
        code == "REMOTE_DIFFERENCES" -> R.string.error_remote_differences
        code == "SOURCE_CHANGED" -> R.string.error_source_changed
        code.contains("VANISHED") || code.contains("FILE_IO") || code.contains("PARTIAL_TRANSFER") -> R.string.error_selected_files
        code == "ARCHIVE_AUTHENTICATION_FAILED" -> R.string.error_archive_authentication
        code.contains("TIMEOUT") || code.contains("SOCKET") || code.contains("PROTOCOL") || code.contains("SERVER_START") -> R.string.error_connection_retry
        else -> R.string.tools_failed
    })

    private fun operationName(kind: String) = activity.getString(when {
        kind.startsWith("ARCHIVE") -> R.string.encrypted_archives
        kind.startsWith("RESTORE") -> R.string.restore_backup
        kind == "VERIFY_SAMPLE" -> R.string.verify_sample
        else -> R.string.verify_full
    })
    private fun date(value: Long) = android.text.format.DateFormat.getMediumDateFormat(activity).format(java.util.Date(value)) + " " +
        android.text.format.DateFormat.getTimeFormat(activity).format(java.util.Date(value))
    private fun message(title: Int, body: Int) = MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(body)
        .setPositiveButton(android.R.string.ok, null).show()
    private fun textMessage(title: Int, text: String) {
        val view = TextView(activity).apply { this.text = text; setPadding(32, 16, 32, 16); setTextIsSelectable(true) }
        MaterialAlertDialogBuilder(activity).setTitle(title).setView(ScrollView(activity).apply { addView(view) })
            .setPositiveButton(android.R.string.ok, null).show()
    }
    private fun background(block: suspend () -> Unit) {
        activity.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { app.awaitReady(); block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!activity.isDestroyed) message(R.string.needs_attention, R.string.tools_failed) }
        }
    }
    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { if (!activity.isDestroyed) block() }
}
