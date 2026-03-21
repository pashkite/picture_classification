package com.codex.ppa.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.codex.ppa.domain.MediaFingerprint
import com.codex.ppa.domain.MediaItem
import com.codex.ppa.domain.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreScanner(
    private val context: Context
) {
    suspend fun scan(permissionSnapshot: MediaPermissionSnapshot): List<MediaItem> {
        return withContext(Dispatchers.IO) {
            buildList {
                if (permissionSnapshot.canReadImages) {
                    addAll(queryImages())
                }
                if (permissionSnapshot.canReadVideos) {
                    addAll(queryVideos())
                }
            }.sortedByDescending { it.dateModifiedEpochSeconds }
        }
    }

    suspend fun getMediaItem(
        mediaType: MediaType,
        mediaStoreId: Long
    ): MediaItem? = withContext(Dispatchers.IO) {
        when (mediaType) {
            MediaType.IMAGE -> queryImages(
                selection = "${MediaStore.Images.Media._ID} = ?",
                selectionArgs = arrayOf(mediaStoreId.toString())
            ).firstOrNull()

            MediaType.VIDEO -> queryVideos(
                selection = "${MediaStore.Video.Media._ID} = ?",
                selectionArgs = arrayOf(mediaStoreId.toString())
            ).firstOrNull()
        }
    }

    private fun queryImages(
        selection: String? = null,
        selectionArgs: Array<String>? = null
    ): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

            buildList {
                while (cursor.moveToNext()) {
                    add(
                        buildMediaItem(
                            mediaType = MediaType.IMAGE,
                            baseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            mediaStoreId = cursor.getLong(idIndex),
                            displayName = cursor.getString(nameIndex).orEmpty(),
                            relativePath = cursor.getString(pathIndex),
                            mimeType = cursor.getString(mimeIndex),
                            sizeBytes = cursor.getLong(sizeIndex),
                            dateModifiedEpochSeconds = cursor.getLong(modifiedIndex),
                            dateAddedEpochSeconds = cursor.getLong(addedIndex),
                            width = cursor.getIntOrNull(widthIndex),
                            height = cursor.getIntOrNull(heightIndex),
                            durationMs = null
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private fun queryVideos(
        selection: String? = null,
        selectionArgs: Array<String>? = null
    ): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION
        )

        return context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            buildList {
                while (cursor.moveToNext()) {
                    add(
                        buildMediaItem(
                            mediaType = MediaType.VIDEO,
                            baseUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            mediaStoreId = cursor.getLong(idIndex),
                            displayName = cursor.getString(nameIndex).orEmpty(),
                            relativePath = cursor.getString(pathIndex),
                            mimeType = cursor.getString(mimeIndex),
                            sizeBytes = cursor.getLong(sizeIndex),
                            dateModifiedEpochSeconds = cursor.getLong(modifiedIndex),
                            dateAddedEpochSeconds = cursor.getLong(addedIndex),
                            width = cursor.getIntOrNull(widthIndex),
                            height = cursor.getIntOrNull(heightIndex),
                            durationMs = cursor.getLong(durationIndex)
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private fun buildMediaItem(
        mediaType: MediaType,
        baseUri: Uri,
        mediaStoreId: Long,
        displayName: String,
        relativePath: String?,
        mimeType: String?,
        sizeBytes: Long,
        dateModifiedEpochSeconds: Long,
        dateAddedEpochSeconds: Long,
        width: Int?,
        height: Int?,
        durationMs: Long?
    ): MediaItem {
        val contentUri = ContentUris.withAppendedId(baseUri, mediaStoreId)
        val fingerprint = MediaFingerprint(
            mediaType = mediaType,
            contentUri = contentUri.toString(),
            mediaStoreId = mediaStoreId,
            displayName = displayName,
            relativePath = relativePath,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            dateModifiedEpochSeconds = dateModifiedEpochSeconds,
            dateAddedEpochSeconds = dateAddedEpochSeconds,
            width = width,
            height = height,
            durationMs = durationMs
        )

        return MediaItem(
            recordId = MediaIdentity.buildRecordId(fingerprint),
            mediaType = mediaType,
            mediaStoreId = mediaStoreId,
            contentUri = contentUri,
            displayName = displayName,
            relativePath = relativePath,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            dateModifiedEpochSeconds = dateModifiedEpochSeconds,
            dateAddedEpochSeconds = dateAddedEpochSeconds,
            width = width,
            height = height,
            durationMs = durationMs
        )
    }
}

private fun android.database.Cursor.getIntOrNull(index: Int): Int? {
    return if (isNull(index)) {
        null
    } else {
        getInt(index)
    }
}
