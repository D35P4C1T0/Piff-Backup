package com.d35p4c1t0.piffbackup.transport

/** Backend capabilities are explicit so future transports cannot silently weaken backup semantics. */
data class TransportCapabilities(val preview: Boolean, val resume: Boolean, val verification: Boolean,
    val versionPreservation: Boolean, val restore: Boolean)

interface BackupTargetProvider {
    val id: String
    val defaultPort: Int
    val capabilities: TransportCapabilities
    val installKeyCommand: String
}

enum class RsyncTargetProvider(override val defaultPort: Int) : BackupTargetProvider {
    HETZNER(23), SSH_RSYNC(22);
    override val id get() = name
    override val capabilities get() = TransportCapabilities(true, true, true, true, true)
    override val installKeyCommand: String get() = when (this) {
        HETZNER -> "install-ssh-key"
        SSH_RSYNC -> "umask 077; mkdir -p .ssh && touch .ssh/authorized_keys && " +
            "chmod 700 .ssh && chmod 600 .ssh/authorized_keys && " +
            "key=\$(cat) && { grep -qxF \"\$key\" .ssh/authorized_keys || printf '%s\\n' \"\$key\" >> .ssh/authorized_keys; }"
    }
}
