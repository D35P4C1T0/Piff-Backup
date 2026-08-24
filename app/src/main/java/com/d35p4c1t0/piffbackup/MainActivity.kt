package com.d35p4c1t0.piffbackup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.d35p4c1t0.piffbackup.adoption.AdoptionTransferProgress
import com.d35p4c1t0.piffbackup.adoption.InitialAdoptionError
import com.d35p4c1t0.piffbackup.adoption.InitialAdoptionPreview
import com.d35p4c1t0.piffbackup.adoption.InitialAdoptionResult
import com.d35p4c1t0.piffbackup.adoption.LocalTreeSelection
import com.d35p4c1t0.piffbackup.adoption.RemoteDirectory
import com.d35p4c1t0.piffbackup.backup.BackupMapping
import com.d35p4c1t0.piffbackup.backup.BackupMappingValidator
import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import com.d35p4c1t0.piffbackup.backup.RemoteRelativePath
import com.d35p4c1t0.piffbackup.backup.UserFacingFormat
import com.d35p4c1t0.piffbackup.data.FolderMappingEntity
import com.d35p4c1t0.piffbackup.data.FolderMappingInput
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileEntity
import com.d35p4c1t0.piffbackup.databinding.ActivityMainBinding
import com.d35p4c1t0.piffbackup.onboarding.HostKeyPin
import com.d35p4c1t0.piffbackup.onboarding.OnboardingErrorCode
import com.d35p4c1t0.piffbackup.onboarding.OnboardingProgress
import com.d35p4c1t0.piffbackup.onboarding.OnboardingRequest
import com.d35p4c1t0.piffbackup.onboarding.OnboardingResult
import com.d35p4c1t0.piffbackup.onboarding.StorageBoxEndpoint
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val app: PiffBackupApp get() = application as PiffBackupApp
    private val draftMappings = mutableListOf<FolderMappingInput>()

    private var activeProfile: StorageBoxProfileEntity? = null
    private var pendingLocalFolder: LocalTreeSelection? = null
    private var remoteBrowserParent: RemoteRelativePath? = null
    private var selectedRemotePath: String? = null
    private var activePreview: InitialAdoptionPreview? = null

    private val storageSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateStorageAccessState()
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) acceptPickedFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindActions()
        loadExistingProfile(forceConnect = false)
    }

    private fun bindActions() {
        binding.startSetupButton.setOnClickListener { showConnect(null) }
        binding.reconnectButton.setOnClickListener { loadExistingProfile(forceConnect = true) }
        binding.advancedHostnameToggle.setOnCheckedChangeListener { _, checked ->
            binding.hostnameLayout.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.connectButton.setOnClickListener { beginConnection() }
        binding.openConsoleButton.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, HETZNER_CONSOLE_URL.toUri())) }
        }
        binding.chooseFoldersButton.setOnClickListener { showMappingSetup() }
        binding.grantStorageAccessButton.setOnClickListener { openAllFilesSettings() }
        binding.addLocalFolderButton.setOnClickListener { pickLocalFolder() }
        binding.clearMappingsButton.setOnClickListener {
            draftMappings.clear()
            hideMappingEditor()
            renderDraftMappings()
        }
        binding.remoteUpButton.setOnClickListener { browseUp() }
        binding.useRemoteFolderButton.setOnClickListener {
            selectRemotePath(requireNotNull(remoteBrowserParent).value)
        }
        binding.useNewRemoteFolderButton.setOnClickListener { selectNewRemoteFolder() }
        binding.saveMappingButton.setOnClickListener { savePendingMapping() }
        binding.previewAdoptionButton.setOnClickListener { beginAdoptionPreview() }
        binding.startAdoptionButton.setOnClickListener { beginConfirmedAdoption() }
        binding.changeMappingsButton.setOnClickListener {
            app.initialAdoptionCoordinator.discardPreview()
            activePreview = null
            showMappingSetup()
        }
        binding.cancelAdoptionButton.setOnClickListener {
            binding.cancelAdoptionButton.isEnabled = false
            app.initialAdoptionCoordinator.cancel()
        }
        binding.backupAgainButton.setOnClickListener { checkForNewFiles() }
        binding.completeChangeMappingsButton.setOnClickListener { showMappingSetup() }
        binding.completeChangeConnectionButton.setOnClickListener {
            loadExistingProfile(forceConnect = true)
        }
    }

    private fun loadExistingProfile(forceConnect: Boolean) {
        executor.execute {
            val state = runBlocking {
                val profile = app.configurationStore.profile(OnboardingRequest.DEFAULT_PROFILE_ID)
                val mappings = profile?.let { app.configurationStore.mappings(it.id) }.orEmpty()
                val checkpoint = profile?.let {
                    app.durableBackupStore.checkpointForPlanning(it.id, PRIMARY_VOLUME)
                }
                ExistingState(profile, mappings, checkpoint != null)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                activeProfile = state.profile
                draftMappings.clear()
                draftMappings += state.mappings.map(::mappingInput)
                val profile = state.profile
                if (!forceConnect && profile?.setupCompleted == true) {
                    val pin = runCatching { HostKeyPin.parse(requireNotNull(profile.pinnedHostKey)) }.getOrNull()
                    when {
                        pin == null -> showConnect(profile)
                        state.hasCheckpoint -> showAdoptionComplete(null)
                        app.initialAdoptionCoordinator.currentPreview() != null -> {
                            showAdoptionPreview(requireNotNull(app.initialAdoptionCoordinator.currentPreview()))
                        }
                        else -> showConnected(profile, pin.sha256Fingerprint)
                    }
                } else if (profile != null && (!profile.setupCompleted || forceConnect)) {
                    showConnect(profile)
                } else {
                    showWelcome()
                }
            }
        }
    }

    private fun beginConnection() {
        val passwordEditable = binding.passwordInput.text
        val password = CharArray(passwordEditable?.length ?: 0) { index -> passwordEditable!![index] }
        passwordEditable?.clear()
        val endpoint = runCatching {
            StorageBoxEndpoint.create(
                username = binding.usernameInput.text?.toString().orEmpty(),
                advancedHostname = binding.hostnameInput.text?.toString()
                    .takeIf { binding.advancedHostnameToggle.isChecked },
            )
        }.getOrElse {
            password.fill('\u0000')
            showConnectionError(OnboardingErrorCode.INVALID_INPUT)
            return
        }
        val remoteBasePath = runCatching {
            RemoteRelativePath.create(binding.remoteBasePathInput.text?.toString()?.trim().orEmpty())
        }.getOrElse {
            password.fill('\u0000')
            showConnectionError(OnboardingErrorCode.INVALID_INPUT)
            return
        }
        val request = runCatching {
            OnboardingRequest(endpoint = endpoint, remoteBasePath = remoteBasePath, password = password)
        }.getOrElse {
            password.fill('\u0000')
            showConnectionError(OnboardingErrorCode.INVALID_INPUT)
            return
        }
        setConnectionBusy(true)
        executor.execute {
            val result = runBlocking {
                app.onboardingCoordinator.onboard(request) { progress ->
                    runOnUiThread { if (!isDestroyed) showConnectionProgress(progress) }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setConnectionBusy(false)
                when (result) {
                    is OnboardingResult.Success -> loadExistingProfile(forceConnect = false)
                    is OnboardingResult.Failure -> showConnectionError(result.code)
                }
            }
        }
    }

    private fun showWelcome() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showOnly(binding.welcomeGroup)
    }

    private fun showConnect(profile: StorageBoxProfileEntity?) {
        app.initialAdoptionCoordinator.discardPreview()
        activePreview = null
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showOnly(binding.connectGroup)
        val username = profile?.username.orEmpty()
        binding.usernameInput.setText(username)
        binding.remoteBasePathInput.setText(profile?.remoteBasePath.orEmpty())
        val derived = "$username.your-storagebox.de"
        val advanced = profile?.hostname?.takeIf { it != derived }
        binding.advancedHostnameToggle.isChecked = advanced != null
        binding.hostnameInput.setText(advanced.orEmpty())
        binding.passwordInput.text?.clear()
        binding.connectError.visibility = View.GONE
        binding.openConsoleButton.visibility = View.GONE
        binding.connectStatus.visibility = View.GONE
        if (username.isEmpty()) binding.usernameInput.requestFocus() else binding.passwordInput.requestFocus()
    }

    private fun showConnected(profile: StorageBoxProfileEntity, fingerprint: String) {
        activeProfile = profile
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showOnly(binding.connectedGroup)
        binding.connectedSummary.text = getString(
            R.string.connected_summary,
            profile.hostname,
            profile.remoteBasePath.trimEnd('/') + "/",
        )
        binding.serverFingerprint.text = fingerprint
    }

    private fun showMappingSetup() {
        val profile = activeProfile ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showOnly(binding.adoptionMappingGroup)
        binding.adoptionError.visibility = View.GONE
        binding.adoptionDiscoveryProgress.visibility = View.GONE
        binding.previewAdoptionButton.isEnabled = true
        pendingLocalFolder = null
        remoteBrowserParent = RemoteRelativePath.create(profile.remoteBasePath)
        selectedRemotePath = null
        hideMappingEditor()
        updateStorageAccessState()
        renderDraftMappings()
    }

    private fun updateStorageAccessState() {
        if (!::binding.isInitialized) return
        val granted = Environment.isExternalStorageManager()
        binding.storageAccessStatus.setText(
            if (granted) R.string.storage_access_granted else R.string.storage_access_missing,
        )
        binding.grantStorageAccessButton.visibility = if (granted) View.GONE else View.VISIBLE
        binding.addLocalFolderButton.isEnabled = granted
        binding.previewAdoptionButton.isEnabled = granted && draftMappings.isNotEmpty()
    }

    private fun openAllFilesSettings() {
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:$packageName".toUri(),
        )
        runCatching { storageSettings.launch(appIntent) }
            .getOrElse { storageSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    private fun pickLocalFolder() {
        if (!Environment.isExternalStorageManager()) {
            showAdoptionError(InitialAdoptionError.STORAGE_PERMISSION_REQUIRED)
            return
        }
        folderPicker.launch(null)
    }

    private fun acceptPickedFolder(uri: Uri) {
        val selection = runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.treeSelectionResolver.resolve(uri)
        }.getOrElse {
            showAdoptionError(InitialAdoptionError.INVALID_CONFIGURATION)
            return
        }
        pendingLocalFolder = selection
        selectedRemotePath = null
        remoteBrowserParent = RemoteRelativePath.create(requireNotNull(activeProfile).remoteBasePath)
        binding.mappingEditorGroup.visibility = View.VISIBLE
        binding.selectedLocalFolder.text = getString(R.string.selected_local_folder_format, selection.displayName)
        binding.newRemoteFolderInput.setText(selection.displayName)
        binding.mediaFastMode.isChecked = true
        renderSelectedRemotePath()
        loadRemoteDirectories()
    }

    private fun loadRemoteDirectories() {
        val profile = activeProfile ?: return
        val parent = remoteBrowserParent ?: return
        binding.remoteBrowserPath.text = getString(R.string.remote_browser_path_format, parent.value)
        binding.remoteBrowserStatus.setText(R.string.remote_browser_loading)
        binding.remoteDirectoryList.removeAllViews()
        binding.remoteUpButton.isEnabled = parent.value != profile.remoteBasePath
        executor.execute {
            val directories = runCatching { app.remoteDirectoryBrowser.list(profile, parent) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || remoteBrowserParent?.value != parent.value) return@runOnUiThread
                if (directories == null) binding.remoteBrowserStatus.setText(R.string.remote_browser_failed)
                else renderRemoteDirectories(directories)
            }
        }
    }

    private fun renderRemoteDirectories(directories: List<RemoteDirectory>) {
        binding.remoteDirectoryList.removeAllViews()
        binding.remoteBrowserStatus.setText(
            if (directories.isEmpty()) R.string.remote_browser_empty else R.string.use_this_remote_folder,
        )
        directories.forEach { directory ->
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            button.text = getString(R.string.remote_folder_button_format, directory.name)
            button.isAllCaps = false
            button.minHeight = resources.getDimensionPixelSize(R.dimen.adoption_touch_target)
            button.setOnClickListener {
                remoteBrowserParent = RemoteRelativePath.create(directory.relativePath)
                loadRemoteDirectories()
            }
            binding.remoteDirectoryList.addView(button)
        }
        val localName = pendingLocalFolder?.displayName
        val suggestion = directories.firstOrNull { it.name.equals(localName, ignoreCase = true) }
        if (selectedRemotePath == null && suggestion != null) selectRemotePath(suggestion.relativePath)
    }

    private fun browseUp() {
        val profileBase = RemoteRelativePath.create(requireNotNull(activeProfile).remoteBasePath)
        val parent = requireNotNull(remoteBrowserParent)
        if (parent.value == profileBase.value) return
        remoteBrowserParent = RemoteRelativePath.create(parent.components.dropLast(1).joinToString("/"))
        if (!profileBase.isSameOrAncestorOf(requireNotNull(remoteBrowserParent))) remoteBrowserParent = profileBase
        loadRemoteDirectories()
    }

    private fun selectNewRemoteFolder() {
        val parent = remoteBrowserParent ?: return
        val name = binding.newRemoteFolderInput.text?.toString()?.trim().orEmpty()
        val path = runCatching {
            require(name.isNotEmpty() && '/' !in name && name != "." && name != "..")
            RemoteRelativePath.create("${parent.value}/$name").value
        }.getOrElse {
            showAdoptionError(InitialAdoptionError.INVALID_CONFIGURATION)
            return
        }
        selectRemotePath(path)
    }

    private fun selectRemotePath(path: String) {
        selectedRemotePath = RemoteRelativePath.create(path).value
        renderSelectedRemotePath()
    }

    private fun renderSelectedRemotePath() {
        val path = selectedRemotePath
        binding.selectedRemoteFolder.text = if (path == null) "" else {
            getString(R.string.selected_remote_folder_format, path)
        }
    }

    private fun savePendingMapping() {
        val local = pendingLocalFolder ?: return
        val remote = selectedRemotePath ?: run {
            showAdoptionError(InitialAdoptionError.INVALID_CONFIGURATION)
            return
        }
        val input = FolderMappingInput(
            id = UUID.randomUUID().toString(),
            displayName = local.displayName,
            treeUri = local.treeUri,
            canonicalLocalPath = local.canonicalPath,
            relativeMediaStorePrefix = local.relativeMediaStorePrefix,
            relativeRemotePath = remote,
            mode = if (binding.allFilesMode.isChecked) MappingModeValue.ALL_FILES else MappingModeValue.MEDIA_FAST,
        )
        val candidate = draftMappings + input
        if (!validateDraftMappings(candidate)) {
            showAdoptionError(InitialAdoptionError.INVALID_CONFIGURATION)
            return
        }
        draftMappings += input
        pendingLocalFolder = null
        selectedRemotePath = null
        hideMappingEditor()
        binding.adoptionError.visibility = View.GONE
        renderDraftMappings()
    }

    private fun validateDraftMappings(inputs: List<FolderMappingInput>): Boolean = runCatching {
        val shared = Environment.getExternalStorageDirectory()
        BackupMappingValidator.validate(
            inputs.map { input ->
                BackupMapping(
                    CanonicalLocalRoot.create(input.canonicalLocalPath, shared),
                    RemoteRelativePath.create(input.relativeRemotePath),
                )
            },
            RemoteRelativePath.create(requireNotNull(activeProfile).remoteBasePath),
        )
    }.isSuccess

    private fun renderDraftMappings() {
        binding.configuredMappings.text = if (draftMappings.isEmpty()) {
            getString(R.string.no_folders_configured)
        } else {
            draftMappings.joinToString("\n") { mapping ->
                getString(
                    R.string.configured_mapping_format,
                    mapping.displayName,
                    mapping.relativeRemotePath,
                    getString(
                        if (mapping.mode == MappingModeValue.MEDIA_FAST) {
                            R.string.media_only_fast
                        } else {
                            R.string.all_files_slower
                        },
                    ),
                )
            }
        }
        binding.clearMappingsButton.visibility = if (draftMappings.isEmpty()) View.GONE else View.VISIBLE
        binding.previewAdoptionButton.isEnabled =
            Environment.isExternalStorageManager() && draftMappings.isNotEmpty()
    }

    private fun hideMappingEditor() {
        binding.mappingEditorGroup.visibility = View.GONE
        binding.remoteDirectoryList.removeAllViews()
    }

    private fun beginAdoptionPreview() {
        val profile = activeProfile ?: return
        if (!Environment.isExternalStorageManager()) {
            showAdoptionError(InitialAdoptionError.STORAGE_PERMISSION_REQUIRED)
            return
        }
        binding.previewAdoptionButton.isEnabled = false
        binding.addLocalFolderButton.isEnabled = false
        binding.adoptionError.visibility = View.GONE
        binding.adoptionDiscoveryProgress.visibility = View.VISIBLE
        executor.execute {
            val result = runBlocking { app.initialAdoptionCoordinator.preview(profile.id, draftMappings.toList()) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.adoptionDiscoveryProgress.visibility = View.GONE
                binding.addLocalFolderButton.isEnabled = true
                when (result) {
                    is InitialAdoptionResult.Success -> showAdoptionPreview(result.value)
                    is InitialAdoptionResult.Failure -> {
                        binding.previewAdoptionButton.isEnabled = true
                        showAdoptionError(result.error)
                    }
                }
            }
        }
    }

    private fun showAdoptionPreview(preview: InitialAdoptionPreview) {
        activePreview = preview
        showOnly(binding.adoptionPreviewGroup)
        binding.adoptionPreviewError.visibility = View.GONE
        binding.adoptionSummary.text = getString(
            R.string.adoption_summary_format,
            UserFacingFormat.itemCount(preview.summary.alreadyBackedUpItems),
            UserFacingFormat.itemCount(preview.summary.itemsToUpload),
            UserFacingFormat.bytes(preview.summary.bytesToUpload),
        )
        binding.startAdoptionButton.isEnabled = true
    }

    private fun beginConfirmedAdoption() {
        val preview = activePreview ?: return
        showOnly(binding.adoptionTransferGroup)
        binding.cancelAdoptionButton.isEnabled = true
        binding.adoptionTransferProgress.setProgressCompat(0, false)
        executor.execute {
            val result = runBlocking {
                app.initialAdoptionCoordinator.confirm(preview.id) { progress ->
                    runOnUiThread { if (!isDestroyed) showTransferProgress(progress) }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is InitialAdoptionResult.Success -> showAdoptionComplete(result.value.itemsToUpload)
                    is InitialAdoptionResult.Failure -> {
                        showAdoptionPreview(preview)
                        binding.adoptionPreviewError.visibility = View.VISIBLE
                        binding.adoptionPreviewError.setText(adoptionErrorMessage(result.error))
                    }
                }
            }
        }
    }

    private fun showTransferProgress(progress: AdoptionTransferProgress) {
        binding.adoptionTransferProgress.setProgressCompat(progress.percentage, true)
        binding.adoptionTransferStatus.text = getString(
            R.string.adoption_progress_format,
            progress.rootNumber,
            progress.rootCount,
            progress.rootName,
            progress.percentage,
        )
    }

    private fun showAdoptionComplete(uploadedItems: Long?) {
        activePreview = null
        showOnly(binding.adoptionCompleteGroup)
        binding.adoptionCompleteSummary.text = if (uploadedItems == null) {
            getString(R.string.initial_backup_complete)
        } else {
            getString(R.string.adoption_complete_format, UserFacingFormat.itemCount(uploadedItems))
        }
    }

    private fun checkForNewFiles() {
        showMappingSetup()
        if (Environment.isExternalStorageManager() && draftMappings.isNotEmpty()) {
            beginAdoptionPreview()
        }
    }

    private fun showAdoptionError(error: InitialAdoptionError) {
        binding.adoptionError.visibility = View.VISIBLE
        binding.adoptionError.setText(adoptionErrorMessage(error))
    }

    private fun adoptionErrorMessage(error: InitialAdoptionError): Int = when (error) {
        InitialAdoptionError.INVALID_CONFIGURATION -> R.string.adoption_error_invalid
        InitialAdoptionError.STORAGE_PERMISSION_REQUIRED,
        InitialAdoptionError.MEDIA_SNAPSHOT_UNAVAILABLE,
        -> R.string.adoption_error_permission
        InitialAdoptionError.PREVIEW_FAILED -> R.string.adoption_error_preview
        InitialAdoptionError.CONFIGURATION_CHANGED -> R.string.adoption_error_changed
        InitialAdoptionError.TRANSFER_FAILED -> R.string.adoption_error_transfer
        InitialAdoptionError.CANCELLED -> R.string.adoption_error_cancelled
        InitialAdoptionError.SECURE_STORAGE_FAILED -> R.string.adoption_error_storage
    }

    private fun setConnectionBusy(busy: Boolean) {
        binding.connectButton.isEnabled = !busy
        binding.usernameInput.isEnabled = !busy
        binding.passwordInput.isEnabled = !busy
        binding.advancedHostnameToggle.isEnabled = !busy
        binding.hostnameInput.isEnabled = !busy
        binding.connectProgress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            binding.connectError.visibility = View.GONE
            binding.openConsoleButton.visibility = View.GONE
        }
    }

    private fun showConnectionProgress(progress: OnboardingProgress) {
        binding.connectStatus.visibility = View.VISIBLE
        binding.connectStatus.setText(
            when (progress) {
                OnboardingProgress.PREPARING_KEY -> R.string.progress_preparing_key
                OnboardingProgress.CONNECTING_WITH_PASSWORD -> R.string.progress_connecting_password
                OnboardingProgress.INSTALLING_KEY -> R.string.progress_installing_key
                OnboardingProgress.VERIFYING_KEY_AND_DESTINATION -> R.string.progress_verifying
                OnboardingProgress.SAVING -> R.string.progress_saving
            },
        )
    }

    private fun showConnectionError(code: OnboardingErrorCode) {
        binding.connectStatus.visibility = View.GONE
        binding.connectError.visibility = View.VISIBLE
        binding.connectError.setText(
            when (code) {
                OnboardingErrorCode.INVALID_INPUT -> R.string.error_invalid_input
                OnboardingErrorCode.NETWORK_UNAVAILABLE -> R.string.error_network
                OnboardingErrorCode.AUTHENTICATION_FAILED -> R.string.error_authentication
                OnboardingErrorCode.HOST_KEY_CHANGED -> R.string.error_host_changed
                OnboardingErrorCode.KEY_INSTALL_FAILED -> R.string.error_key_install
                OnboardingErrorCode.KEY_VERIFICATION_FAILED -> R.string.error_key_verify
                OnboardingErrorCode.DESTINATION_NOT_FOUND -> R.string.error_destination_missing
                OnboardingErrorCode.SECURE_STORAGE_FAILED -> R.string.error_secure_storage
            },
        )
        binding.openConsoleButton.visibility = if (
            code == OnboardingErrorCode.NETWORK_UNAVAILABLE ||
            code == OnboardingErrorCode.HOST_KEY_CHANGED ||
            code == OnboardingErrorCode.KEY_INSTALL_FAILED
        ) View.VISIBLE else View.GONE
    }

    private fun showOnly(visible: View) {
        listOf(
            binding.welcomeGroup,
            binding.connectGroup,
            binding.connectedGroup,
            binding.adoptionMappingGroup,
            binding.adoptionPreviewGroup,
            binding.adoptionTransferGroup,
            binding.adoptionCompleteGroup,
        ).forEach { it.visibility = if (it === visible) View.VISIBLE else View.GONE }
    }

    private fun mappingInput(entity: FolderMappingEntity) = FolderMappingInput(
        id = entity.id,
        displayName = entity.displayName,
        treeUri = entity.treeUri,
        canonicalLocalPath = entity.canonicalLocalPath,
        relativeMediaStorePrefix = entity.relativeMediaStorePrefix,
        relativeRemotePath = entity.relativeRemotePath,
        mode = entity.mode,
        enabled = entity.enabled,
    )

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && binding.adoptionMappingGroup.isVisible) {
            updateStorageAccessState()
        }
    }

    override fun onDestroy() {
        binding.passwordInput.text?.clear()
        app.initialAdoptionCoordinator.cancel()
        app.remoteDirectoryBrowser.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    private data class ExistingState(
        val profile: StorageBoxProfileEntity?,
        val mappings: List<FolderMappingEntity>,
        val hasCheckpoint: Boolean,
    )

    private companion object {
        const val HETZNER_CONSOLE_URL = "https://console.hetzner.com/"
        const val PRIMARY_VOLUME = "external_primary"
    }
}
