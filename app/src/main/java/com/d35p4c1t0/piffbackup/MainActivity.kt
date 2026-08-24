package com.d35p4c1t0.piffbackup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.d35p4c1t0.piffbackup.data.StorageBoxProfileEntity
import com.d35p4c1t0.piffbackup.databinding.ActivityMainBinding
import com.d35p4c1t0.piffbackup.onboarding.HostKeyPin
import com.d35p4c1t0.piffbackup.onboarding.OnboardingErrorCode
import com.d35p4c1t0.piffbackup.onboarding.OnboardingProgress
import com.d35p4c1t0.piffbackup.onboarding.OnboardingRequest
import com.d35p4c1t0.piffbackup.onboarding.OnboardingResult
import com.d35p4c1t0.piffbackup.onboarding.StorageBoxEndpoint
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val app: PiffBackupApp get() = application as PiffBackupApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startSetupButton.setOnClickListener { showConnect(null) }
        binding.reconnectButton.setOnClickListener { loadExistingProfile(forceConnect = true) }
        binding.advancedHostnameToggle.setOnCheckedChangeListener { _, checked ->
            binding.hostnameLayout.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.connectButton.setOnClickListener { beginConnection() }
        binding.openConsoleButton.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, HETZNER_CONSOLE_URL.toUri()))
            }
        }
        loadExistingProfile(forceConnect = false)
    }

    private fun loadExistingProfile(forceConnect: Boolean) {
        executor.execute {
            val profile = runBlocking {
                app.configurationStore.profile(OnboardingRequest.DEFAULT_PROFILE_ID)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!forceConnect && profile?.setupCompleted == true) {
                    val pin = runCatching { HostKeyPin.parse(requireNotNull(profile.pinnedHostKey)) }.getOrNull()
                    if (pin != null) showConnected(profile.hostname, profile.remoteBasePath, pin.sha256Fingerprint)
                    else showConnect(profile)
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
            showError(OnboardingErrorCode.INVALID_INPUT)
            return
        }
        val request = runCatching { OnboardingRequest(endpoint = endpoint, password = password) }.getOrElse {
            password.fill('\u0000')
            showError(OnboardingErrorCode.INVALID_INPUT)
            return
        }
        setBusy(true)
        executor.execute {
            val result = runBlocking {
                app.onboardingCoordinator.onboard(request) { progress ->
                    runOnUiThread {
                        if (!isDestroyed) showProgress(progress)
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setBusy(false)
                when (result) {
                    is OnboardingResult.Success -> showConnected(
                        result.endpoint.hostname,
                        result.remoteBasePath,
                        result.hostFingerprint,
                    )
                    is OnboardingResult.Failure -> showError(result.code)
                }
            }
        }
    }

    private fun showWelcome() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding.welcomeGroup.visibility = View.VISIBLE
        binding.connectGroup.visibility = View.GONE
        binding.connectedGroup.visibility = View.GONE
    }

    private fun showConnect(profile: StorageBoxProfileEntity?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding.welcomeGroup.visibility = View.GONE
        binding.connectGroup.visibility = View.VISIBLE
        binding.connectedGroup.visibility = View.GONE
        val username = profile?.username ?: getString(R.string.default_storage_box_username)
        binding.usernameInput.setText(username)
        val derived = "$username.your-storagebox.de"
        val advanced = profile?.hostname?.takeIf { it != derived }
        binding.advancedHostnameToggle.isChecked = advanced != null
        binding.hostnameInput.setText(advanced.orEmpty())
        binding.passwordInput.text?.clear()
        binding.connectError.visibility = View.GONE
        binding.openConsoleButton.visibility = View.GONE
        binding.connectStatus.visibility = View.GONE
        binding.passwordInput.requestFocus()
    }

    private fun showConnected(hostname: String, remoteBase: String, fingerprint: String) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding.welcomeGroup.visibility = View.GONE
        binding.connectGroup.visibility = View.GONE
        binding.connectedGroup.visibility = View.VISIBLE
        binding.connectedSummary.text = getString(
            R.string.connected_summary,
            hostname,
            remoteBase.trimEnd('/') + "/",
        )
        binding.serverFingerprint.text = fingerprint
    }

    private fun setBusy(busy: Boolean) {
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

    private fun showProgress(progress: OnboardingProgress) {
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

    private fun showError(code: OnboardingErrorCode) {
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
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    override fun onDestroy() {
        binding.passwordInput.text?.clear()
        executor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val HETZNER_CONSOLE_URL = "https://console.hetzner.com/"
    }
}
