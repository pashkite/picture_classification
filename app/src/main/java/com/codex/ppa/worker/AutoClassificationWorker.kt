package com.codex.ppa.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.codex.ppa.MainActivity
import com.codex.ppa.PpaApplication
import com.codex.ppa.R
import com.codex.ppa.data.ClassificationWrite
import com.codex.ppa.domain.ClassificationEngineInfo
import com.codex.ppa.domain.ClassificationSource
import com.codex.ppa.notifications.OngoingActivityBroadcastReceiver
import com.codex.ppa.notifications.PromotedNotificationSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

class AutoClassificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val appContainer = (appContext.applicationContext as PpaApplication).appContainer
    private val workManager = WorkManager.getInstance(appContext)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)
            ?: return Result.failure(
                workDataOf(
                    OUTPUT_SUMMARY to "자동분류 작업 ID가 없습니다.",
                    OUTPUT_COMPLETED_AT to System.currentTimeMillis()
                )
            )

        val job = runCatching {
            appContainer.autoClassificationJobStore.load(jobId)
        }.getOrElse { throwable ->
            return Result.failure(
                workDataOf(
                    OUTPUT_SUMMARY to (throwable.message ?: "자동분류 작업 정보를 읽지 못했습니다."),
                    OUTPUT_COMPLETED_AT to System.currentTimeMillis()
                )
            )
        }

        val total = job.mediaItems.size
        var appliedCount = 0
        var movedCount = 0
        var skippedCount = 0
        var failedCount = 0
        var lastPublishedProcessed = 0
        var lastPublishedMovedCount = 0
        var lastPublishedAtEpochMs = 0L
        val startedAtEpochMs = System.currentTimeMillis()
        val pendingWrites = mutableListOf<ClassificationWrite>()
        suspend fun flushPendingWrites() {
            if (pendingWrites.isNotEmpty()) {
                appContainer.classificationRepository.upsertAll(pendingWrites.toList())
                pendingWrites.clear()
            }
        }
        suspend fun maybePublishProgress(processed: Int) {
            val now = System.currentTimeMillis()
            val shouldPublishProgress =
                processed == total ||
                    processed == 1 ||
                    movedCount != lastPublishedMovedCount ||
                    processed - lastPublishedProcessed >= ProgressUpdateBatchSize ||
                    now - lastPublishedAtEpochMs >= ProgressUpdateMinIntervalMs

            if (shouldPublishProgress) {
                updateProgressNotification(
                    modeLabel = job.modeLabel,
                    processed = processed,
                    total = total,
                    movedCount = movedCount,
                    startedAtEpochMs = startedAtEpochMs,
                    updatedAtEpochMs = now
                )
                lastPublishedProcessed = processed
                lastPublishedMovedCount = movedCount
                lastPublishedAtEpochMs = now
            }
        }

        try {
            appContainer.classificationRepository.load()
            updateProgressNotification(
                modeLabel = job.modeLabel,
                processed = 0,
                total = total,
                movedCount = 0,
                startedAtEpochMs = startedAtEpochMs,
                updatedAtEpochMs = startedAtEpochMs
            )

            val engineInfo = ClassificationEngineInfo(
                engineId = appContainer.classificationEngine.engineId,
                engineVersion = appContainer.classificationEngine.engineVersion,
                engineDisplayName = appContainer.classificationEngine.displayName
            )

            job.mediaItems.forEachIndexed { index, queuedMedia ->
                currentCoroutineContext().ensureActive()
                val mediaItem = queuedMedia.toMediaItem()

                if (job.onlyUnclassified) {
                    val existing = appContainer.classificationRepository.findClassification(mediaItem)
                    if (existing?.classification?.isEmpty == false) {
                        skippedCount += 1
                        maybePublishProgress(index + 1)
                        return@forEachIndexed
                    }
                }

                val suggestion = runCatching {
                    appContainer.classificationEngine.classify(mediaItem)
                }.getOrElse {
                    failedCount += 1
                    maybePublishProgress(index + 1)
                    null
                }

                val suggestedLabels = suggestion?.labels?.normalized()

                if (suggestedLabels == null || suggestedLabels.isEmpty) {
                    skippedCount += 1
                    maybePublishProgress(index + 1)
                    return@forEachIndexed
                }

                runCatching {
                    pendingWrites += ClassificationWrite(
                        mediaItem = mediaItem,
                        labels = suggestedLabels,
                        source = ClassificationSource.MODEL_SUGGESTION,
                        engineInfo = suggestion.engineInfo ?: engineInfo,
                        debugInfo = suggestion.debugInfo
                    )

                    if (job.moveFiles) {
                        val moveResult = appContainer.mediaOrganizer.moveToClassification(
                            mediaItem = mediaItem,
                            labels = suggestedLabels
                        )
                        if (moveResult.moved) {
                            movedCount += 1
                            pendingWrites += ClassificationWrite(
                                mediaItem = moveResult.mediaItem,
                                labels = suggestedLabels,
                                source = ClassificationSource.MODEL_SUGGESTION,
                                engineInfo = suggestion.engineInfo ?: engineInfo,
                                debugInfo = suggestion.debugInfo
                            )
                        }
                    }

                    appliedCount += 1
                }.onFailure {
                    failedCount += 1
                }

                val processed = index + 1
                val shouldPersistBatch = processed % ManifestSaveBatchSize == 0 || processed == total
                if (shouldPersistBatch && pendingWrites.isNotEmpty()) {
                    appContainer.classificationRepository.upsertAll(pendingWrites.toList())
                    pendingWrites.clear()
                }

                maybePublishProgress(processed)
            }

            flushPendingWrites()
            val successSummary = buildResultSummary(
                modeLabel = job.modeLabel,
                appliedCount = appliedCount,
                movedCount = movedCount,
                skippedCount = skippedCount,
                failedCount = failedCount,
                moveFiles = job.moveFiles
            )
            publishTerminalNotification(
                title = "${job.modeLabel} AI 자동분류 완료",
                text = successSummary
            )

            return Result.success(
                workDataOf(
                    OUTPUT_SUMMARY to successSummary,
                    OUTPUT_COMPLETED_AT to System.currentTimeMillis(),
                    OUTPUT_PROCESSED to total,
                    OUTPUT_TOTAL to total,
                    OUTPUT_MODE_LABEL to job.modeLabel,
                    OUTPUT_MOVED to movedCount
                )
            )
        } catch (cancellationException: CancellationException) {
            flushPendingWrites()
            publishTerminalNotification(
                title = "${job.modeLabel} AI 자동분류 중지됨",
                text = buildTerminalStateText(
                    processed = appliedCount + skippedCount + failedCount,
                    total = total,
                    movedCount = movedCount
                )
            )
            throw cancellationException
        } catch (throwable: Throwable) {
            flushPendingWrites()
            val failureSummary = throwable.message ?: "${job.modeLabel} AI 자동분류 중 오류가 발생했습니다."
            publishTerminalNotification(
                title = "${job.modeLabel} AI 자동분류 실패",
                text = failureSummary
            )
            return Result.failure(
                workDataOf(
                    OUTPUT_SUMMARY to failureSummary,
                    OUTPUT_COMPLETED_AT to System.currentTimeMillis(),
                    OUTPUT_PROCESSED to (appliedCount + skippedCount + failedCount),
                    OUTPUT_TOTAL to total,
                    OUTPUT_MODE_LABEL to job.modeLabel,
                    OUTPUT_MOVED to movedCount
                )
            )
        } finally {
            appContainer.autoClassificationJobStore.delete(jobId)
        }
    }

    private suspend fun updateProgressNotification(
        modeLabel: String,
        processed: Int,
        total: Int,
        movedCount: Int,
        startedAtEpochMs: Long,
        updatedAtEpochMs: Long
    ) {
        val percent = if (total > 0) {
            ((processed.toFloat() / total.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val estimatedCompletionEpochMs = estimateCompletionEpochMs(
            startedAtEpochMs = startedAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
            processed = processed,
            total = total
        )

        setProgress(
            workDataOf(
                PROGRESS_PROCESSED to processed,
                PROGRESS_TOTAL to total,
                PROGRESS_MODE_LABEL to modeLabel,
                PROGRESS_MOVED to movedCount,
                PROGRESS_UPDATED_AT to System.currentTimeMillis(),
                PROGRESS_PERCENT to percent
            )
        )
        setForeground(
            createForegroundInfo(
                modeLabel = modeLabel,
                processed = processed,
                total = total,
                movedCount = movedCount,
                percent = percent,
                estimatedCompletionEpochMs = estimatedCompletionEpochMs
            )
        )
    }

    private fun createForegroundInfo(
        modeLabel: String,
        processed: Int,
        total: Int,
        movedCount: Int,
        percent: Int,
        estimatedCompletionEpochMs: Long?
    ): ForegroundInfo {
        ensureNotificationChannel(applicationContext)
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val deleteIntent = PendingIntent.getBroadcast(
            applicationContext,
            0,
            Intent(applicationContext, OngoingActivityBroadcastReceiver::class.java).apply {
                action = OngoingActivityBroadcastReceiver.ActionDelete
                `package` = applicationContext.packageName
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val showEta = estimatedCompletionEpochMs != null
        val title = buildNotificationTitle(modeLabel, percent, processed, total)
        val contentText = buildNotificationText(processed, total, movedCount)
        val subText = buildNotificationSubText(
            processed = processed,
            total = total,
            movedCount = movedCount,
            estimatedCompletionEpochMs = estimatedCompletionEpochMs
        )
        PromotedNotificationSupport.logRuntimeState(
            context = applicationContext,
            channelId = NOTIFICATION_CHANNEL_ID,
            reason = "before_build"
        )

        val notification = if (Build.VERSION.SDK_INT >= 36) {
            val builder = android.app.Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSubText(subText)
                .setColor(NotificationAccentColor)
                .setShortCriticalText("${percent}%")
                .setStyle(
                    createProgressStyle(
                        total = total,
                        movedCount = movedCount,
                        percent = percent
                    )
                )
                .setWhen(estimatedCompletionEpochMs ?: System.currentTimeMillis())
                .setShowWhen(showEta)
                .setUsesChronometer(showEta)
                .setChronometerCountDown(showEta)
                .setContentIntent(contentIntent)
                .setDeleteIntent(deleteIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(android.app.Notification.CATEGORY_PROGRESS)
                .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    android.app.Notification.Action.Builder(
                        null,
                        "중지",
                        workManager.createCancelPendingIntent(id)
                    ).build()
                )
            val requestResult = PromotedNotificationSupport.requestPromotedOngoing(builder)
            builder.build().also {
                PromotedNotificationSupport.logBuiltNotification(
                    context = applicationContext,
                    channelId = NOTIFICATION_CHANNEL_ID,
                    source = "worker_api36",
                    requestResult = requestResult,
                    notification = it,
                    processed = processed,
                    total = total,
                    movedCount = movedCount
                )
            }
        } else {
            NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSubText(subText)
                .setColor(NotificationAccentColor)
                .setProgress(total.coerceAtLeast(1), processed.coerceAtLeast(0), total <= 0)
                .setWhen(estimatedCompletionEpochMs ?: System.currentTimeMillis())
                .setShowWhen(showEta)
                .setUsesChronometer(showEta)
                .setChronometerCountDown(showEta)
                .setContentIntent(contentIntent)
                .setDeleteIntent(deleteIntent)
                .setExtras(PromotedNotificationSupport.promotedRequestExtras())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "중지",
                    workManager.createCancelPendingIntent(id)
                )
                .build().also {
                    PromotedNotificationSupport.logBuiltNotification(
                        context = applicationContext,
                        channelId = NOTIFICATION_CHANNEL_ID,
                        source = "worker_compat",
                        requestResult = PromotedNotificationSupport.RequestResult(
                            method = "extras[${PromotedNotificationSupport.RequestPromotedOngoingExtraKey}]=true",
                            reflectionApplied = false
                        ),
                        notification = it,
                        processed = processed,
                        total = total,
                        movedCount = movedCount
                    )
                }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NotificationId, notification)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "auto-classification-worker"
        const val KEY_JOB_ID = "job_id"

        const val PROGRESS_PROCESSED = "processed"
        const val PROGRESS_TOTAL = "total"
        const val PROGRESS_MODE_LABEL = "mode_label"
        const val PROGRESS_MOVED = "moved"
        const val PROGRESS_UPDATED_AT = "updated_at"
        const val PROGRESS_PERCENT = "percent"

        const val OUTPUT_SUMMARY = "summary"
        const val OUTPUT_COMPLETED_AT = "completed_at"
        const val OUTPUT_PROCESSED = "processed"
        const val OUTPUT_TOTAL = "total"
        const val OUTPUT_MODE_LABEL = "mode_label"
        const val OUTPUT_MOVED = "moved"

        private const val NotificationId = 4001
        private const val CompletionNotificationId = 4002
        const val NOTIFICATION_CHANNEL_ID = "auto-classification-live-update-v3"
        private const val COMPLETION_CHANNEL_ID = "auto-classification-summary-v1"
        private const val NotificationAccentColor = -0xe09115
        private const val ManifestSaveBatchSize = 10
        private const val ProgressUpdateBatchSize = 4
        private const val ProgressUpdateMinIntervalMs = 900L
        private val LEGACY_NOTIFICATION_CHANNEL_IDS = listOf(
            "auto-classification-progress",
            "auto-classification-progress-v2"
        )

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            LEGACY_NOTIFICATION_CHANNEL_IDS.forEach(notificationManager::deleteNotificationChannel)
            if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
                ensureCompletionNotificationChannel(notificationManager)
                PromotedNotificationSupport.logRuntimeState(
                    context = context,
                    channelId = NOTIFICATION_CHANNEL_ID,
                    reason = "channel_reused"
                )
                return
            }

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "AI 자동분류 진행",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "사진·동영상 AI 자동분류와 파일 이동 진행 상태를 표시합니다."
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                }
            )
            ensureCompletionNotificationChannel(notificationManager)
            PromotedNotificationSupport.logRuntimeState(
                context = context,
                channelId = NOTIFICATION_CHANNEL_ID,
                reason = "channel_created"
            )
        }

        fun buildRequest(jobId: String) = OneTimeWorkRequestBuilder<AutoClassificationWorker>()
            .setInputData(workDataOf(KEY_JOB_ID to jobId))
            .addTag(UNIQUE_WORK_NAME)
            .build()

        private fun ensureCompletionNotificationChannel(notificationManager: NotificationManager) {
            if (notificationManager.getNotificationChannel(COMPLETION_CHANNEL_ID) != null) {
                return
            }

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    COMPLETION_CHANNEL_ID,
                    "AI 자동분류 완료",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "AI 자동분류 완료, 중지, 실패 결과를 표시합니다."
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                    enableVibration(false)
                    setShowBadge(true)
                }
            )
        }
    }

    private fun publishTerminalNotification(
        title: String,
        text: String
    ) {
        ensureNotificationChannel(applicationContext)
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            CompletionNotificationId,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, COMPLETION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(CompletionNotificationId, notification)
    }
}

private fun estimateCompletionEpochMs(
    startedAtEpochMs: Long,
    updatedAtEpochMs: Long,
    processed: Int,
    total: Int
): Long? {
    if (processed <= 0 || total <= processed) {
        return null
    }

    val elapsedMs = (updatedAtEpochMs - startedAtEpochMs).coerceAtLeast(1L)
    val estimatedTotalMs = (elapsedMs.toDouble() / processed.toDouble() * total.toDouble()).toLong()
    return startedAtEpochMs + estimatedTotalMs
}

private const val NotificationMovedColor = -0xb45c5a
private const val NotificationProcessedColor = -0xe09115
private const val NotificationRemainingColor = -0x777778
private const val NotificationMilestoneColor = -0x444445

private fun buildNotificationTitle(
    modeLabel: String,
    percent: Int,
    processed: Int,
    total: Int
): String {
    if (total <= 0) {
        return "$modeLabel AI 자동분류 준비 중"
    }
    if (processed >= total) {
        return "$modeLabel AI 자동분류 마무리 중"
    }
    return "$modeLabel AI 자동분류 · $percent%"
}

private fun buildNotificationText(
    processed: Int,
    total: Int,
    movedCount: Int
): String {
    if (total <= 0) {
        return "대상 미디어를 준비하고 있다"
    }
    return buildString {
        append(formatNotificationCount(processed))
        append(" / ")
        append(formatNotificationCount(total))
        append("개 처리")
        append(" · 이동 ")
        append(formatNotificationCount(movedCount))
        append("개")
    }
}

private fun buildNotificationSubText(
    processed: Int,
    total: Int,
    movedCount: Int,
    estimatedCompletionEpochMs: Long?
): String {
    return buildString {
        append(currentProgressPhaseLabel(processed, total, movedCount))
        append(" · 남은 시간 ")
        append(formatRemainingDuration(estimatedCompletionEpochMs))
    }
}

private fun currentProgressPhaseLabel(
    processed: Int,
    total: Int,
    movedCount: Int
): String {
    if (total <= 0) {
        return "작업 준비 중"
    }
    if (processed <= 0) {
        return "첫 분류 준비 중"
    }
    if (processed >= total) {
        return "결과 정리 중"
    }
    if (movedCount > 0) {
        return "분류 및 이동 진행 중"
    }
    if (processed >= (total * 9) / 10) {
        return "마무리 분류 중"
    }
    return "AI 분류 진행 중"
}

private fun formatRemainingDuration(estimatedCompletionEpochMs: Long?): String {
    if (estimatedCompletionEpochMs == null) {
        return "계산 중"
    }

    val remainingMs = (estimatedCompletionEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
    val totalSeconds = (remainingMs / 1000L).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> String.format(Locale.KOREA, "%d시간 %02d분", hours, minutes)
        minutes > 0 -> String.format(Locale.KOREA, "%d분 %02d초", minutes, seconds)
        else -> String.format(Locale.KOREA, "%d초", seconds)
    }
}

private fun formatNotificationCount(value: Int): String {
    return NumberFormat.getIntegerInstance(Locale.KOREA).format(value)
}

private fun buildResultSummary(
    modeLabel: String,
    appliedCount: Int,
    movedCount: Int,
    skippedCount: Int,
    failedCount: Int,
    moveFiles: Boolean
): String {
    return buildString {
        append(modeLabel)
        append(" AI 자동분류 완료: ")
        append(appliedCount)
        append("개 적용")
        if (moveFiles) {
            append(", ")
            append(movedCount)
            append("개 이동")
        }
        if (skippedCount > 0) {
            append(", ")
            append(skippedCount)
            append("개 건너뜀")
        }
        if (failedCount > 0) {
            append(", ")
            append(failedCount)
            append("개 실패")
        }
    }
}

private fun buildTerminalStateText(
    processed: Int,
    total: Int,
    movedCount: Int
): String {
    return buildString {
        append(processed.coerceAtLeast(0))
        append(" / ")
        append(total.coerceAtLeast(0))
        append("개 처리")
        append(" · 이동 ")
        append(movedCount.coerceAtLeast(0))
        append("개")
    }
}

private fun createProgressStyle(
    total: Int,
    movedCount: Int,
    percent: Int
): android.app.Notification.ProgressStyle {
    val style = android.app.Notification.ProgressStyle()
        .setStyledByProgress(true)
        .setProgressIndeterminate(total <= 0)
        .setProgress(percent)
        .setProgressStartIcon(
            Icon.createWithResource(
                "android",
                android.R.drawable.ic_menu_gallery
            )
        )
        .setProgressTrackerIcon(
            Icon.createWithResource(
                "android",
                android.R.drawable.stat_notify_sync
            )
        )
        .setProgressEndIcon(
            Icon.createWithResource(
                "android",
                android.R.drawable.ic_menu_save
            )
        )

    if (total > 0) {
        style.setProgressSegments(buildProgressSegments(percent, total, movedCount))
        style.setProgressPoints(buildProgressPoints())
    }

    return style
}

private fun buildProgressSegments(
    percent: Int,
    total: Int,
    movedCount: Int
): List<android.app.Notification.ProgressStyle.Segment> {
    val movedPercent = if (total > 0) {
        ((movedCount.toFloat() / total.toFloat()) * 100f).roundToInt()
            .coerceIn(0, percent)
    } else {
        0
    }
    val processedOnlyPercent = (percent - movedPercent).coerceAtLeast(0)
    val remainingPercent = (100 - movedPercent - processedOnlyPercent).coerceAtLeast(0)

    return buildList {
        if (movedPercent > 0) {
            add(
                android.app.Notification.ProgressStyle.Segment(movedPercent)
                    .setId(1)
                    .setColor(NotificationMovedColor)
            )
        }
        if (processedOnlyPercent > 0) {
            add(
                android.app.Notification.ProgressStyle.Segment(processedOnlyPercent)
                    .setId(2)
                    .setColor(NotificationProcessedColor)
            )
        }
        if (remainingPercent > 0) {
            add(
                android.app.Notification.ProgressStyle.Segment(remainingPercent)
                    .setId(3)
                    .setColor(NotificationRemainingColor)
            )
        }
    }
}

private fun buildProgressPoints(): List<android.app.Notification.ProgressStyle.Point> {
    return listOf(25, 50, 75).map { position ->
        android.app.Notification.ProgressStyle.Point(position)
            .setId(position)
            .setColor(NotificationMilestoneColor)
    }
}
