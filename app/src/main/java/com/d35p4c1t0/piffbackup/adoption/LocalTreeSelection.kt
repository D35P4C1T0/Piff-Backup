package com.d35p4c1t0.piffbackup.adoption

import android.net.Uri
import android.provider.DocumentsContract
import com.d35p4c1t0.piffbackup.backup.CanonicalLocalRoot
import java.io.File

data class LocalTreeSelection(
    val displayName: String,
    val treeUri: String,
    val canonicalPath: String,
    val relativeMediaStorePrefix: String,
)

class PrimaryTreeSelectionResolver(
    sharedStorageRoot: File,
) {
    private val sharedRoot = sharedStorageRoot.canonicalFile

    fun resolve(uri: Uri): LocalTreeSelection {
        require(uri.scheme == "content" && uri.authority == EXTERNAL_STORAGE_AUTHORITY) {
            "Only the system's primary storage picker is supported"
        }
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val separator = documentId.indexOf(':')
        require(separator > 0 && documentId.substring(0, separator).equals(PRIMARY_VOLUME, ignoreCase = true)) {
            "Only primary shared storage is supported"
        }
        val relative = documentId.substring(separator + 1).trimEnd('/')
        require(relative.isNotEmpty()) { "The whole shared-storage root cannot be selected" }
        val components = relative.split('/')
        require(components.none { component ->
            component.isEmpty() || component == "." || component == ".." ||
                component.any { it == '\u0000' || it == '\r' || it == '\n' }
        }) { "The selected folder has an unsafe path" }
        require(!isRestrictedAndroidDirectory(components)) {
            "Android app-private folders cannot be selected"
        }
        val root = CanonicalLocalRoot.create(File(sharedRoot, relative).path, sharedRoot).file
        require(root.isDirectory && root.canRead()) { "The selected folder is unavailable" }
        return LocalTreeSelection(
            displayName = components.last(),
            treeUri = uri.toString(),
            canonicalPath = root.path,
            relativeMediaStorePrefix = "$relative/",
        )
    }

    private fun isRestrictedAndroidDirectory(components: List<String>): Boolean =
        components.size >= 2 &&
            components[0].equals("Android", ignoreCase = true) &&
            (components[1].equals("data", ignoreCase = true) ||
                components[1].equals("obb", ignoreCase = true))

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val PRIMARY_VOLUME = "primary"
    }
}
