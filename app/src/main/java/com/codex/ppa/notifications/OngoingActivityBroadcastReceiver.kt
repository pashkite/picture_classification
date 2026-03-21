package com.codex.ppa.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.codex.ppa.worker.AutoClassificationWorker

class OngoingActivityBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ActionSettingChanged -> {
                Log.i(
                    PromotedNotificationSupport.LogTag,
                    "ongoing_activity_setting_changed: package=${context.packageName}"
                )
                PromotedNotificationSupport.logRuntimeState(
                    context = context,
                    channelId = AutoClassificationWorker.NOTIFICATION_CHANNEL_ID,
                    reason = "ongoing_activity_setting_changed"
                )
            }

            ActionDelete -> {
                Log.i(
                    PromotedNotificationSupport.LogTag,
                    "ongoing_activity_delete: user dismissed notification surface"
                )
            }
        }
    }

    companion object {
        const val ActionSettingChanged = "com.samsung.intent.action.ONGOING_ACTIVITY_SETTING_CHANGED"
        const val ActionDelete = "com.codex.ppa.intent.action.ONGOING_ACTIVITY_DELETE"
    }
}
