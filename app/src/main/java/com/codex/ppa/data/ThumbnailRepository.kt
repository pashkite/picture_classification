package com.codex.ppa.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import com.codex.ppa.domain.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbnailRepository(
    private val contentResolver: ContentResolver
) {
    private val cache = object : LruCache<String, Bitmap>(ThumbnailCacheSizeBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount.coerceAtLeast(1)
        }
    }

    fun cached(mediaItem: MediaItem, sizePx: Int): Bitmap? {
        return cache.get(cacheKey(mediaItem.contentUri, sizePx))
    }

    suspend fun load(mediaItem: MediaItem, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = cacheKey(mediaItem.contentUri, sizePx)
        cache.get(key) ?: runCatching {
            contentResolver.loadThumbnail(
                mediaItem.contentUri,
                Size(sizePx, sizePx),
                null
            )
        }.getOrNull()?.also { bitmap ->
            cache.put(key, bitmap)
        }
    }

    private fun cacheKey(contentUri: Uri, sizePx: Int): String = "$contentUri@$sizePx"

    private companion object {
        private const val ThumbnailCacheSizeBytes = 24 * 1024 * 1024
    }
}
