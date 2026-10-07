package com.d35p4c1t0.piffbackup.operations

import java.io.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independently authenticated 64 KiB frames, including an authenticated end marker.
 * A random 256-bit salt derives a fresh key for each archive; the 32-bit frame counter must never wrap.
 * Format is versioned and portable: header PBA1 + salt, then big-endian length + GCM frame.
 */
object ArchiveCipher {
    private val magic = byteArrayOf(80, 66, 65, 49)
    private const val FRAME = 65536
    fun encrypting(output: OutputStream, key: ByteArray): OutputStream {
        require(key.size == 32)
        val prefix = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val header = magic + prefix
        val target = DataOutputStream(output)
        target.write(header)
        return object : OutputStream() {
            private val buffer = ByteArray(FRAME)
            private var used = 0
            private var index = 0L
            private var closed = false
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                check(!closed)
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                var start = offset; var remaining = length
                while (remaining > 0) {
                    val take = minOf(FRAME - used, remaining)
                    bytes.copyInto(buffer, used, start, start + take)
                    used += take; start += take; remaining -= take
                    if (used == FRAME) frame()
                }
            }
            private fun frame() {
                require(index < 0xFFFFFFFFL) { "Archive is too large" }
                val cipher = cipher(Cipher.ENCRYPT_MODE, key, header, index, used)
                target.writeInt(used)
                target.write(cipher.doFinal(buffer, 0, used))
                index++; used = 0
            }
            override fun flush() { target.flush() }
            override fun close() {
                if (closed) return
                try { if (used > 0) frame(); frame(); target.flush() }
                finally { closed = true; buffer.fill(0); target.close() }
            }
        }
    }
    /** Caller must discard the private output on any error and publish only after this succeeds. */
    fun decrypt(input: InputStream, output: OutputStream, key: ByteArray) {
        require(key.size == 32)
        val source = DataInputStream(input)
        val header = ByteArray(36).also { source.readFully(it) }
        require(header.copyOfRange(0, 4).contentEquals(magic)) { "Unknown archive format" }
        var index = 0L
        while (true) {
            require(index < 0xFFFFFFFFL) { "Archive is too large" }
            val length = source.readInt()
            require(length in 0..FRAME) { "Invalid archive frame" }
            val encrypted = ByteArray(length + 16).also { source.readFully(it) }
            val plaintext = cipher(Cipher.DECRYPT_MODE, key, header, index, length).doFinal(encrypted)
            try { if (length == 0) { require(source.read() == -1) { "Trailing archive data" }; return }
                output.write(plaintext)
            } finally { plaintext.fill(0) }
            index++
        }
    }
    private fun cipher(mode: Int, key: ByteArray, header: ByteArray, index: Long, length: Int): Cipher {
        val suffix = java.nio.ByteBuffer.allocate(4).putInt(index.toInt()).array()
        val nonce = ByteArray(8) + suffix
        val derived = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal("PiffBackup archive key v1".toByteArray(Charsets.US_ASCII) + header)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, nonce))
            derived.fill(0)
            updateAAD(header + suffix + java.nio.ByteBuffer.allocate(4).putInt(length).array())
        }
    }
}
