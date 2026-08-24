package com.d35p4c1t0.piffbackup.onboarding

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.UserAuthException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
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
        try {
            SSHClient().use { client ->
                client.connectTimeout = CONNECT_TIMEOUT_MILLIS
                client.timeout = SOCKET_TIMEOUT_MILLIS
                client.addHostKeyVerifier(verifier)
                onProgress(OnboardingProgress.CONNECTING_WITH_PASSWORD)
                client.connect(endpoint.hostname, endpoint.port)
                client.authPassword(endpoint.username, password)
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
            throw OnboardingFailure(OnboardingErrorCode.AUTHENTICATION_FAILED, failure)
        } catch (failure: SocketTimeoutException) {
            throw OnboardingFailure(OnboardingErrorCode.NETWORK_UNAVAILABLE, failure)
        } catch (failure: ConnectException) {
            throw OnboardingFailure(OnboardingErrorCode.NETWORK_UNAVAILABLE, failure)
        } catch (failure: Exception) {
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

    private companion object {
        const val INSTALL_COMMAND = "install-ssh-key"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val SOCKET_TIMEOUT_MILLIS = 30_000
        const val COMMAND_TIMEOUT_SECONDS = 30L
        const val MAX_PUBLIC_KEY_CHARS = 2048
        const val MAX_DIAGNOSTIC_BYTES = 8 * 1024
    }
}
