package com.d35p4c1t0.piffbackup.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedCredentialVault(context: Context) {
    private val root = File(context.noBackupFilesDir, "credentials").canonicalFile
    private val encryptedRoot = File(root, "encrypted")
    private val temporaryRoot = File(root, "temporary")

    init {
        require(encryptedRoot.mkdirs() || encryptedRoot.isDirectory) { "Encrypted credential storage unavailable" }
        require(temporaryRoot.mkdirs() || temporaryRoot.isDirectory) { "Temporary credential storage unavailable" }
        Os.chmod(root.path, OWNER_DIRECTORY_MODE)
        Os.chmod(encryptedRoot.path, OWNER_DIRECTORY_MODE)
        Os.chmod(temporaryRoot.path, OWNER_DIRECTORY_MODE)
    }

    fun referenceFor(profileId: String): String {
        require(SAFE_ID.matches(profileId)) { "Invalid profile ID" }
        return "$REFERENCE_PREFIX$profileId"
    }

    fun contains(reference: String): Boolean = encryptedFile(reference).isFile

    fun store(reference: String, plaintextFile: File) {
        require(plaintextFile.isFile && plaintextFile.length() in 1..MAX_PLAINTEXT_BYTES) {
            "Invalid private key file"
        }
        val plaintext = plaintextFile.readBytes()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(reference.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext)
            val encoded = MAGIC + cipher.iv + ciphertext
            try {
                writeAtomically(encryptedFile(reference), encoded)
            } finally {
                ciphertext.fill(0)
                encoded.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun <T> withDecryptedKey(reference: String, block: (File) -> T): T {
        val encrypted = encryptedFile(reference)
        require(encrypted.isFile && encrypted.length() in MIN_ENCRYPTED_BYTES..MAX_ENCRYPTED_BYTES) {
            "Encrypted credential is missing or invalid"
        }
        val encoded = encrypted.readBytes()
        var plaintext: ByteArray? = null
        val temporary = File(temporaryRoot, "key-${UUID.randomUUID()}.tmp").canonicalFile
        require(temporary.parentFile == temporaryRoot) { "Temporary credential escaped storage" }
        try {
            require(encoded.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Unknown credential format" }
            val iv = encoded.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
            val ciphertext = encoded.copyOfRange(MAGIC.size + IV_BYTES, encoded.size)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, requireKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(reference.toByteArray(Charsets.UTF_8))
                plaintext = cipher.doFinal(ciphertext)
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
            val decrypted = requireNotNull(plaintext)
            require(decrypted.isNotEmpty() && decrypted.size <= MAX_PLAINTEXT_BYTES) {
                "Decrypted credential is invalid"
            }
            FileOutputStream(temporary).use { output ->
                output.write(decrypted)
                output.fd.sync()
            }
            Os.chmod(temporary.path, OWNER_FILE_MODE)
            return block(temporary)
        } finally {
            plaintext?.fill(0)
            encoded.fill(0)
            if (temporary.exists()) temporary.delete()
        }
    }

    fun cleanupAbandonedTemporaryKeys(): Int {
        var deleted = 0
        temporaryRoot.listFiles().orEmpty().forEach { candidate ->
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (
                canonical.parentFile == temporaryRoot &&
                canonical.name.startsWith("key-") &&
                canonical.name.endsWith(".tmp") &&
                canonical.isFile &&
                canonical.delete()
            ) {
                deleted++
            }
        }
        return deleted
    }

    private fun encryptedFile(reference: String): File {
        require(reference.startsWith(REFERENCE_PREFIX)) { "Invalid credential reference" }
        val profileId = reference.removePrefix(REFERENCE_PREFIX)
        require(SAFE_ID.matches(profileId)) { "Invalid credential reference" }
        return File(encryptedRoot, "$profileId.key.enc").canonicalFile.also {
            require(it.parentFile == encryptedRoot) { "Credential reference escaped storage" }
        }
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val temporary = File(encryptedRoot, ".${destination.name}.${UUID.randomUUID()}.tmp").canonicalFile
        require(temporary.parentFile == encryptedRoot) { "Encrypted temporary file escaped storage" }
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Os.chmod(temporary.path, OWNER_FILE_MODE)
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Os.chmod(destination.path, OWNER_FILE_MODE)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun getOrCreateKey(): SecretKey = requireKeyOrNull() ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    private fun requireKey(): SecretKey =
        requireKeyOrNull() ?: throw IllegalStateException("Credential key is missing")

    private fun requireKeyOrNull(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private companion object {
        const val REFERENCE_PREFIX = "keystore:v1:"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "piffbackup.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val OWNER_DIRECTORY_MODE = 448 // 0700
        const val OWNER_FILE_MODE = 384 // 0600
        const val MAX_PLAINTEXT_BYTES = 64 * 1024L
        const val MIN_ENCRYPTED_BYTES = 4L + IV_BYTES + 16L
        const val MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + 4L + IV_BYTES + 16L
        val MAGIC = byteArrayOf('P'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 1)
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
