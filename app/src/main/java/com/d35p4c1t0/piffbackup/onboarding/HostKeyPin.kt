package com.d35p4c1t0.piffbackup.onboarding

import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

class HostKeyPin private constructor(
    val algorithm: String,
    val base64Key: String,
) {
    val persistedValue: String = "$algorithm $base64Key"

    val sha256Fingerprint: String by lazy(LazyThreadSafetyMode.NONE) {
        val digest = MessageDigest.getInstance("SHA-256").digest(decodedKey())
        "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
    }

    fun knownHostsLine(hostname: String): String {
        require(StorageBoxEndpoint.isValidHostname(hostname)) { "Invalid known-host hostname" }
        return "$hostname $persistedValue\n"
    }

    fun securelyMatches(other: HostKeyPin): Boolean =
        algorithm == other.algorithm && MessageDigest.isEqual(decodedKey(), other.decodedKey())

    private fun decodedKey(): ByteArray = Base64.getDecoder().decode(base64Key)

    companion object {
        private val ALGORITHM = Regex("[A-Za-z0-9][A-Za-z0-9@._+-]{0,127}")

        fun parse(value: String): HostKeyPin {
            require('\n' !in value && '\r' !in value && '\u0000' !in value) { "Invalid host-key pin" }
            val parts = value.split(' ')
            require(parts.size == 2 && ALGORITHM.matches(parts[0])) { "Invalid host-key pin" }
            val decoded = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull()
            require(decoded != null && decoded.isNotEmpty() && decoded.size <= MAX_KEY_BYTES) {
                "Invalid host-key pin"
            }
            return HostKeyPin(parts[0], parts[1])
        }

        fun fromPublicKey(key: PublicKey): HostKeyPin {
            val type = KeyType.fromKey(key)
            require(type != KeyType.UNKNOWN) { "Unsupported server host-key type" }
            val line = OpenSSHKnownHosts.HostEntry(null, "host.invalid", type, key).line
            return parse(line.split(' ', limit = 3).drop(1).joinToString(" "))
        }

        private const val MAX_KEY_BYTES = 16 * 1024
    }
}

internal class PinningHostKeyVerifier(
    private val expectedHostname: String,
    private val expectedPort: Int,
    expectedPin: HostKeyPin?,
) : HostKeyVerifier {
    private val expected = expectedPin

    @Volatile
    var capturedPin: HostKeyPin? = null
        private set

    @Volatile
    var rejectedChangedKey: Boolean = false
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        if (hostname != expectedHostname || port != expectedPort) return false
        val offered = runCatching { HostKeyPin.fromPublicKey(key) }.getOrNull() ?: return false
        val required = expected ?: capturedPin
        if (required != null && !required.securelyMatches(offered)) {
            rejectedChangedKey = true
            return false
        }
        capturedPin = offered
        return true
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        if (hostname != expectedHostname || port != expectedPort) return emptyList()
        return listOfNotNull(expected?.algorithm ?: capturedPin?.algorithm)
    }
}
