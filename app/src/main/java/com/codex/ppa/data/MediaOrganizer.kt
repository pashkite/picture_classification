package com.codex.ppa.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.codex.ppa.domain.ClassificationLabels
import com.codex.ppa.domain.MediaItem
import com.codex.ppa.domain.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaOrganizer(
    private val context: Context,
    private val mediaStoreScanner: MediaStoreScanner
) {
    fun targetRelativePath(mediaItem: MediaItem, labels: ClassificationLabels): String? {
        return ClassificationPathPolicy.buildRelativePath(mediaItem.mediaType, labels)
    }

    fun needsMove(mediaItem: MediaItem, labels: ClassificationLabels): Boolean {
        val targetRelativePath = targetRelativePath(mediaItem, labels) ?: return false
        return normalizeRelativePath(mediaItem.relativePath) != targetRelativePath
    }

    suspend fun moveToClassification(
        mediaItem: MediaItem,
        labels: ClassificationLabels
    ): MediaMoveResult = withContext(Dispatchers.IO) {
        val targetRelativePath = targetRelativePath(mediaItem, labels)
            ?: return@withContext MediaMoveResult(
                mediaItem = mediaItem,
                moved = false,
                targetRelativePath = null
            )

        if (normalizeRelativePath(mediaItem.relativePath) == targetRelativePath) {
            return@withContext MediaMoveResult(
                mediaItem = mediaItem,
                moved = false,
                targetRelativePath = targetRelativePath
            )
        }

        val updatedRows = context.contentResolver.update(
            mediaItem.contentUri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelativePath)
            },
            null,
            null
        )

        require(updatedRows > 0) { "파일 이동을 적용하지 못했습니다: ${mediaItem.displayName}" }

        val refreshedItem = mediaStoreScanner.getMediaItem(
            mediaType = mediaItem.mediaType,
            mediaStoreId = mediaItem.mediaStoreId
        ) ?: mediaItem.copy(relativePath = targetRelativePath)

        MediaMoveResult(
            mediaItem = refreshedItem,
            moved = true,
            targetRelativePath = targetRelativePath
        )
    }
}

data class MediaMoveResult(
    val mediaItem: MediaItem,
    val moved: Boolean,
    val targetRelativePath: String?
)

internal object ClassificationPathPolicy {
    private const val AppFolderName = "PersonalMediaSorter"
    private const val PicturesRoot = "Pictures"
    private const val MoviesRoot = "Movies"

    fun buildRelativePath(mediaType: MediaType, labels: ClassificationLabels): String? {
        val normalized = labels.normalized()
        if (normalized.isEmpty) {
            return null
        }

        val baseDirectory = when (mediaType) {
            MediaType.IMAGE -> PicturesRoot
            MediaType.VIDEO -> MoviesRoot
        }

        val segments = buildList {
            add(baseDirectory)
            add(AppFolderName)
            sanitizeSegment(normalized.level1)?.let(::add)
            sanitizeSegment(normalized.level2)?.let(::add)
            sanitizeSegment(normalized.level3)?.let(::add)
        }

        return segments.joinToString(separator = "/", postfix = "/")
    }

    private fun sanitizeSegment(raw: String): String? {
        val sanitized = raw
            .trim()
            .replace('/', '_')
            .replace('\\', '_')
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(48)

        return sanitized.takeIf { it.isNotBlank() }
    }
}

private fun normalizeRelativePath(value: String?): String? {
    return value
        ?.trim()
        ?.trim('/')
        ?.takeIf { it.isNotBlank() }
        ?.let { "$it/" }
}
