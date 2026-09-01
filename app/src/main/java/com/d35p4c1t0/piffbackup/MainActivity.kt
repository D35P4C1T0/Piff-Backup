package com.d35p4c1t0.piffbackup

import android.animation.ValueAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
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
import com.d35p4c1t0.piffbackup.data.DurablePendingJob
import com.d35p4c1t0.piffbackup.data.MappingModeValue
import com.d35p4c1t0.piffbackup.data.PendingBackupJobEntity
import com.d35p4c1t0.piffbackup.data.PendingJobStatusValue
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileEntity
import com.d35p4c1t0.piffbackup.databinding.ActivityMainBinding
import com.d35p4c1t0.piffbackup.onboarding.HostKeyPin
import com.d35p4c1t0.piffbackup.onboarding.OnboardingConnection
import com.d35p4c1t0.piffbackup.onboarding.OnboardingErrorCode
import com.d35p4c1t0.piffbackup.onboarding.OnboardingProgress
import com.d35p4c1t0.piffbackup.onboarding.OnboardingRequest
import com.d35p4c1t0.piffbackup.onboarding.OnboardingResult
import com.d35p4c1t0.piffbackup.onboarding.StorageBoxEndpoint
import com.d35p4c1t0.piffbackup.scheduling.BackupDiscoveryResult
import com.d35p4c1t0.piffbackup.scheduling.BackupProgressEvent
import com.d35p4c1t0.piffbackup.scheduling.BackupProgressEvents
import com.d35p4c1t0.piffbackup.scheduling.BackupProgressStatus
import com.d35p4c1t0.piffbackup.ui.HomeBackupStatus
import com.d35p4c1t0.piffbackup.ui.HomeScreenState
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.runBlocking
import java.util.Date
import java.util.UUID
import java.util.ArrayDeque
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
    private var selectedOnboardingRoot: RemoteDirectory? = null
    private var activePreview: InitialAdoptionPreview? = null
    private var previewPurpose = PreviewPurpose.INITIAL_ADOPTION
    private var hasCompletedAdoption = false
    private var latestHomeState: HomeScreenState? = null
    private var oneShotHomeMessage: String? = null
    private var activePendingJobId: String? = null
    private var transferLogJobId: String? = null
    private val transferLogEntries = ArrayDeque<String>()
    private var jobAwaitingNotificationPermission: String? = null
    private var currentScreen: View? = null
    private var restoreTarget: RestoreTarget? = null
    private var restoredProfileId: String? = null
    private var restoredDraftMappings: List<FolderMappingInput>? = null
    private var restoredPendingLocalFolder: LocalTreeSelection? = null
    private var restoredRemoteBrowserParent: String? = null
    private var restoredSelectedRemotePath: String? = null
    private var restoredNewRemoteFolderName: String? = null
    private var restoredAllFilesMode = false

    private val backupProgressListener: (BackupProgressEvent) -> Unit = { event ->
        runOnUiThread {
            if (!isDestroyed && event.jobId == activePendingJobId) {
                when (event.status) {
                    BackupProgressStatus.RUNNING -> latestHomeState?.let { state ->
                        event.fileName?.let { appendTransferLogEntry(event.jobId, it) }
                        showHome(
                            state.copy(
                                status = HomeBackupStatus.BACKING_UP,
                                progressPercentage = event.percentage,
                            ),
                        )
                    }
                    BackupProgressStatus.PAUSED -> loadExistingProfile(forceConnect = false)
                    BackupProgressStatus.SUCCEEDED -> loadExistingProfile(forceConnect = false)
                    BackupProgressStatus.FAILED -> {
                        latestHomeState?.let { state ->
                            showHome(state.copy(status = HomeBackupStatus.NEEDS_ATTENTION, progressPercentage = null))
                        }
                    }
                }
            }
        }
    }

    private val preferences by lazy {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
    }

    private val storageSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateStorageAccessState()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val jobId = jobAwaitingNotificationPermission
        jobAwaitingNotificationPermission = null
        if (it && jobId != null) {
            schedulePendingBackup(jobId)
        } else if (jobId != null) {
            oneShotHomeMessage = getString(R.string.notification_permission_needed)
            latestHomeState?.let { state ->
                showHome(state.copy(status = HomeBackupStatus.NEEDS_ATTENTION, progressPercentage = null))
            }
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) acceptPickedFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        restoreTransientUiState(savedInstanceState)
        configureResponsiveScaffold()
        bindActions()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.destinationGroup.isVisible) {
                        app.remoteDirectoryBrowser.cancel()
                        app.onboardingCoordinator.discardPendingConnection()
                        showConnect(activeProfile)
                    } else if (binding.mappingEditorGroup.isVisible) {
                        closeMappingEditor()
                    } else if (binding.adoptionFlow.adoptionTransferGroup.isVisible) {
                        // Keep the foreground adoption visible so Back cannot silently cancel it.
                        return
                    } else if (binding.adoptionFlow.adoptionPreviewGroup.isVisible) {
                        app.initialAdoptionCoordinator.discardPreview()
                        activePreview = null
                        showMappingSetup()
                    } else if (binding.adoptionMappingGroup.isVisible && !hasCompletedAdoption) {
                        showConnect(activeProfile)
                    } else if (binding.connectGroup.isVisible && !hasCompletedAdoption) {
                        showWelcome()
                    } else if (hasCompletedAdoption && !binding.homeGroup.root.isVisible) {
                        app.initialAdoptionCoordinator.discardPreview()
                        activePreview = null
                        loadExistingProfile(forceConnect = false)
                    } else {
                        finish()
                    }
                }
            },
        )
        loadExistingProfile(forceConnect = false)
    }

    private fun configureResponsiveScaffold() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.mainScroll.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        val availableWidth = windowManager.currentWindowMetrics.bounds.width()
        val maxWidth = resources.getDimensionPixelSize(R.dimen.content_max_width)
        binding.screensContainer.updateLayoutParams<FrameLayout.LayoutParams> {
            width = minOf(availableWidth, maxWidth)
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER_HORIZONTAL
        }
        ViewCompat.requestApplyInsets(binding.appRoot)
    }

    private fun bindActions() {
        binding.welcomeGroup.startSetupButton.setOnClickListener { showConnect(null) }
        binding.connectToolbar.setNavigationOnClickListener {
            if (hasCompletedAdoption) loadExistingProfile(forceConnect = false) else showWelcome()
        }
        binding.advancedHostnameToggle.setOnCheckedChangeListener { _, checked ->
            binding.hostnameLayout.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.connectButton.setOnClickListener { beginConnection() }
        binding.confirmDestinationButton.setOnClickListener {
            selectedOnboardingRoot?.let { completeDestinationSelection(it.relativePath) }
        }
        binding.retryDestinationListButton.setOnClickListener { loadTopLevelDirectories() }
        binding.changeDestinationConnectionButton.setOnClickListener {
            app.remoteDirectoryBrowser.cancel()
            app.onboardingCoordinator.discardPendingConnection()
            showConnect(activeProfile)
        }
        binding.destinationToolbar.setNavigationOnClickListener {
            app.remoteDirectoryBrowser.cancel()
            app.onboardingCoordinator.discardPendingConnection()
            showConnect(activeProfile)
        }
        binding.openConsoleButton.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, HETZNER_CONSOLE_URL.toUri())) }
        }
        binding.storageAccessPanel.grantStorageAccessButton.setOnClickListener { openAllFilesSettings() }
        binding.addLocalFolderButton.setOnClickListener { pickLocalFolder() }
        binding.clearMappingsButton.setOnClickListener {
            confirmClearMappings()
        }
        binding.foldersToolbar.setNavigationOnClickListener {
            if (hasCompletedAdoption) loadExistingProfile(forceConnect = false) else showConnect(activeProfile)
        }
        binding.mappingEditorToolbar.setNavigationOnClickListener { closeMappingEditor() }
        binding.mappingDetailsButton.setOnClickListener {
            toggleCompactSection(binding.mappingDetailsGroup, binding.mappingDetailsButton)
        }
        binding.newRemoteFolderToggle.setOnClickListener {
            toggleCompactSection(binding.newRemoteFolderGroup, binding.newRemoteFolderToggle)
        }
        binding.mappingModeHelpToggle.setOnClickListener {
            toggleCompactSection(binding.mappingModeHelp, binding.mappingModeHelpToggle)
        }
        binding.remoteUpButton.setOnClickListener { browseUp() }
        binding.retryRemoteListButton.setOnClickListener { loadRemoteDirectories() }
        binding.useRemoteFolderButton.setOnClickListener {
            selectRemotePath(requireNotNull(remoteBrowserParent).value)
        }
        binding.useNewRemoteFolderButton.setOnClickListener { selectNewRemoteFolder() }
        binding.saveMappingButton.setOnClickListener { savePendingMapping() }
        binding.previewAdoptionButton.setOnClickListener { beginAdoptionPreview() }
        binding.adoptionFlow.startAdoptionButton.setOnClickListener { beginConfirmedAdoption() }
        binding.adoptionFlow.changeMappingsButton.setOnClickListener {
            app.initialAdoptionCoordinator.discardPreview()
            activePreview = null
            showMappingSetup()
        }
        binding.adoptionFlow.cancelAdoptionButton.setOnClickListener {
            binding.adoptionFlow.cancelAdoptionButton.isEnabled = false
            app.initialAdoptionCoordinator.cancel()
        }
        binding.adoptionFlow.adoptionTransferLogToggle.setOnClickListener {
            toggleCompactSection(
                binding.adoptionFlow.adoptionTransferLogEntries,
                binding.adoptionFlow.adoptionTransferLogToggle,
            )
        }
        binding.homeGroup.homeBackupButton.setOnClickListener { handleHomePrimaryAction() }
        binding.homeGroup.homeTransferLogToggle.setOnClickListener {
            toggleCompactSection(
                binding.homeGroup.homeTransferLogEntries,
                binding.homeGroup.homeTransferLogToggle,
            )
        }
        binding.homeGroup.homeFoldersButton.setOnClickListener {
            app.initialAdoptionCoordinator.discardPreview()
            activePreview = null
            showMappingSetup()
        }
        binding.homeGroup.homeSettingsButton.setOnClickListener { showSettings() }
        binding.adoptionFlow.previewBackHomeButton.setOnClickListener {
            app.initialAdoptionCoordinator.discardPreview()
            activePreview = null
            loadExistingProfile(forceConnect = false)
        }
        binding.settingsGroup.settingsToolbar.setNavigationOnClickListener {
            loadExistingProfile(forceConnect = false)
        }
        binding.settingsGroup.settingsChangeConnectionButton.setOnClickListener {
            loadExistingProfile(forceConnect = true)
        }
        binding.settingsGroup.openBatterySettingsButton.setOnClickListener { openAppBatterySettings() }
        binding.settingsGroup.automaticUploadSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit { putBoolean(AUTOMATIC_UPLOAD_KEY, checked) }
        }
        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                beginConnection()
                true
            } else {
                false
            }
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
                val lastSuccessfulRun = profile?.let { app.durableBackupStore.latestSuccessfulRun(it.id) }
                val pending = profile?.let { app.durableBackupStore.activeJob(it.id) }
                val problem = profile?.let { app.durableBackupStore.latestProblemJob(it.id) }
                ExistingState(
                    profile,
                    mappings,
                    checkpoint != null,
                    lastSuccessfulRun?.finishedAtEpochMillis,
                    pending,
                    problem,
                )
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                activeProfile = state.profile
                draftMappings.clear()
                val restoredMappings = restoredDraftMappings
                    ?.takeIf { state.profile?.id == restoredProfileId }
                draftMappings += restoredMappings ?: state.mappings.map(::mappingInput)
                restoredDraftMappings = null
                hasCompletedAdoption = state.lastSuccessfulBackupAtEpochMillis != null || state.hasCheckpoint
                selectActivePendingJob(state.pending?.job?.id)
                val profile = state.profile
                val pendingConnection = app.onboardingCoordinator.pendingConnection()
                val target = restoreTarget.also { restoreTarget = null }
                if (!forceConnect && pendingConnection != null) {
                    showDestinationPicker(pendingConnection)
                } else if (!forceConnect && target == RestoreTarget.SETTINGS && profile?.setupCompleted == true) {
                    showSettings()
                } else if (!forceConnect && target == RestoreTarget.FOLDERS && profile?.setupCompleted == true) {
                    showMappingSetup()
                } else if (!forceConnect && target == RestoreTarget.MAPPING_EDITOR && profile?.setupCompleted == true) {
                    showRestoredMappingEditor(profile)
                } else if (!forceConnect && profile?.setupCompleted == true) {
                    val pin = runCatching { HostKeyPin.parse(requireNotNull(profile.pinnedHostKey)) }.getOrNull()
                    when {
                        pin == null -> showConnect(profile)
                        app.initialAdoptionCoordinator.currentPreview() != null -> {
                            previewPurpose = if (state.lastSuccessfulBackupAtEpochMillis == null) {
                                PreviewPurpose.INITIAL_ADOPTION
                            } else {
                                PreviewPurpose.NORMAL_BACKUP
                            }
                            showAdoptionPreview(requireNotNull(app.initialAdoptionCoordinator.currentPreview()))
                        }
                        hasCompletedAdoption -> showHome(homeState(state))
                        else -> showMappingSetup()
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
        binding.usernameLayout.error = null
        binding.passwordLayout.error = null
        binding.hostnameLayout.error = null
        var hasInputError = false
        if (binding.usernameInput.text.isNullOrBlank()) {
            binding.usernameLayout.error = getString(R.string.username_required)
            hasInputError = true
        }
        if (passwordEditable.isNullOrEmpty()) {
            binding.passwordLayout.error = getString(R.string.password_required)
            hasInputError = true
        }
        if (binding.advancedHostnameToggle.isChecked && binding.hostnameInput.text.isNullOrBlank()) {
            binding.hostnameLayout.error = getString(R.string.server_address_required)
            hasInputError = true
        }
        if (hasInputError) return
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
        val request = runCatching {
            OnboardingRequest(endpoint = endpoint, password = password)
        }.getOrElse {
            password.fill('\u0000')
            showConnectionError(OnboardingErrorCode.INVALID_INPUT)
            return
        }
        setConnectionBusy(true)
        executor.execute {
            val result = runBlocking {
                app.onboardingCoordinator.connect(request) { progress ->
                    runOnUiThread { if (!isDestroyed) showConnectionProgress(progress) }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setConnectionBusy(false)
                when (result) {
                    is OnboardingResult.Connected -> showDestinationPicker(result.connection)
                    is OnboardingResult.Success -> loadExistingProfile(forceConnect = false)
                    is OnboardingResult.Failure -> showConnectionError(result.code)
                }
            }
        }
    }

    private fun showWelcome() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showOnly(binding.welcomeGroup.root)
    }

    private fun showHome(state: HomeScreenState) {
        latestHomeState = state
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showOnly(binding.homeGroup.root)
        renderHomeState(state)
    }

    private fun homeState(state: ExistingState): HomeScreenState {
        val pending = state.pending?.job
        val mappingCount = state.mappings.count { it.enabled }
        if (pending != null) {
            val percentage = if (pending.totalBytes > 0L) {
                ((pending.completedBytes * 100L) / pending.totalBytes).toInt().coerceIn(0, 100)
            } else {
                0
            }
            return HomeScreenState(
                status = when (pending.status) {
                    PendingJobStatusValue.RUNNING -> HomeBackupStatus.BACKING_UP
                    PendingJobStatusValue.PAUSED,
                    PendingJobStatusValue.RETRYABLE,
                    -> HomeBackupStatus.PAUSED
                    else -> HomeBackupStatus.NEW_ITEMS_READY
                },
                mappingCount = mappingCount,
                lastSuccessfulBackupAtEpochMillis = state.lastSuccessfulBackupAtEpochMillis,
                changedItems = pending.totalFiles,
                changedBytes = pending.totalBytes,
                progressPercentage = if (pending.status == PendingJobStatusValue.RUNNING) percentage else null,
            )
        }
        if (state.problem != null) {
            return HomeScreenState(
                status = HomeBackupStatus.NEEDS_ATTENTION,
                mappingCount = mappingCount,
                lastSuccessfulBackupAtEpochMillis = state.lastSuccessfulBackupAtEpochMillis,
            )
        }
        return HomeScreenState.loaded(
            mappingCount = mappingCount,
            lastSuccessfulBackupAtEpochMillis = state.lastSuccessfulBackupAtEpochMillis,
            hasCurrentCheckpoint = state.hasCheckpoint,
        )
    }

    private fun renderHomeState(state: HomeScreenState) {
        binding.homeGroup.homeStatusTitle.setText(
            when (state.status) {
                HomeBackupStatus.EVERYTHING_BACKED_UP -> R.string.everything_backed_up
                HomeBackupStatus.LOOKING_FOR_CHANGES -> R.string.looking_for_new_media
                HomeBackupStatus.NEW_ITEMS_READY -> R.string.new_items_ready
                HomeBackupStatus.BACKING_UP -> R.string.backing_up_with_percentage
                HomeBackupStatus.PAUSED -> R.string.backup_paused
                HomeBackupStatus.NEEDS_ATTENTION -> R.string.needs_attention
            },
        )
        binding.homeGroup.homeStatusDetail.text = oneShotHomeMessage ?: when (state.status) {
            HomeBackupStatus.EVERYTHING_BACKED_UP -> getString(R.string.home_up_to_date_detail)
            HomeBackupStatus.LOOKING_FOR_CHANGES -> getString(R.string.discovery_safe_detail)
            HomeBackupStatus.NEW_ITEMS_READY -> getString(
                R.string.new_items_summary_format,
                UserFacingFormat.itemCount(state.changedItems),
                UserFacingFormat.bytes(state.changedBytes),
            )
            HomeBackupStatus.BACKING_UP -> getString(
                R.string.backing_up_percentage_format,
                requireNotNull(state.progressPercentage),
            )
            HomeBackupStatus.PAUSED -> getString(R.string.backup_paused_detail)
            HomeBackupStatus.NEEDS_ATTENTION -> getString(
                if (state.mappingCount == 0) R.string.no_folders_detail else R.string.needs_attention_detail,
            )
        }
        oneShotHomeMessage = null
        binding.homeGroup.homeBackupButton.setText(
            when (state.status) {
                HomeBackupStatus.PAUSED -> R.string.resume_backup
                HomeBackupStatus.BACKING_UP -> R.string.pause_backup
                HomeBackupStatus.NEW_ITEMS_READY -> R.string.start_backup
                HomeBackupStatus.NEEDS_ATTENTION -> {
                    if (state.mappingCount == 0) R.string.choose_folders else R.string.try_again
                }
                else -> R.string.back_up_now
            },
        )
        binding.homeGroup.homeBackupButton.setIconResource(
            when {
                state.status == HomeBackupStatus.NEEDS_ATTENTION && state.mappingCount == 0 -> R.drawable.ic_folder
                state.status == HomeBackupStatus.PAUSED -> R.drawable.ic_play
                state.status == HomeBackupStatus.BACKING_UP -> R.drawable.ic_pause
                else -> R.drawable.ic_backup
            },
        )
        binding.homeGroup.homeStatusIcon.setImageResource(
            when (state.status) {
                HomeBackupStatus.EVERYTHING_BACKED_UP -> R.drawable.ic_cloud_done
                HomeBackupStatus.NEEDS_ATTENTION -> R.drawable.ic_warning
                else -> R.drawable.ic_backup
            },
        )
        binding.homeGroup.homeProgress.isVisible =
            state.status == HomeBackupStatus.LOOKING_FOR_CHANGES || state.status == HomeBackupStatus.BACKING_UP
        binding.homeGroup.homeProgress.isIndeterminate = state.status == HomeBackupStatus.LOOKING_FOR_CHANGES
        state.progressPercentage?.let {
            binding.homeGroup.homeProgress.setProgressCompat(it, ValueAnimator.areAnimatorsEnabled())
        }
        binding.homeGroup.homeBackupButton.isEnabled = state.status != HomeBackupStatus.LOOKING_FOR_CHANGES
        val navigationEnabled = state.status != HomeBackupStatus.LOOKING_FOR_CHANGES &&
            state.status != HomeBackupStatus.BACKING_UP
        binding.homeGroup.homeFoldersButton.isEnabled = navigationEnabled
        binding.homeGroup.homeSettingsButton.isEnabled = navigationEnabled
        binding.homeGroup.homeLastBackup.text = state.lastSuccessfulBackupAtEpochMillis?.let {
            getString(R.string.last_backup_format, formatDateTime(it))
        } ?: getString(R.string.last_backup_never)
        binding.homeGroup.homeFoldersButton.text = resources.getQuantityString(
            R.plurals.folders_count,
            state.mappingCount,
            state.mappingCount,
        )
        val transferLogAvailable = transferLogJobId != null && (
            state.status == HomeBackupStatus.BACKING_UP ||
                state.status == HomeBackupStatus.PAUSED ||
                (state.status == HomeBackupStatus.NEEDS_ATTENTION && transferLogEntries.isNotEmpty())
            )
        binding.homeGroup.homeTransferLogCard.isVisible = transferLogAvailable
        if (!transferLogAvailable) collapseTransferLog()
        renderTransferLogEntries()
    }

    private fun selectActivePendingJob(jobId: String?) {
        if (activePendingJobId == jobId && transferLogJobId == jobId) return
        activePendingJobId = jobId
        resetTransferLog(jobId)
    }

    private fun resetTransferLog(jobId: String?) {
        transferLogJobId = jobId
        transferLogEntries.clear()
        if (::binding.isInitialized) {
            collapseTransferLog()
            binding.adoptionFlow.adoptionTransferLogEntries.visibility = View.GONE
            binding.adoptionFlow.adoptionTransferLogToggle.setIconResource(R.drawable.ic_expand_more)
            renderTransferLogEntries()
        }
    }

    private fun appendTransferLogEntry(jobId: String, rawFileName: String) {
        if (transferLogJobId != jobId) resetTransferLog(jobId)
        val fileName = rawFileName
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .trim()
            .take(MAX_TRANSFER_LOG_FILE_NAME_LENGTH)
        if (fileName.isEmpty() || transferLogEntries.peekLast() == fileName) return
        if (transferLogEntries.size == MAX_TRANSFER_LOG_ENTRIES) transferLogEntries.removeFirst()
        transferLogEntries.addLast(fileName)
        renderTransferLogEntries()
    }

    private fun renderTransferLogEntries() {
        val text = if (transferLogEntries.isEmpty()) {
            getString(R.string.transfer_waiting)
        } else {
            transferLogEntries.joinToString("\n") { getString(R.string.transfer_log_entry_format, it) }
        }
        binding.homeGroup.homeTransferLogEntries.text = text
        binding.adoptionFlow.adoptionTransferLogEntries.text = text
    }

    private fun collapseTransferLog() {
        binding.homeGroup.homeTransferLogEntries.visibility = View.GONE
        binding.homeGroup.homeTransferLogToggle.setIconResource(R.drawable.ic_expand_more)
    }

    private fun showConnect(profile: StorageBoxProfileEntity?) {
        app.initialAdoptionCoordinator.discardPreview()
        activePreview = null
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showOnly(binding.connectGroup)
        val username = profile?.username.orEmpty()
        binding.usernameInput.setText(username)
        val derived = "$username.your-storagebox.de"
        val advanced = profile?.hostname?.takeIf { it != derived }
        binding.advancedHostnameToggle.isChecked = advanced != null
        binding.hostnameInput.setText(advanced.orEmpty())
        binding.passwordInput.text?.clear()
        binding.usernameLayout.error = null
        binding.passwordLayout.error = null
        binding.hostnameLayout.error = null
        binding.connectError.visibility = View.GONE
        binding.openConsoleButton.visibility = View.GONE
        binding.connectStatus.visibility = View.GONE
        if (username.isEmpty()) binding.usernameInput.requestFocus() else binding.passwordInput.requestFocus()
    }

    private fun showDestinationPicker(connection: OnboardingConnection) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showOnly(binding.destinationGroup)
        binding.destinationConnectionSummary.text = getString(
            R.string.destination_connected_format,
            connection.endpoint.hostname,
        )
        binding.destinationError.visibility = View.GONE
        binding.retryDestinationListButton.visibility = View.GONE
        selectedOnboardingRoot = null
        binding.confirmDestinationButton.isEnabled = false
        binding.destinationDirectoryDropdown.setText("", false)
        loadTopLevelDirectories()
    }

    private fun loadTopLevelDirectories() {
        val connection = app.onboardingCoordinator.pendingConnection() ?: run {
            showConnect(activeProfile)
            return
        }
        setDestinationBusy(true)
        binding.destinationStatus.setText(R.string.destination_loading)
        selectedOnboardingRoot = null
        binding.confirmDestinationButton.isEnabled = false
        binding.destinationDirectoryLayout.isEnabled = false
        binding.destinationDirectoryDropdown.setText("", false)
        binding.destinationError.visibility = View.GONE
        binding.retryDestinationListButton.visibility = View.GONE
        executor.execute {
            val directories = runCatching {
                app.remoteDirectoryBrowser.listTopLevel(connection)
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed || !binding.destinationGroup.isVisible) return@runOnUiThread
                setDestinationBusy(false)
                if (directories == null) {
                    binding.destinationStatus.setText(R.string.destination_list_failed)
                    binding.retryDestinationListButton.visibility = View.VISIBLE
                } else {
                    renderTopLevelDirectories(directories)
                }
            }
        }
    }

    private fun renderTopLevelDirectories(directories: List<RemoteDirectory>) {
        binding.destinationStatus.setText(
            if (directories.isEmpty()) R.string.destination_empty else R.string.destination_choose,
        )
        binding.destinationDirectoryLayout.isEnabled = directories.isNotEmpty()
        binding.destinationDirectoryDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, directories.map { it.name }),
        )
        binding.destinationDirectoryDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedOnboardingRoot = directories[position]
            binding.confirmDestinationButton.isEnabled = true
        }
    }

    private fun completeDestinationSelection(path: String) {
        val remoteBasePath = runCatching { RemoteRelativePath.create(path) }.getOrElse {
            showDestinationError(OnboardingErrorCode.INVALID_INPUT)
            return
        }
        setDestinationBusy(true)
        binding.destinationError.visibility = View.GONE
        executor.execute {
            val result = runBlocking {
                app.onboardingCoordinator.selectDestination(remoteBasePath) { progress ->
                    runOnUiThread { if (!isDestroyed) showDestinationProgress(progress) }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setDestinationBusy(false)
                when (result) {
                    is OnboardingResult.Success -> loadExistingProfile(forceConnect = false)
                    is OnboardingResult.Failure -> showDestinationError(result.code)
                    is OnboardingResult.Connected -> Unit
                }
            }
        }
    }

    private fun setDestinationBusy(busy: Boolean) {
        binding.destinationProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.changeDestinationConnectionButton.isEnabled = !busy
        binding.retryDestinationListButton.isEnabled = !busy
        binding.destinationDirectoryLayout.isEnabled = !busy
        binding.confirmDestinationButton.isEnabled = !busy && selectedOnboardingRoot != null
    }

    private fun showDestinationProgress(progress: OnboardingProgress) {
        binding.destinationStatus.setText(
            when (progress) {
                OnboardingProgress.VERIFYING_DESTINATION -> R.string.progress_verifying_destination
                OnboardingProgress.SAVING -> R.string.progress_saving
                else -> R.string.destination_loading
            },
        )
    }

    private fun showDestinationError(code: OnboardingErrorCode) {
        binding.destinationError.visibility = View.VISIBLE
        binding.destinationError.setText(onboardingErrorMessage(code))
    }

    private fun showMappingSetup() {
        val profile = activeProfile ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showOnly(binding.adoptionMappingGroup)
        binding.foldersToolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.previewAdoptionButton.setText(
            if (hasCompletedAdoption) R.string.save_and_check_folders else R.string.check_existing_backup,
        )
        binding.foldersConnectionSummary.text = getString(
            R.string.folder_screen_connection_summary,
            profile.hostname,
            profile.remoteBasePath.trimEnd('/') + "/",
        )
        binding.adoptionError.visibility = View.GONE
        binding.adoptionDiscoveryProgress.visibility = View.GONE
        binding.previewAdoptionButton.isEnabled = true
        pendingLocalFolder = null
        remoteBrowserParent = RemoteRelativePath.create(profile.remoteBasePath)
        selectedRemotePath = null
        binding.mappingDetailsGroup.visibility = View.GONE
        binding.mappingDetailsButton.setIconResource(R.drawable.ic_expand_more)
        updateStorageAccessState()
        renderDraftMappings()
    }

    private fun showSettings() {
        val profile = activeProfile ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showOnly(binding.settingsGroup.root)
        binding.settingsGroup.settingsConnectionSummary.text = getString(
            R.string.settings_connection_summary,
            profile.hostname,
            profile.remoteBasePath.trimEnd('/') + "/",
        )
        binding.settingsGroup.settingsFingerprint.text = runCatching {
            HostKeyPin.parse(requireNotNull(profile.pinnedHostKey)).sha256Fingerprint
        }.getOrElse { getString(R.string.fingerprint_unavailable) }
        binding.settingsGroup.automaticUploadSwitch.isChecked = preferences.getBoolean(AUTOMATIC_UPLOAD_KEY, false)
    }

    private fun openAppBatterySettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
        runCatching { startActivity(intent) }
    }

    private fun updateStorageAccessState() {
        if (!::binding.isInitialized) return
        val granted = Environment.isExternalStorageManager()
        binding.storageAccessPanel.root.visibility = if (granted) View.GONE else View.VISIBLE
        binding.storageAccessPanel.storageAccessStatus.setText(
            if (granted) R.string.storage_access_granted else R.string.storage_access_missing,
        )
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
        showOnly(binding.mappingEditorGroup)
        binding.selectedLocalFolder.text = getString(R.string.selected_local_folder_format, selection.displayName)
        binding.newRemoteFolderInput.setText(selection.displayName)
        binding.mediaFastMode.isChecked = true
        binding.newRemoteFolderGroup.visibility = View.GONE
        binding.newRemoteFolderToggle.setIconResource(R.drawable.ic_expand_more)
        binding.mappingModeHelp.visibility = View.GONE
        binding.mappingModeHelpToggle.setIconResource(R.drawable.ic_expand_more)
        binding.mappingEditorError.visibility = View.GONE
        binding.newRemoteFolderLayout.error = null
        renderSelectedRemotePath()
        loadRemoteDirectories()
    }

    private fun showRestoredMappingEditor(profile: StorageBoxProfileEntity) {
        val selection = restoredPendingLocalFolder ?: run {
            showMappingSetup()
            return
        }
        pendingLocalFolder = selection
        remoteBrowserParent = runCatching {
            RemoteRelativePath.create(restoredRemoteBrowserParent ?: profile.remoteBasePath)
        }.getOrElse { RemoteRelativePath.create(profile.remoteBasePath) }
        selectedRemotePath = restoredSelectedRemotePath
        showOnly(binding.mappingEditorGroup)
        binding.selectedLocalFolder.text = getString(R.string.selected_local_folder_format, selection.displayName)
        binding.newRemoteFolderInput.setText(restoredNewRemoteFolderName ?: selection.displayName)
        binding.allFilesMode.isChecked = restoredAllFilesMode
        binding.mediaFastMode.isChecked = !restoredAllFilesMode
        binding.newRemoteFolderGroup.visibility = View.GONE
        binding.newRemoteFolderToggle.setIconResource(R.drawable.ic_expand_more)
        binding.mappingModeHelp.visibility = View.GONE
        binding.mappingModeHelpToggle.setIconResource(R.drawable.ic_expand_more)
        binding.mappingEditorError.visibility = View.GONE
        binding.newRemoteFolderLayout.error = null
        renderSelectedRemotePath()
        clearRestoredEditorState()
        loadRemoteDirectories()
    }

    private fun clearRestoredEditorState() {
        restoredPendingLocalFolder = null
        restoredRemoteBrowserParent = null
        restoredSelectedRemotePath = null
        restoredNewRemoteFolderName = null
        restoredAllFilesMode = false
    }

    private fun loadRemoteDirectories() {
        val profile = activeProfile ?: return
        val parent = remoteBrowserParent ?: return
        binding.remoteBrowserPath.text = getString(R.string.remote_browser_path_format, parent.value)
        binding.remoteBrowserStatus.visibility = View.VISIBLE
        binding.remoteBrowserStatus.setText(R.string.remote_browser_loading)
        binding.retryRemoteListButton.visibility = View.GONE
        binding.remoteDirectoryLayout.isEnabled = false
        binding.remoteDirectoryDropdown.setText("", false)
        binding.remoteDirectoryDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, emptyList<String>()),
        )
        binding.remoteUpButton.isEnabled = parent.value != profile.remoteBasePath
        executor.execute {
            val directories = runCatching { app.remoteDirectoryBrowser.list(profile, parent) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || remoteBrowserParent?.value != parent.value) return@runOnUiThread
                if (directories == null) {
                    binding.remoteBrowserStatus.setText(R.string.remote_browser_failed)
                    binding.retryRemoteListButton.visibility = View.VISIBLE
                } else {
                    renderRemoteDirectories(directories)
                }
            }
        }
    }

    private fun renderRemoteDirectories(directories: List<RemoteDirectory>) {
        binding.retryRemoteListButton.visibility = View.GONE
        binding.remoteBrowserStatus.visibility = if (directories.isEmpty()) View.VISIBLE else View.GONE
        if (directories.isEmpty()) binding.remoteBrowserStatus.setText(R.string.remote_browser_empty)
        binding.remoteDirectoryLayout.isEnabled = directories.isNotEmpty()
        binding.remoteDirectoryDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, directories.map { it.name }),
        )
        binding.remoteDirectoryDropdown.setOnItemClickListener { _, _, position, _ ->
            val directory = directories[position]
            remoteBrowserParent = RemoteRelativePath.create(directory.relativePath)
            loadRemoteDirectories()
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
        binding.newRemoteFolderLayout.error = null
        val path = runCatching {
            require(name.isNotEmpty() && '/' !in name && name != "." && name != "..")
            RemoteRelativePath.create("${parent.value}/$name").value
        }.getOrElse {
            binding.newRemoteFolderLayout.error = getString(R.string.remote_folder_name_invalid)
            return
        }
        selectRemotePath(path)
    }

    private fun selectRemotePath(path: String) {
        selectedRemotePath = RemoteRelativePath.create(path).value
        binding.mappingEditorError.visibility = View.GONE
        renderSelectedRemotePath()
    }

    private fun renderSelectedRemotePath() {
        val path = selectedRemotePath
        binding.saveMappingButton.isEnabled = path != null
        binding.selectedRemoteFolder.visibility = if (path == null) View.GONE else View.VISIBLE
        binding.selectedRemoteFolder.text = path?.let {
            getString(R.string.selected_remote_folder_format, it)
        }.orEmpty()
    }

    private fun savePendingMapping() {
        val local = pendingLocalFolder ?: return
        val remote = selectedRemotePath ?: run {
            showMappingEditorError(R.string.adoption_error_invalid)
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
            showMappingEditorError(R.string.adoption_error_invalid)
            return
        }
        draftMappings += input
        pendingLocalFolder = null
        selectedRemotePath = null
        binding.adoptionError.visibility = View.GONE
        showMappingSetup()
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
        binding.configuredMappingActions.removeAllViews()
        binding.configuredMappings.text = if (draftMappings.isEmpty()) {
            getString(R.string.no_folders_configured)
        } else {
            getString(R.string.configured_folders_title, draftMappings.size)
        }
        draftMappings.toList().forEach { mapping ->
            binding.configuredMappingActions.addView(mappingCard(mapping))
        }
        binding.clearMappingsButton.visibility = if (draftMappings.isEmpty()) View.GONE else View.VISIBLE
        binding.previewAdoptionButton.isEnabled =
            Environment.isExternalStorageManager() && draftMappings.isNotEmpty()
    }

    private fun mappingCard(mapping: FolderMappingInput): View {
        val card = MaterialCardView(this).apply {
            cardElevation = 0f
            strokeWidth = 1.dp
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 6.dp }
        }
        val row = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dp, 10.dp, 8.dp, 10.dp)
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(TextView(this).apply {
            text = mapping.displayName
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        })
        labels.addView(TextView(this).apply {
            text = getString(
                R.string.configured_mapping_detail,
                mapping.relativeRemotePath,
                getString(
                    if (mapping.mode == MappingModeValue.MEDIA_FAST) {
                        R.string.media_only_fast
                    } else {
                        R.string.all_files_slower
                    },
                ),
            )
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        })
        val remove = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialIconButtonStyle,
        ).apply {
            setIconResource(R.drawable.ic_delete)
            text = ""
            contentDescription = getString(R.string.remove_folder_format, mapping.displayName)
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp)
            setOnClickListener { confirmRemoveMapping(mapping) }
        }
        row.addView(labels)
        row.addView(remove)
        card.addView(row)
        return card
    }

    private fun confirmRemoveMapping(mapping: FolderMappingInput) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.remove_folder_title, mapping.displayName))
            .setMessage(R.string.remove_folder_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove_folder_action) { _, _ ->
                draftMappings.removeAll { it.id == mapping.id }
                renderDraftMappings()
            }
            .show()
    }

    private fun confirmClearMappings() {
        if (draftMappings.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_folders_title)
            .setMessage(R.string.clear_folders_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove_folder_action) { _, _ ->
                draftMappings.clear()
                binding.mappingDetailsGroup.visibility = View.GONE
                binding.mappingDetailsButton.setIconResource(R.drawable.ic_expand_more)
                renderDraftMappings()
            }
            .show()
    }

    private fun toggleCompactSection(target: View, button: MaterialButton) {
        val expanding = target.visibility != View.VISIBLE
        target.visibility = if (expanding) View.VISIBLE else View.GONE
        button.setIconResource(if (expanding) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun closeMappingEditor() {
        app.remoteDirectoryBrowser.cancel()
        pendingLocalFolder = null
        selectedRemotePath = null
        binding.remoteDirectoryDropdown.dismissDropDown()
        showMappingSetup()
    }

    private fun beginAdoptionPreview() {
        val profile = activeProfile ?: return
        val fromHome = binding.homeGroup.root.isVisible
        previewPurpose = if (hasCompletedAdoption) PreviewPurpose.NORMAL_BACKUP else PreviewPurpose.INITIAL_ADOPTION
        if (!Environment.isExternalStorageManager()) {
            if (fromHome) {
                oneShotHomeMessage = getString(R.string.adoption_error_permission)
                showHome(requireNotNull(latestHomeState).copy(status = HomeBackupStatus.NEEDS_ATTENTION))
            } else {
                showAdoptionError(InitialAdoptionError.STORAGE_PERMISSION_REQUIRED)
            }
            return
        }
        if (fromHome) {
            showHome(
                requireNotNull(latestHomeState).copy(
                    status = HomeBackupStatus.LOOKING_FOR_CHANGES,
                    changedItems = 0L,
                    changedBytes = 0L,
                    progressPercentage = null,
                ),
            )
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
                    is InitialAdoptionResult.Success -> {
                        activePreview = result.value
                        val startAutomatically = previewPurpose == PreviewPurpose.NORMAL_BACKUP &&
                            (result.value.summary.itemsToUpload == 0L || automaticUploadEnabled())
                        if (startAutomatically) {
                            beginConfirmedAdoption()
                        } else {
                            showAdoptionPreview(result.value)
                        }
                    }
                    is InitialAdoptionResult.Failure -> {
                        binding.previewAdoptionButton.isEnabled = true
                        if (fromHome) {
                            oneShotHomeMessage = getString(adoptionErrorMessage(result.error))
                            showHome(requireNotNull(latestHomeState).copy(status = HomeBackupStatus.NEEDS_ATTENTION))
                        } else {
                            showAdoptionError(result.error)
                        }
                    }
                }
            }
        }
    }

    private fun showAdoptionPreview(preview: InitialAdoptionPreview) {
        activePreview = preview
        val normalBackup = previewPurpose == PreviewPurpose.NORMAL_BACKUP
        if (normalBackup) {
            showHome(
                requireNotNull(latestHomeState).copy(
                    status = HomeBackupStatus.NEW_ITEMS_READY,
                    changedItems = preview.summary.itemsToUpload,
                    changedBytes = preview.summary.bytesToUpload,
                    progressPercentage = null,
                ),
            )
            return
        }
        showOnly(binding.adoptionFlow.adoptionPreviewGroup)
        binding.adoptionFlow.adoptionPreviewError.visibility = View.GONE
        binding.adoptionFlow.adoptionPreviewTitle.setText(
            R.string.existing_backup_checked,
        )
        binding.adoptionFlow.adoptionSummary.text = getString(
            R.string.adoption_summary_format,
            UserFacingFormat.itemCount(preview.summary.alreadyBackedUpItems),
            UserFacingFormat.itemCount(preview.summary.itemsToUpload),
            UserFacingFormat.bytes(preview.summary.bytesToUpload),
        )
        binding.adoptionFlow.previewExplanation.setText(R.string.no_backup_started_yet)
        binding.adoptionFlow.previewBackHomeButton.visibility = if (hasCompletedAdoption) View.VISIBLE else View.GONE
        binding.adoptionFlow.startAdoptionButton.isEnabled = true
    }

    private fun beginConfirmedAdoption() {
        val preview = activePreview ?: return
        resetTransferLog(preview.id)
        if (previewPurpose == PreviewPurpose.NORMAL_BACKUP) {
            showHome(
                requireNotNull(latestHomeState).copy(
                    status = HomeBackupStatus.BACKING_UP,
                    progressPercentage = 0,
                ),
            )
        } else {
            showOnly(binding.adoptionFlow.adoptionTransferGroup)
            binding.adoptionFlow.cancelAdoptionButton.isEnabled = true
            binding.adoptionFlow.adoptionTransferProgress.setProgressCompat(0, false)
        }
        executor.execute {
            val result = runBlocking {
                app.initialAdoptionCoordinator.confirm(preview.id) { progress ->
                    runOnUiThread { if (!isDestroyed) showTransferProgress(progress) }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is InitialAdoptionResult.Success -> {
                        activePreview = null
                        oneShotHomeMessage = if (result.value.itemsToUpload == 0L) {
                            getString(R.string.no_new_items_found)
                        } else {
                            getString(
                                R.string.backup_finished_format,
                                UserFacingFormat.itemCount(result.value.itemsToUpload),
                            )
                        }
                        loadExistingProfile(forceConnect = false)
                    }
                    is InitialAdoptionResult.Failure -> {
                        if (previewPurpose == PreviewPurpose.NORMAL_BACKUP) {
                            val status = if (result.error == InitialAdoptionError.CANCELLED) {
                                HomeBackupStatus.PAUSED
                            } else {
                                HomeBackupStatus.NEEDS_ATTENTION
                            }
                            oneShotHomeMessage = getString(adoptionErrorMessage(result.error))
                            showHome(
                                requireNotNull(latestHomeState).copy(
                                    status = status,
                                    progressPercentage = null,
                                ),
                            )
                        } else {
                            showAdoptionPreview(preview)
                            binding.adoptionFlow.adoptionPreviewError.visibility = View.VISIBLE
                            binding.adoptionFlow.adoptionPreviewError.setText(adoptionErrorMessage(result.error))
                        }
                    }
                }
            }
        }
    }

    private fun showTransferProgress(progress: AdoptionTransferProgress) {
        progress.fileName?.let { fileName ->
            activePreview?.id?.let { previewId -> appendTransferLogEntry(previewId, fileName) }
        }
        if (previewPurpose == PreviewPurpose.NORMAL_BACKUP) {
            showHome(
                requireNotNull(latestHomeState).copy(
                    status = HomeBackupStatus.BACKING_UP,
                    progressPercentage = progress.percentage,
                ),
            )
            return
        }
        binding.adoptionFlow.adoptionTransferProgress.setProgressCompat(
            progress.percentage,
            ValueAnimator.areAnimatorsEnabled(),
        )
        binding.adoptionFlow.adoptionTransferStatus.text = getString(
            R.string.adoption_progress_format,
            progress.rootNumber,
            progress.rootCount,
            progress.rootName,
            progress.percentage,
        )
    }

    private fun checkForNewFiles() {
        if (draftMappings.isEmpty()) {
            showMappingSetup()
            return
        }
        val profile = activeProfile ?: return
        showHome(
            requireNotNull(latestHomeState).copy(
                status = HomeBackupStatus.LOOKING_FOR_CHANGES,
                changedItems = 0L,
                changedBytes = 0L,
                progressPercentage = null,
            ),
        )
        executor.execute {
            val result = runBlocking { app.incrementalBackupCoordinator.discover(profile.id) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is BackupDiscoveryResult.Ready -> {
                        selectActivePendingJob(result.pending.job.id)
                        showHome(
                            requireNotNull(latestHomeState).copy(
                                status = HomeBackupStatus.NEW_ITEMS_READY,
                                changedItems = result.pending.job.totalFiles,
                                changedBytes = result.pending.job.totalBytes,
                                progressPercentage = null,
                            ),
                        )
                        if (automaticUploadEnabled()) schedulePendingBackup(result.pending.job.id)
                    }
                    BackupDiscoveryResult.UpToDate -> {
                        oneShotHomeMessage = getString(R.string.no_new_items_found)
                        loadExistingProfile(forceConnect = false)
                    }
                    BackupDiscoveryResult.RequiresReconciliation -> beginAdoptionPreview()
                    BackupDiscoveryResult.Failed -> {
                        oneShotHomeMessage = getString(R.string.adoption_error_preview)
                        showHome(
                            requireNotNull(latestHomeState).copy(
                                status = HomeBackupStatus.NEEDS_ATTENTION,
                                progressPercentage = null,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun handleHomePrimaryAction() {
        when (latestHomeState?.status) {
            HomeBackupStatus.NEW_ITEMS_READY -> {
                activePendingJobId?.let(::schedulePendingBackup) ?: prepareDurablePreviewOrTransfer()
            }
            HomeBackupStatus.PAUSED -> {
                activePendingJobId?.let(::schedulePendingBackup) ?: beginConfirmedAdoption()
            }
            HomeBackupStatus.BACKING_UP -> {
                binding.homeGroup.homeBackupButton.isEnabled = false
                val jobId = activePendingJobId
                if (jobId == null) {
                    app.initialAdoptionCoordinator.cancel()
                } else {
                    executor.execute {
                        runBlocking { app.backupScheduler.pause(jobId) }
                        runOnUiThread { if (!isDestroyed) loadExistingProfile(forceConnect = false) }
                    }
                }
            }
            HomeBackupStatus.LOOKING_FOR_CHANGES -> Unit
            HomeBackupStatus.NEEDS_ATTENTION -> {
                if (latestHomeState?.mappingCount == 0) showMappingSetup() else checkForNewFiles()
            }
            HomeBackupStatus.EVERYTHING_BACKED_UP, null -> checkForNewFiles()
        }
    }

    private fun prepareDurablePreviewOrTransfer() {
        val preview = activePreview ?: return
        binding.homeGroup.homeBackupButton.isEnabled = false
        executor.execute {
            val result = runBlocking { app.initialAdoptionCoordinator.prepareDurableConfirmation(preview.id) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is InitialAdoptionResult.Success -> {
                        activePreview = null
                        selectActivePendingJob(result.value.job.id)
                        schedulePendingBackup(result.value.job.id)
                    }
                    is InitialAdoptionResult.Failure -> {
                        binding.homeGroup.homeBackupButton.isEnabled = true
                        // A checkpoint reset cannot be represented by the incremental durable record.
                        // The already-confirmed reconciliation remains safe in the activity path.
                        beginConfirmedAdoption()
                    }
                }
            }
        }
    }

    private fun schedulePendingBackup(jobId: String) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            jobAwaitingNotificationPermission = jobId
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        executor.execute {
            val pending = runBlocking { app.durableBackupStore.pendingJob(jobId) }
            val scheduled = pending != null && app.backupScheduler.schedule(jobId, pending.job.totalBytes)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (scheduled) {
                    latestHomeState?.let { state ->
                        showHome(state.copy(status = HomeBackupStatus.BACKING_UP, progressPercentage = 0))
                    }
                } else {
                    oneShotHomeMessage = getString(R.string.backup_could_not_start)
                    latestHomeState?.let { state ->
                        showHome(state.copy(status = HomeBackupStatus.PAUSED, progressPercentage = null))
                    }
                }
            }
        }
    }

    private fun automaticUploadEnabled(): Boolean =
        preferences.getBoolean(AUTOMATIC_UPLOAD_KEY, false)

    private fun formatDateTime(epochMillis: Long): String {
        val date = Date(epochMillis)
        val dateText = android.text.format.DateFormat.getMediumDateFormat(this).format(date)
        val timeText = android.text.format.DateFormat.getTimeFormat(this).format(date)
        return getString(R.string.date_time_format, dateText, timeText)
    }

    private fun showAdoptionError(error: InitialAdoptionError) {
        binding.adoptionError.visibility = View.VISIBLE
        binding.adoptionError.setText(adoptionErrorMessage(error))
    }

    private fun showMappingEditorError(message: Int) {
        binding.mappingEditorError.visibility = View.VISIBLE
        binding.mappingEditorError.setText(message)
        binding.mainScroll.post { binding.mainScroll.smoothScrollTo(0, binding.mappingEditorError.top) }
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
                OnboardingProgress.VERIFYING_KEY -> R.string.progress_verifying_key
                OnboardingProgress.VERIFYING_DESTINATION -> R.string.progress_verifying_destination
                OnboardingProgress.SAVING -> R.string.progress_saving
            },
        )
    }

    private fun showConnectionError(code: OnboardingErrorCode) {
        binding.connectStatus.visibility = View.GONE
        binding.connectError.visibility = View.VISIBLE
        binding.connectError.setText(onboardingErrorMessage(code))
        binding.openConsoleButton.visibility = if (
            code == OnboardingErrorCode.NETWORK_UNAVAILABLE ||
            code == OnboardingErrorCode.HOST_KEY_CHANGED ||
            code == OnboardingErrorCode.KEY_INSTALL_FAILED
        ) View.VISIBLE else View.GONE
    }

    private fun onboardingErrorMessage(code: OnboardingErrorCode): Int =
        when (code) {
                OnboardingErrorCode.INVALID_INPUT -> R.string.error_invalid_input
                OnboardingErrorCode.NETWORK_UNAVAILABLE -> R.string.error_network
                OnboardingErrorCode.AUTHENTICATION_FAILED -> R.string.error_authentication
                OnboardingErrorCode.HOST_KEY_CHANGED -> R.string.error_host_changed
                OnboardingErrorCode.KEY_INSTALL_FAILED -> R.string.error_key_install
                OnboardingErrorCode.KEY_VERIFICATION_FAILED -> R.string.error_key_verify
                OnboardingErrorCode.DESTINATION_NOT_FOUND -> R.string.error_destination_missing
                OnboardingErrorCode.SECURE_STORAGE_FAILED -> R.string.error_secure_storage
        }

    private fun showOnly(visible: View) {
        val screenChanged = currentScreen !== visible
        listOf(
            binding.welcomeGroup.root,
            binding.homeGroup.root,
            binding.settingsGroup.root,
            binding.connectGroup,
            binding.destinationGroup,
            binding.adoptionMappingGroup,
            binding.mappingEditorGroup,
            binding.adoptionFlow.adoptionPreviewGroup,
            binding.adoptionFlow.adoptionTransferGroup,
        ).forEach { it.visibility = if (it === visible) View.VISIBLE else View.GONE }
        if (screenChanged) {
            currentScreen = visible
            binding.root.post {
                binding.mainScroll.scrollTo(0, 0)
            }
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        val target = when {
            binding.settingsGroup.root.isVisible -> RestoreTarget.SETTINGS
            binding.mappingEditorGroup.isVisible -> RestoreTarget.MAPPING_EDITOR
            binding.adoptionMappingGroup.isVisible -> RestoreTarget.FOLDERS
            else -> null
        }
        target?.let { outState.putString(STATE_SCREEN, it.name) }
        activeProfile?.id?.let { outState.putString(STATE_PROFILE_ID, it) }
        if (target == RestoreTarget.FOLDERS || target == RestoreTarget.MAPPING_EDITOR) {
            outState.putParcelableArrayList(
                STATE_DRAFT_MAPPINGS,
                ArrayList(draftMappings.map(::mappingBundle)),
            )
        }
        if (target == RestoreTarget.MAPPING_EDITOR) {
            pendingLocalFolder?.let { outState.putBundle(STATE_LOCAL_FOLDER, localFolderBundle(it)) }
            remoteBrowserParent?.value?.let { outState.putString(STATE_REMOTE_PARENT, it) }
            selectedRemotePath?.let { outState.putString(STATE_SELECTED_REMOTE, it) }
            outState.putString(STATE_NEW_REMOTE_NAME, binding.newRemoteFolderInput.text?.toString().orEmpty())
            outState.putBoolean(STATE_ALL_FILES_MODE, binding.allFilesMode.isChecked)
        }
        super.onSaveInstanceState(outState)
    }

    private fun restoreTransientUiState(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        restoreTarget = savedInstanceState.getString(STATE_SCREEN)?.let { saved ->
            RestoreTarget.entries.firstOrNull { it.name == saved }
        }
        restoredProfileId = savedInstanceState.getString(STATE_PROFILE_ID)
        restoredDraftMappings = savedInstanceState
            .getParcelableArrayList(STATE_DRAFT_MAPPINGS, Bundle::class.java)
            ?.mapNotNull(::mappingFromBundle)
        restoredPendingLocalFolder = savedInstanceState.getBundle(STATE_LOCAL_FOLDER)?.let(::localFolderFromBundle)
        restoredRemoteBrowserParent = savedInstanceState.getString(STATE_REMOTE_PARENT)
        restoredSelectedRemotePath = savedInstanceState.getString(STATE_SELECTED_REMOTE)
        restoredNewRemoteFolderName = savedInstanceState.getString(STATE_NEW_REMOTE_NAME)
        restoredAllFilesMode = savedInstanceState.getBoolean(STATE_ALL_FILES_MODE)
    }

    private fun mappingBundle(mapping: FolderMappingInput) = Bundle().apply {
        putString(MAPPING_ID, mapping.id)
        putString(MAPPING_DISPLAY_NAME, mapping.displayName)
        putString(MAPPING_TREE_URI, mapping.treeUri)
        putString(MAPPING_LOCAL_PATH, mapping.canonicalLocalPath)
        putString(MAPPING_MEDIA_PREFIX, mapping.relativeMediaStorePrefix)
        putString(MAPPING_REMOTE_PATH, mapping.relativeRemotePath)
        putString(MAPPING_MODE, mapping.mode)
        putBoolean(MAPPING_ENABLED, mapping.enabled)
    }

    private fun mappingFromBundle(bundle: Bundle): FolderMappingInput? = runCatching {
        FolderMappingInput(
            id = requireNotNull(bundle.getString(MAPPING_ID)),
            displayName = requireNotNull(bundle.getString(MAPPING_DISPLAY_NAME)),
            treeUri = requireNotNull(bundle.getString(MAPPING_TREE_URI)),
            canonicalLocalPath = requireNotNull(bundle.getString(MAPPING_LOCAL_PATH)),
            relativeMediaStorePrefix = requireNotNull(bundle.getString(MAPPING_MEDIA_PREFIX)),
            relativeRemotePath = requireNotNull(bundle.getString(MAPPING_REMOTE_PATH)),
            mode = requireNotNull(bundle.getString(MAPPING_MODE)),
            enabled = bundle.getBoolean(MAPPING_ENABLED, true),
        )
    }.getOrNull()

    private fun localFolderBundle(selection: LocalTreeSelection) = Bundle().apply {
        putString(LOCAL_DISPLAY_NAME, selection.displayName)
        putString(LOCAL_TREE_URI, selection.treeUri)
        putString(LOCAL_CANONICAL_PATH, selection.canonicalPath)
        putString(LOCAL_MEDIA_PREFIX, selection.relativeMediaStorePrefix)
    }

    private fun localFolderFromBundle(bundle: Bundle): LocalTreeSelection? = runCatching {
        LocalTreeSelection(
            displayName = requireNotNull(bundle.getString(LOCAL_DISPLAY_NAME)),
            treeUri = requireNotNull(bundle.getString(LOCAL_TREE_URI)),
            canonicalPath = requireNotNull(bundle.getString(LOCAL_CANONICAL_PATH)),
            relativeMediaStorePrefix = requireNotNull(bundle.getString(LOCAL_MEDIA_PREFIX)),
        )
    }.getOrNull()

    override fun onStart() {
        super.onStart()
        BackupProgressEvents.addListener(backupProgressListener)
    }

    override fun onStop() {
        BackupProgressEvents.removeListener(backupProgressListener)
        super.onStop()
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
        val lastSuccessfulBackupAtEpochMillis: Long?,
        val pending: DurablePendingJob?,
        val problem: PendingBackupJobEntity?,
    )

    private enum class PreviewPurpose {
        INITIAL_ADOPTION,
        NORMAL_BACKUP,
    }

    private enum class RestoreTarget {
        SETTINGS,
        FOLDERS,
        MAPPING_EDITOR,
    }

    private companion object {
        const val HETZNER_CONSOLE_URL = "https://console.hetzner.com/"
        const val PRIMARY_VOLUME = "external_primary"
        const val PREFERENCES_NAME = "piffbackup-user-settings"
        const val AUTOMATIC_UPLOAD_KEY = "automatic-upload-after-discovery"
        const val MAX_TRANSFER_LOG_ENTRIES = 6
        const val MAX_TRANSFER_LOG_FILE_NAME_LENGTH = 240
        const val STATE_SCREEN = "ui-screen"
        const val STATE_PROFILE_ID = "ui-profile-id"
        const val STATE_DRAFT_MAPPINGS = "ui-draft-mappings"
        const val STATE_LOCAL_FOLDER = "ui-local-folder"
        const val STATE_REMOTE_PARENT = "ui-remote-parent"
        const val STATE_SELECTED_REMOTE = "ui-selected-remote"
        const val STATE_NEW_REMOTE_NAME = "ui-new-remote-name"
        const val STATE_ALL_FILES_MODE = "ui-all-files-mode"
        const val MAPPING_ID = "mapping-id"
        const val MAPPING_DISPLAY_NAME = "mapping-display-name"
        const val MAPPING_TREE_URI = "mapping-tree-uri"
        const val MAPPING_LOCAL_PATH = "mapping-local-path"
        const val MAPPING_MEDIA_PREFIX = "mapping-media-prefix"
        const val MAPPING_REMOTE_PATH = "mapping-remote-path"
        const val MAPPING_MODE = "mapping-mode"
        const val MAPPING_ENABLED = "mapping-enabled"
        const val LOCAL_DISPLAY_NAME = "local-display-name"
        const val LOCAL_TREE_URI = "local-tree-uri"
        const val LOCAL_CANONICAL_PATH = "local-canonical-path"
        const val LOCAL_MEDIA_PREFIX = "local-media-prefix"
    }
}
