package com.codex.ppa.data

import android.net.Uri
import com.codex.ppa.domain.MediaItem
import com.codex.ppa.domain.MediaType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class AutoClassificationJobStore(
    private val jobsDirectory: File
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun save(job: AutoClassificationJobSpec) {
        jobsDirectory.mkdirs()
        jobFile(job.jobId).writeText(json.encodeToString(AutoClassificationJobSpec.serializer(), job))
    }

    fun load(jobId: String): AutoClassificationJobSpec {
        val file = jobFile(jobId)
        require(file.exists()) { "자동분류 작업 정보를 찾을 수 없습니다: $jobId" }
        return json.decodeFromString(AutoClassificationJobSpec.serializer(), file.readText())
    }

    fun delete(jobId: String) {
        jobFile(jobId).delete()
    }

    private fun jobFile(jobId: String): File = File(jobsDirectory, "$jobId.json")
}

@Serializable
data class AutoClassificationJobSpec(
    val jobId: String,
    val requestedAtEpochMs: Long = System.currentTimeMillis(),
    val onlyUnclassified: Boolean,
    val moveFiles: Boolean = true,
    val mediaItems: List<QueuedMediaItem>
) {
    val modeLabel: String
        get() = if (onlyUnclassified) "미분류만" else "전체"
}

@Serializable
data class QueuedMediaItem(
    val recordId: String,
    val mediaType: MediaType,
    val mediaStoreId: Long,
    val contentUri: String,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val dateModifiedEpochSeconds: Long,
    val dateAddedEpochSeconds: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?
) {
    fun toMediaItem(): MediaItem = MediaItem(
        recordId = recordId,
        mediaType = mediaType,
        mediaStoreId = mediaStoreId,
        contentUri = Uri.parse(contentUri),
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

fun MediaItem.toQueuedMediaItem(): QueuedMediaItem = QueuedMediaItem(
    recordId = recordId,
    mediaType = mediaType,
    mediaStoreId = mediaStoreId,
    contentUri = contentUri.toString(),
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
