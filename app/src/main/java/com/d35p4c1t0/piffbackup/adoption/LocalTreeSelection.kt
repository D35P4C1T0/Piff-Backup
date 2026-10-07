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
    val volumeName: String = "external_primary",
)

class PrimaryTreeSelectionResolver(
    sharedStorageRoot: File,
    volumes: Map<String, File> = mapOf("primary" to sharedStorageRoot),
    private val volumesProvider: () -> Map<String, File> = { volumes },
) {

    fun resolve(uri: Uri): LocalTreeSelection {
        require(uri.scheme == "content" && uri.authority == EXTERNAL_STORAGE_AUTHORITY) {
            "Only the system storage picker is supported"
        }
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val separator = documentId.indexOf(':')
        require(separator > 0) { "Invalid storage volume" }
        val volumeId = documentId.substring(0, separator)
        val selectedRoot = requireNotNull(volumesProvider().entries.firstOrNull { it.key.equals(volumeId, true) }?.value) {
            "This storage volume is unavailable"
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
        val root = CanonicalLocalRoot.create(File(selectedRoot, relative).path, selectedRoot).file
        require(root.isDirectory && root.canRead()) { "The selected folder is unavailable" }
        return LocalTreeSelection(
            displayName = components.last(),
            treeUri = uri.toString(),
            canonicalPath = root.path,
            relativeMediaStorePrefix = "$relative/",
            volumeName = if (volumeId.equals("primary", true)) "external_primary" else volumeId,
        )
    }

    private fun isRestrictedAndroidDirectory(components: List<String>): Boolean =
        components.size >= 2 &&
            components[0].equals("Android", ignoreCase = true) &&
            (components[1].equals("data", ignoreCase = true) ||
                components[1].equals("obb", ignoreCase = true))

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
