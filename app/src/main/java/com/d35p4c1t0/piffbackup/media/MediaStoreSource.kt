package com.d35p4c1t0.piffbackup.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore

enum class MediaKind {
    IMAGE,
    VIDEO,
}

data class MediaStoreRow(
    val kind: MediaKind,
    val relativePath: String,
    val displayName: String,
    val generationAdded: Long,
    val generationModified: Long,
    val sizeBytes: Long,
) {
    init {
        require(generationAdded >= 0L) { "Added generation must not be negative" }
        require(generationModified >= 0L) { "Modified generation must not be negative" }
        require(sizeBytes >= 0L) { "Media size must not be negative" }
    }

    fun changedWithin(window: MediaGenerationWindow): Boolean =
        generationAdded > window.afterExclusive && generationAdded <= window.throughInclusive ||
            generationModified > window.afterExclusive && generationModified <= window.throughInclusive
}

interface MediaStoreSource {
    fun snapshot(volumeName: String): MediaStoreSnapshot

    fun forEachChangedMedia(
        volumeName: String,
        window: MediaGenerationWindow,
        consumer: (MediaStoreRow) -> Unit,
    )
}

class AndroidMediaStoreSource(
    private val context: Context,
) : MediaStoreSource {
    override fun snapshot(volumeName: String): MediaStoreSnapshot {
        val versionBefore = MediaStore.getVersion(context, volumeName)
        val generation = MediaStore.getGeneration(context, volumeName)
        val versionAfter = MediaStore.getVersion(context, volumeName)
        return MediaStoreSnapshot(
            volumeName = volumeName,
            version = versionAfter,
            generation = generation,
            stable = versionBefore == versionAfter,
            accessScope = mediaAccessScope(),
        )
    }

    override fun forEachChangedMedia(
        volumeName: String,
        window: MediaGenerationWindow,
        consumer: (MediaStoreRow) -> Unit,
    ) {
        if (window.afterExclusive == window.throughInclusive) return
        val projection = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.SIZE,
        )
        val selection = buildString {
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND (")
            append("(${MediaStore.MediaColumns.GENERATION_ADDED} > ? AND ")
            append("${MediaStore.MediaColumns.GENERATION_ADDED} <= ?) OR ")
            append("(${MediaStore.MediaColumns.GENERATION_MODIFIED} > ? AND ")
            append("${MediaStore.MediaColumns.GENERATION_MODIFIED} <= ?))")
        }
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            window.afterExclusive.toString(),
            window.throughInclusive.toString(),
            window.afterExclusive.toString(),
            window.throughInclusive.toString(),
        )
        val cursor = requireNotNull(
            context.contentResolver.query(
                MediaStore.Files.getContentUri(volumeName),
                projection,
                selection,
                selectionArgs,
                null,
            ),
        ) { "MediaStore query returned no cursor" }
        cursor.use {
            val mediaTypeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val relativePathColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val displayNameColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val addedColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_ADDED)
            val modifiedColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (it.moveToNext()) {
                val kind = when (it.getInt(mediaTypeColumn)) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaKind.IMAGE
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaKind.VIDEO
                    else -> continue
                }
                val relativePath = it.getString(relativePathColumn) ?: continue
                val displayName = it.getString(displayNameColumn) ?: continue
                consumer(
                    MediaStoreRow(
                        kind = kind,
                        relativePath = relativePath,
                        displayName = displayName,
                        generationAdded = it.getLong(addedColumn),
                        generationModified = it.getLong(modifiedColumn),
                        sizeBytes = it.getLong(sizeColumn).coerceAtLeast(0L),
                    ),
                )
            }
        }
    }

    private fun mediaAccessScope(): MediaAccessScope {
        fun granted(permission: String): Boolean =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        val fullImages = granted(Manifest.permission.READ_MEDIA_IMAGES)
        val fullVideos = granted(Manifest.permission.READ_MEDIA_VIDEO)
        if (fullImages && fullVideos) return MediaAccessScope.FULL
        val selected = granted(READ_MEDIA_VISUAL_USER_SELECTED)
        return if (fullImages || fullVideos || selected) MediaAccessScope.PARTIAL else MediaAccessScope.NONE
    }

    private companion object {
        // String form keeps the API 33 binary free of an API 34 field lookup.
        const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    }
}
