package com.d35p4c1t0.piffbackup.media

import com.d35p4c1t0.piffbackup.backup.BackupMapping
import java.io.File

class MediaStoreMapping private constructor(
    val mapping: BackupMapping,
    private val mediaRootComponents: List<String>,
) {
    fun relativeFilePath(row: MediaStoreRow): RelativeFileListPath? {
        val directoryComponents = safeRelativeComponents(row.relativePath, allowEmpty = true)
            ?: throw IllegalArgumentException("MediaStore row has an unsafe relative path")
        if (mediaRootComponents.size > directoryComponents.size) return null
        if (directoryComponents.take(mediaRootComponents.size) != mediaRootComponents) return null
        val displayName = safeFileName(row.displayName)
            ?: throw IllegalArgumentException("MediaStore row has an unsafe display name")
        val relativeComponents = directoryComponents.drop(mediaRootComponents.size) + displayName
        return RelativeFileListPath.create(relativeComponents.joinToString("/"))
    }

    companion object {
        fun create(mapping: BackupMapping, volumeRoot: File): MediaStoreMapping {
            val canonicalVolume = volumeRoot.canonicalFile
            val localPath = mapping.localRoot.file.toPath()
            require(localPath.startsWith(canonicalVolume.toPath())) {
                "Media mapping must remain inside its MediaStore volume"
            }
            val relative = canonicalVolume.toPath().relativize(localPath).joinToString("/")
            val components = safeRelativeComponents(relative, allowEmpty = true)
                ?: throw IllegalArgumentException("Media mapping has an unsafe relative path")
            return MediaStoreMapping(mapping, components)
        }

        private fun safeFileName(value: String): String? {
            if (value.isEmpty() || value == "." || value == "..") return null
            if ('/' in value || '\u0000' in value) return null
            return value
        }

        private fun safeRelativeComponents(value: String, allowEmpty: Boolean): List<String>? {
            if (value.startsWith('/') || '\u0000' in value) return null
            val normalized = value.trimEnd('/')
            if (normalized.isEmpty()) return if (allowEmpty) emptyList() else null
            val components = normalized.split('/')
            if (components.any { it.isEmpty() || it == "." || it == ".." }) return null
            return components
        }
    }
}

class RelativeFileListPath private constructor(
    val value: String,
) {
    companion object {
        fun create(value: String): RelativeFileListPath {
            require(value.isNotEmpty()) { "File-list path must not be empty" }
            require(!value.startsWith('/')) { "File-list path must be relative" }
            require('\u0000' !in value) { "File-list path must not contain NUL" }
            require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "File-list path contains an unsafe component"
            }
            return RelativeFileListPath(value)
        }
    }
}
