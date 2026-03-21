package com.codex.ppa.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class MediaPermissionSnapshot(
    val canReadImages: Boolean,
    val canReadVideos: Boolean
) {
    val hasAnyAccess: Boolean
        get() = canReadImages || canReadVideos

    val hasFullAccess: Boolean
        get() = canReadImages && canReadVideos
}

object MediaPermissions {
    fun currentSnapshot(context: Context): MediaPermissionSnapshot {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            MediaPermissionSnapshot(
                canReadImages = hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES),
                canReadVideos = hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
            )
        } else {
            val legacyGranted = hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            MediaPermissionSnapshot(
                canReadImages = legacyGranted,
                canReadVideos = legacyGranted
            )
        }
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }
}
