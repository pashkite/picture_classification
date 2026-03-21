package com.codex.ppa.data

import com.codex.ppa.domain.ClassificationEntry
import com.codex.ppa.domain.MediaFingerprint
import com.codex.ppa.domain.MediaItem
import com.codex.ppa.domain.MediaType

class ClassificationEntryLookup(
    entries: List<ClassificationEntry>
) {
    private val byRecordId = entries.associateBy(ClassificationEntry::recordId)
    private val byContentUri = entries.associateBy { it.fingerprint.contentUri }
    private val byMediaStoreKey = entries.groupBy { mediaStoreKey(it.fingerprint) }
    private val byPathKey = entries.groupBy { pathKey(it.fingerprint) }

    fun find(mediaItem: MediaItem): ClassificationEntry? {
        return byRecordId[mediaItem.recordId]
            ?: byContentUri[mediaItem.contentUri.toString()]
            ?: byMediaStoreKey[mediaStoreKey(mediaItem)]?.firstOrNull { MediaIdentity.matches(it, mediaItem) }
            ?: byPathKey[pathKey(mediaItem)]?.firstOrNull { MediaIdentity.matches(it, mediaItem) }
    }

    private fun mediaStoreKey(fingerprint: MediaFingerprint): MediaStoreKey {
        return MediaStoreKey(
            mediaType = fingerprint.mediaType,
            mediaStoreId = fingerprint.mediaStoreId,
            sizeBytes = fingerprint.sizeBytes
        )
    }

    private fun mediaStoreKey(mediaItem: MediaItem): MediaStoreKey {
        return MediaStoreKey(
            mediaType = mediaItem.mediaType,
            mediaStoreId = mediaItem.mediaStoreId,
            sizeBytes = mediaItem.sizeBytes
        )
    }

    private fun pathKey(fingerprint: MediaFingerprint): PathKey {
        return PathKey(
            mediaType = fingerprint.mediaType,
            relativePath = fingerprint.relativePath,
            displayName = fingerprint.displayName,
            sizeBytes = fingerprint.sizeBytes,
            dateModifiedEpochSeconds = fingerprint.dateModifiedEpochSeconds
        )
    }

    private fun pathKey(mediaItem: MediaItem): PathKey {
        return PathKey(
            mediaType = mediaItem.mediaType,
            relativePath = mediaItem.relativePath,
            displayName = mediaItem.displayName,
            sizeBytes = mediaItem.sizeBytes,
            dateModifiedEpochSeconds = mediaItem.dateModifiedEpochSeconds
        )
    }
}

private data class MediaStoreKey(
    val mediaType: MediaType,
    val mediaStoreId: Long,
    val sizeBytes: Long
)

private data class PathKey(
    val mediaType: MediaType,
    val relativePath: String?,
    val displayName: String,
    val sizeBytes: Long,
    val dateModifiedEpochSeconds: Long
)
