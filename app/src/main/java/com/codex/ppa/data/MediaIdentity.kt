package com.codex.ppa.data

import com.codex.ppa.domain.ClassificationEntry
import com.codex.ppa.domain.MediaFingerprint
import com.codex.ppa.domain.MediaItem
import com.codex.ppa.domain.toFingerprint
import java.security.MessageDigest

object MediaIdentity {
    fun buildRecordId(mediaItem: MediaItem): String = buildRecordId(mediaItem.toFingerprint())

    fun buildRecordId(fingerprint: MediaFingerprint): String {
        val raw = listOf(
            fingerprint.mediaType.name,
            fingerprint.mediaStoreId.toString(),
            fingerprint.contentUri,
            fingerprint.relativePath.orEmpty(),
            fingerprint.displayName,
            fingerprint.sizeBytes.toString(),
            fingerprint.dateModifiedEpochSeconds.toString()
        ).joinToString(separator = "|")

        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun matches(entry: ClassificationEntry, mediaItem: MediaItem): Boolean {
        val fingerprint = entry.fingerprint
        return entry.recordId == mediaItem.recordId ||
            (fingerprint.mediaType == mediaItem.mediaType &&
                fingerprint.mediaStoreId == mediaItem.mediaStoreId &&
                fingerprint.sizeBytes == mediaItem.sizeBytes) ||
            fingerprint.contentUri == mediaItem.contentUri.toString() ||
            (
                fingerprint.mediaType == mediaItem.mediaType &&
                    fingerprint.relativePath == mediaItem.relativePath &&
                    fingerprint.displayName == mediaItem.displayName &&
                    fingerprint.sizeBytes == mediaItem.sizeBytes &&
                    fingerprint.dateModifiedEpochSeconds == mediaItem.dateModifiedEpochSeconds
                )
    }
}
