package com.d35p4c1t0.piffbackup.backup

import java.io.File
import java.io.IOException

class CanonicalLocalRoot private constructor(
    val file: File,
) {
    val pathWithTrailingSlash: String = file.path.trimEnd('/') + "/"

    companion object {
        @Throws(IOException::class)
        fun create(rawPath: String, allowedSharedStorageRoot: File): CanonicalLocalRoot {
            require(rawPath.isNotBlank()) { "Local root must not be blank" }
            require('\u0000' !in rawPath) { "Local root must not contain NUL" }
            val rawCandidate = File(rawPath)
            require(rawCandidate.isAbsolute) { "Local root must be absolute" }
            val allowed = allowedSharedStorageRoot.canonicalFile
            val candidate = rawCandidate.canonicalFile
            require(candidate.toPath().startsWith(allowed.toPath())) {
                "Local root must remain inside shared storage"
            }
            return CanonicalLocalRoot(candidate)
        }
    }
}

class RemoteRelativePath private constructor(
    val value: String,
) {
    val pathWithTrailingSlash: String = "$value/"
    val components: List<String> = value.split('/')

    fun isSameOrAncestorOf(other: RemoteRelativePath): Boolean =
        components.size <= other.components.size &&
            components == other.components.take(components.size)

    companion object {
        fun create(rawPath: String): RemoteRelativePath {
            require(rawPath.isNotBlank()) { "Remote path must not be blank" }
            require(!rawPath.startsWith('/')) { "Remote path must be relative" }
            val normalized = rawPath.trimEnd('/')
            require(normalized.isNotBlank()) { "Remote path must not be root" }
            val components = normalized.split('/')
            require(components.none { it.isEmpty() || it == "." || it == ".." }) {
                "Remote path contains an unsafe component"
            }
            require(components.none { component -> component.any { it == '\u0000' || it == '\r' || it == '\n' } }) {
                "Remote path contains a control character"
            }
            return RemoteRelativePath(normalized)
        }
    }
}

data class BackupMapping(
    val localRoot: CanonicalLocalRoot,
    val remoteRoot: RemoteRelativePath,
)

object BackupMappingValidator {
    fun validate(mappings: List<BackupMapping>, requiredRemoteBase: RemoteRelativePath) {
        mappings.forEach { mapping ->
            require(requiredRemoteBase.isSameOrAncestorOf(mapping.remoteRoot)) {
                "Remote mapping must remain under the configured backup root"
            }
        }

        for (leftIndex in mappings.indices) {
            for (rightIndex in leftIndex + 1 until mappings.size) {
                val left = mappings[leftIndex]
                val right = mappings[rightIndex]
                require(!localRootsOverlap(left.localRoot, right.localRoot)) {
                    "Local folder mappings must not overlap"
                }
                require(!remoteRootsOverlap(left.remoteRoot, right.remoteRoot)) {
                    "Remote folder mappings must not overlap"
                }
            }
        }
    }

    private fun localRootsOverlap(left: CanonicalLocalRoot, right: CanonicalLocalRoot): Boolean {
        val leftPath = left.file.toPath()
        val rightPath = right.file.toPath()
        return leftPath.startsWith(rightPath) || rightPath.startsWith(leftPath)
    }

    private fun remoteRootsOverlap(left: RemoteRelativePath, right: RemoteRelativePath): Boolean =
        left.isSameOrAncestorOf(right) || right.isSameOrAncestorOf(left)
}
