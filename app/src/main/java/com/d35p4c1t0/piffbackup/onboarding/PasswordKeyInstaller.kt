package com.d35p4c1t0.piffbackup.onboarding

import android.util.Log
import com.d35p4c1t0.piffbackup.BuildConfig
import com.hierynomus.sshj.key.KeyAlgorithms
import com.hierynomus.sshj.transport.kex.DHGroups
import com.hierynomus.sshj.transport.kex.ExtInfoClientFactory
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.kex.DHGexSHA256
import net.schmizz.sshj.userauth.UserAuthException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.NoSuchAlgorithmException
import java.util.concurrent.TimeUnit

interface PasswordKeyInstaller {
    fun install(
        endpoint: StorageBoxEndpoint,
        password: CharArray,
        publicKeyLine: String,
        expectedPin: HostKeyPin?,
        onProgress: (OnboardingProgress) -> Unit,
    ): HostKeyPin
}

class SshjPasswordKeyInstaller : PasswordKeyInstaller {
    override fun install(
        endpoint: StorageBoxEndpoint,
        password: CharArray,
        publicKeyLine: String,
        expectedPin: HostKeyPin?,
        onProgress: (OnboardingProgress) -> Unit,
    ): HostKeyPin {
        validatePublicKey(publicKeyLine)
        val verifier = PinningHostKeyVerifier(endpoint.hostname, endpoint.port, expectedPin)
        var stage = BootstrapStage.CONNECT
        try {
            configureSshjSecurityProviders()
            SSHClient(androidCompatibleSshConfig()).use { client ->
                client.connectTimeout = CONNECT_TIMEOUT_MILLIS
                client.timeout = SOCKET_TIMEOUT_MILLIS
                client.addHostKeyVerifier(verifier)
                onProgress(OnboardingProgress.CONNECTING_WITH_PASSWORD)
                client.connect(endpoint.hostname, endpoint.port)
                stage = BootstrapStage.AUTHENTICATE
                client.authPassword(endpoint.username, password)
                stage = BootstrapStage.INSTALL_KEY
                onProgress(OnboardingProgress.INSTALLING_KEY)
                client.startSession().use { session ->
                    val command = session.exec(INSTALL_COMMAND)
                    command.outputStream.use { output ->
                        output.write(publicKeyLine.toByteArray(StandardCharsets.US_ASCII))
                        output.flush()
                    }
                    command.join(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (command.exitStatus == null) {
                        command.close()
                        throw OnboardingFailure(OnboardingErrorCode.KEY_INSTALL_FAILED)
                    }
                    drainBounded(command.inputStream)
                    drainBounded(command.errorStream)
                    if (command.exitStatus != 0) {
                        throw OnboardingFailure(OnboardingErrorCode.KEY_INSTALL_FAILED)
                    }
                }
            }
            return verifier.capturedPin
                ?: throw OnboardingFailure(OnboardingErrorCode.HOST_KEY_CHANGED)
        } catch (failure: OnboardingFailure) {
            throw failure
        } catch (failure: UserAuthException) {
            logSafeFailure(stage, failure)
            throw OnboardingFailure(OnboardingErrorCode.AUTHENTICATION_FAILED, failure)
        } catch (failure: SocketTimeoutException) {
            logSafeFailure(stage, failure)
            throw OnboardingFailure(OnboardingErrorCode.NETWORK_UNAVAILABLE, failure)
        } catch (failure: ConnectException) {
            logSafeFailure(stage, failure)
            throw OnboardingFailure(OnboardingErrorCode.NETWORK_UNAVAILABLE, failure)
        } catch (failure: Exception) {
            logSafeFailure(stage, failure)
            if (verifier.rejectedChangedKey) {
                throw OnboardingFailure(OnboardingErrorCode.HOST_KEY_CHANGED, failure)
            }
            throw OnboardingFailure(OnboardingErrorCode.NETWORK_UNAVAILABLE, failure)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun validatePublicKey(value: String) {
        require(value.endsWith('\n') && value.length <= MAX_PUBLIC_KEY_CHARS) { "Invalid public key" }
        require(value.lineSequence().filter { it.isNotEmpty() }.count() == 1) { "Invalid public key" }
        require(value.startsWith("ssh-ed25519 ") && '\u0000' !in value) { "Invalid public key" }
    }

    private fun drainBounded(stream: java.io.InputStream) {
        stream.use { input ->
            val buffer = ByteArray(1024)
            var remaining = MAX_DIAGNOSTIC_BYTES
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) return
                remaining -= count
            }
        }
    }

    private fun logSafeFailure(stage: BootstrapStage, failure: Throwable) {
        if (!BuildConfig.DEBUG) return
        val types = generateSequence(failure) { it.cause }
            .take(MAX_REPORTED_CAUSES)
            .joinToString(" -> ") { it.javaClass.name }
        // Deliberately omit exception messages: SSH libraries may include the
        // endpoint or username in them. Passwords are never logged.
        Log.w(LOG_TAG, "Password SSH bootstrap failed at $stage: $types")
        generateSequence(failure) { it.cause }
            .last()
            .stackTrace
            .take(MAX_REPORTED_FRAMES)
            .joinToString(" <- ") { frame ->
                "${frame.className}.${frame.methodName}:${frame.lineNumber}"
            }
            .takeIf { it.isNotEmpty() }
            ?.let { Log.w(LOG_TAG, "Terminal failure frames: $it") }
        generateSequence(failure) { it.cause }
            .filterIsInstance<NoSuchAlgorithmException>()
            .firstOrNull()
            ?.message
            ?.let(::sanitizeJcaMessage)
            ?.let { Log.w(LOG_TAG, "Missing JCA primitive: $it") }
    }

    private fun sanitizeJcaMessage(value: String): String = value
        .take(MAX_JCA_MESSAGE_CHARS)
        .map { character ->
            if (character.isLetterOrDigit() || character in JCA_MESSAGE_PUNCTUATION) character else '?'
        }
        .joinToString("")

    private enum class BootstrapStage {
        CONNECT,
        AUTHENTICATE,
        INSTALL_KEY,
    }

    private companion object {
        const val LOG_TAG = "PiffBackupOnboarding"
        const val INSTALL_COMMAND = "install-ssh-key"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val SOCKET_TIMEOUT_MILLIS = 30_000
        const val COMMAND_TIMEOUT_SECONDS = 30L
        const val MAX_PUBLIC_KEY_CHARS = 2048
        const val MAX_DIAGNOSTIC_BYTES = 8 * 1024
        const val MAX_REPORTED_CAUSES = 6
        const val MAX_REPORTED_FRAMES = 8
        const val MAX_JCA_MESSAGE_CHARS = 160
        val JCA_MESSAGE_PUNCTUATION = setOf(' ', '.', '_', '+', '-', '/', ':', '(', ')')
    }
}

/**
 * Android exposes a platform provider named `BC`. SSHJ otherwise sees that
 * name, mistakes it for the bundled modern Bouncy Castle provider, and then
 * requests X25519 from Android's reduced provider, where it is unavailable.
 * API 33 already supplies X25519 and Ed25519 through its default providers, so
 * prevent SSHJ from pinning its primitives to the colliding `BC` name.
 */
internal fun configureSshjSecurityProviders() {
    SecurityUtils.setSecurityProvider(null)
    SecurityUtils.setRegisterBouncyCastle(false)
}

internal fun androidCompatibleSshConfig() = DefaultConfig().apply {
    // Android 13 advertises X25519 and ECDH KeyPairGenerator implementations
    // that are not compatible with SSHJ 0.40. Use strong SHA-256/512 finite-
    // field exchanges supported by current OpenSSH and Android's DH provider.
    keyExchangeFactories = listOf(
        DHGroups.Group16SHA512(),
        DHGroups.Group18SHA512(),
        DHGroups.Group14SHA256(),
        DHGexSHA256.Factory(),
        ExtInfoClientFactory(),
    )
    // SSHJ 0.40's legacy fingerprint path cannot serialize Android's Ed25519
    // public-key implementation before our SHA-256 pin verifier is invoked.
    // Hetzner also offers an RSA host key; negotiate it only with RSA-SHA2.
    keyAlgorithms = listOf(
        KeyAlgorithms.RSASHA512(),
        KeyAlgorithms.RSASHA256(),
    )
}
