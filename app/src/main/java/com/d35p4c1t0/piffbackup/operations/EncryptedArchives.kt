package com.d35p4c1t0.piffbackup.operations

import com.d35p4c1t0.piffbackup.PiffBackupApp
import com.d35p4c1t0.piffbackup.adoption.InitialFileListPlanner
import com.d35p4c1t0.piffbackup.media.AndroidMediaStoreSource
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Separate portable encrypted snapshots. Normal rsync backup checkpoints are never advanced. */
class EncryptedArchives(private val app: PiffBackupApp) {
    private val reference get() = app.credentialVault.referenceFor("archive-primary")
    private val directory get() = File(app.noBackupFilesDir, "archives").apply { require(mkdirs() || isDirectory) }
    suspend fun cleanupAbandoned() {
        val operations = app.database.dao().operations("primary").associateBy { it.id }
        directory.listFiles().orEmpty().forEach { file ->
            val temporary = (file.name.startsWith(".") && file.extension in setOf("tmp", "zip")) || file.name.startsWith("archive-key-")
            val operation = operations[file.name.removeSuffix(".pba")]
            if (temporary || file.extension == "pba" && (operation == null || operation.status in setOf("SUCCEEDED", "SUPERSEDED") ||
                    operation.status == "FAILED" && System.currentTimeMillis() - (operation.finishedAtEpochMillis ?: 0L) > 7L*24*60*60*1000)) file.delete()
        }
    }

    fun hasKey() = app.credentialVault.contains(reference)
    fun keyForExport(): ByteArray = if (hasKey()) withKey { it.copyOf() }
        else ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    /** Called only after a recovery key is successfully exported or explicitly imported. */
    fun installKey(bytes: ByteArray) {
        require(bytes.size == 32)
        val file = File.createTempFile("archive-key-", ".tmp", directory)
        try { file.writeBytes(bytes); app.credentialVault.store(reference, file) } finally { file.delete() }
    }
    fun <T> withKey(block: (ByteArray) -> T): T = app.credentialVault.withDecryptedKey(reference) { file ->
        val bytes = file.readBytes()
        try { require(bytes.size == 32); block(bytes) } finally { bytes.fill(0) }
    }
    fun staged(id: String): File {
        require(Regex("operation-[A-Za-z0-9-]+").matches(id))
        return File(directory, "$id.pba")
    }
    suspend fun create(id: String): File {
        val target = staged(id)
        if (target.isFile) return target
        val temporary = File(directory, ".$id.tmp")
        val mappings = app.configurationStore.mappings("primary").filter { it.enabled }
        require(mappings.isNotEmpty())
        val media = AndroidMediaStoreSource(app)
        val snapshot = media.snapshot("external_primary")
        val roots = InitialFileListPlanner(media, app.adoptionFileLists,
            android.os.Environment.getExternalStorageDirectory(), app.allowedStorageRoots, app.backupPreferences::selection)
            .plan(snapshot, mappings)
        try {
            roots.forEach { root -> app.allFilesMetadataPlanner.writeSnapshotForFileList(mappings.single { it.id == root.entity.id }, root.file.path) }
            withKey { key ->
                ZipOutputStream(ArchiveCipher.encrypting(temporary.outputStream().buffered(), key)).use { zip ->
                    zip.putNextEntry(ZipEntry("PiffBackup-configuration.json"))
                    zip.write(com.d35p4c1t0.piffbackup.settings.ConfigurationTransfer.encode(
                        requireNotNull(kotlinx.coroutines.runBlocking { app.configurationStore.profile("primary") }), mappings).toByteArray())
                    zip.closeEntry()
                    roots.forEach { root ->
                        FileListSampling.forEach(root.file) { relative ->
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val source = File(root.mapping.localRoot.file, relative)
                            require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS))
                            require(source.canonicalFile.toPath().startsWith(root.mapping.localRoot.file.toPath()))
                            zip.putNextEntry(ZipEntry("${root.entity.id}/$relative"))
                            Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            roots.forEach { root -> require(app.allFilesMetadataPlanner.matchesSnapshot(mappings.single { it.id == root.entity.id }, root.file.path)) { "Source changed while creating archive" } }
            require(media.snapshot("external_primary") == snapshot) { "Media changed while creating archive" }
            Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return target
        } finally {
            temporary.delete()
            roots.forEach { app.durableBackupStore.deleteLocalMetadataSnapshot(it.file.path); it.file.delete() }
        }
    }
    /** SAF is a separate archive source: it never pretends a provider URI is a native rsync path. */
    fun createSaf(id: String, tree: android.net.Uri): File {
        val target = staged(id)
        if (target.isFile) return target
        val temporary = File(directory, ".$id.tmp")
        val resolver = app.contentResolver
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val visited = HashSet<String>()
        var entries = 0L
        try {
            withKey { key -> ZipOutputStream(ArchiveCipher.encrypting(temporary.outputStream().buffered(),key)).use { zip ->
                fun visit(parent: String, prefix: String, depth: Int) {
                    require(depth <= 128 && visited.add(parent)) { "Document provider cycle or excessive depth" }
                    val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
                    val columns = arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified")
                    requireNotNull(resolver.query(children,columns,null,null,null)).use { cursor ->
                        while (cursor.moveToNext()) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            require(++entries <= 1000000) { "Document tree is too large" }
                            val documentId = cursor.getString(0)
                            val name = cursor.getString(1)
                            require(name.isNotEmpty() && '/' !in name && name != "." && name != "..")
                            val relative = com.d35p4c1t0.piffbackup.media.RelativeFileListPath.create(prefix + name).value
                            if (!app.backupPreferences.selection().includes(relative)) continue
                            if (cursor.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                                visit(documentId, "$relative/",depth+1)
                            } else {
                                val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,documentId)
                                val size = if (cursor.isNull(3)) null else cursor.getLong(3)
                                val modified = if (cursor.isNull(4)) null else cursor.getLong(4)
                                zip.putNextEntry(ZipEntry("Documents/$relative"))
                                val copied = requireNotNull(resolver.openInputStream(uri)).use { input ->
                                    val bytes = ByteArray(65536); var count = 0L
                                    while (true) {
                                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                        val read = input.read(bytes); if (read < 0) break
                                        zip.write(bytes,0,read); count += read
                                    }
                                    count
                                }
                                zip.closeEntry()
                                require(size == null || size == copied) { "Document size changed" }
                                requireNotNull(resolver.query(uri,arrayOf("_size","last_modified"),null,null,null)).use { after ->
                                    require(after.moveToFirst())
                                    if (size != null && !after.isNull(0)) require(size == after.getLong(0))
                                    if (modified != null && !after.isNull(1)) require(modified == after.getLong(1))
                                }
                            }
                        }
                    }
                }
                visit(rootId,"",0)
            } }
            Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return target
        } finally { temporary.delete() }
    }

    fun restore(id: String, destination: File) {
        val archive = staged(id)
        val plaintext = File(directory, ".$id.zip")
        try {
            withKey { key -> archive.inputStream().buffered().use { input -> plaintext.outputStream().buffered().use { output ->
                ArchiveCipher.decrypt(input, output, key)
            } } }
            extract(plaintext, destination)
        } finally { plaintext.delete() }
    }
    companion object {
        fun extract(zip: File, destination: File) {
            require(destination.isDirectory && !Files.isSymbolicLink(destination.toPath()))
            val base = destination.canonicalFile.toPath()
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    require(!entry.isDirectory) { "Unexpected archive directory" }
                    val relative = com.d35p4c1t0.piffbackup.media.RelativeFileListPath.create(entry.name).value
                    val target = destination.resolve(relative)
                    require(target.canonicalFile.toPath().startsWith(base)) { "Archive escaped destination" }
                    require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    // Restore retries compare existing bytes; they never replace an existing file.
                    if (target.exists()) {
                        require(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS))
                        target.inputStream().buffered().use { existing ->
                            val bytes = ByteArray(8192)
                            while (true) {
                                val read = input.read(bytes)
                                if (read < 0) break
                                for (index in 0 until read) require(existing.read() == bytes[index].toInt().and(255)) { "Restore destination changed" }
                            }
                            require(existing.read() == -1) { "Restore destination changed" }
                        }
                    } else {
                        val temporary = File.createTempFile(".piffrestore-", ".tmp", target.parentFile)
                        try {
                            temporary.outputStream().use { output ->
                                val bytes = ByteArray(8192)
                                while (true) {
                                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                    val read = input.read(bytes); if (read < 0) break
                                    output.write(bytes, 0, read)
                                }
                            }
                            // No REPLACE_EXISTING: user files remain protected if the directory changes.
                            Files.move(temporary.toPath(), target.toPath())
                        } finally { temporary.delete() }
                    }
                    input.closeEntry()
                }
            }
        }
    }
}
