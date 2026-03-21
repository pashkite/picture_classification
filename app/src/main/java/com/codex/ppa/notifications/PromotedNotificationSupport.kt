package com.codex.ppa.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat

object PromotedNotificationSupport {
    const val LogTag = "PpaNowBar"
    const val RequestPromotedOngoingExtraKey = "android.requestPromotedOngoing"

    data class RequestResult(
        val method: String,
        val reflectionApplied: Boolean
    )

    fun requestPromotedOngoing(builder: Notification.Builder): RequestResult {
        if (Build.VERSION.SDK_INT >= 36) {
            val method = runCatching {
                Notification.Builder::class.java.getMethod(
                    "setRequestPromotedOngoing",
                    Boolean::class.javaPrimitiveType
                )
            }.getOrNull()

            if (method != null) {
                val invoked = runCatching {
                    method.invoke(builder, true)
                }.isSuccess
                if (invoked) {
                    return RequestResult(
                        method = "builder#setRequestPromotedOngoing(true)",
                        reflectionApplied = true
                    )
                }
                Log.w(
                    LogTag,
                    "setRequestPromotedOngoing reflection failed. Falling back to extras."
                )
            }
        }

        builder.setExtras(promotedRequestExtras())
        return RequestResult(
            method = "extras[$RequestPromotedOngoingExtraKey]=true",
            reflectionApplied = false
        )
    }

    fun promotedRequestExtras(): Bundle {
        return Bundle().apply {
            putBoolean(RequestPromotedOngoingExtraKey, true)
        }
    }

    fun logRuntimeState(
        context: Context,
        channelId: String,
        reason: String
    ) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel(channelId)
        val notificationsEnabled = notificationManager.areNotificationsEnabled()
        val postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val canPostPromoted = if (Build.VERSION.SDK_INT >= 36) {
            runCatching { notificationManager.canPostPromotedNotifications() }
                .getOrDefault(false)
        } else {
            false
        }

        Log.i(
            LogTag,
            "notification_runtime[$reason]: " +
                "sdk=${Build.VERSION.SDK_INT}, " +
                "postGranted=$postNotificationsGranted, " +
                "notificationsEnabled=$notificationsEnabled, " +
                "appImportance=${notificationManager.importance}, " +
                "canPostPromoted=$canPostPromoted, " +
                "channelId=$channelId, " +
                "channelImportance=${channel?.importance}, " +
                "channelExists=${channel != null}"
        )
    }

    fun logBuiltNotification(
        context: Context,
        channelId: String,
        source: String,
        requestResult: RequestResult,
        notification: Notification,
        processed: Int,
        total: Int,
        movedCount: Int
    ) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel(channelId)
        val hasPromotableCharacteristics = if (Build.VERSION.SDK_INT >= 36) {
            runCatching { notification.hasPromotableCharacteristics() }
                .getOrDefault(false)
        } else {
            false
        }

        Log.i(
            LogTag,
            "notification_build[$source]: " +
                "requestMethod=${requestResult.method}, " +
                "reflectionApplied=${requestResult.reflectionApplied}, " +
                "notificationsEnabled=${notificationManager.areNotificationsEnabled()}, " +
                "appImportance=${notificationManager.importance}, " +
                "canPostPromoted=${if (Build.VERSION.SDK_INT >= 36) notificationManager.canPostPromotedNotifications() else false}, " +
                "channelImportance=${channel?.importance}, " +
                "hasPromotable=$hasPromotableCharacteristics, " +
                "requestExtra=${notification.extras?.getBoolean(RequestPromotedOngoingExtraKey) == true}, " +
                "ongoing=${notification.flags and Notification.FLAG_ONGOING_EVENT != 0}, " +
                "onlyAlertOnce=${notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0}, " +
                "groupSummary=${notification.flags and Notification.FLAG_GROUP_SUMMARY != 0}, " +
                "deleteIntent=${notification.deleteIntent != null}, " +
                "customViews=${notification.contentView != null || notification.bigContentView != null || notification.headsUpContentView != null}, " +
                "title=${notification.extras?.getCharSequence(Notification.EXTRA_TITLE)}, " +
                "progress=${notification.extras?.getInt(Notification.EXTRA_PROGRESS)}, " +
                "progressMax=${notification.extras?.getInt(Notification.EXTRA_PROGRESS_MAX)}, " +
                "processed=$processed, total=$total, moved=$movedCount"
        )
    }
}
