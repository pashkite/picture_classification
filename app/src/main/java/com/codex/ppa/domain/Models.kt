package com.codex.ppa.domain

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
enum class MediaType {
    IMAGE,
    VIDEO
}

data class MediaItem(
    val recordId: String,
    val mediaType: MediaType,
    val mediaStoreId: Long,
    val contentUri: Uri,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val dateModifiedEpochSeconds: Long,
    val dateAddedEpochSeconds: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?
)

@Serializable
data class ClassificationLabels(
    val level1: String = "",
    val level2: String = "",
    val level3: String = ""
) {
    val isEmpty: Boolean
        get() = level1.isBlank() && level2.isBlank() && level3.isBlank()

    fun normalized(): ClassificationLabels = copy(
        level1 = level1.trim(),
        level2 = level2.trim(),
        level3 = level3.trim()
    )
}

@Serializable
data class ClassificationManifest(
    val schemaVersion: Int = 2,
    val exportedAtEpochMs: Long = System.currentTimeMillis(),
    val formatName: String = "manifest.json",
    val appVersion: String = "0.1.0",
    val entries: List<ClassificationEntry> = emptyList()
)

@Serializable
data class ClassificationEntry(
    val recordId: String,
    val fingerprint: MediaFingerprint,
    val classification: ClassificationLabels = ClassificationLabels(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val source: ClassificationSource = ClassificationSource.MANUAL,
    val engineInfo: ClassificationEngineInfo? = null,
    val debugInfo: ClassificationDebugInfo? = null
)

@Serializable
data class MediaFingerprint(
    val mediaType: MediaType,
    val contentUri: String,
    val mediaStoreId: Long,
    val displayName: String,
    val relativePath: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long,
    val dateModifiedEpochSeconds: Long,
    val dateAddedEpochSeconds: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null
)

@Serializable
enum class ClassificationSource {
    MANUAL,
    IMPORTED,
    MODEL_SUGGESTION
}

@Serializable
data class ClassificationEngineInfo(
    val engineId: String,
    val engineVersion: String? = null,
    val engineDisplayName: String? = null
)

@Serializable
data class ClassificationDebugInfo(
    val confidence: Float = 0f,
    val reducedMode: Boolean = false,
    val reasoning: List<String> = emptyList(),
    val finalScores: List<ScoredSignal> = emptyList(),
    val seriesCandidates: List<ScoredSignal> = emptyList(),
    val modelOutputs: List<ModelDebugInfo> = emptyList()
)

@Serializable
data class ModelDebugInfo(
    val modelId: String,
    val displayName: String,
    val summary: String = "",
    val tags: List<ScoredSignal> = emptyList()
)

@Serializable
data class ScoredSignal(
    val label: String,
    val score: Float
)

data class ClassificationSuggestion(
    val labels: ClassificationLabels,
    val confidence: Float,
    val engineInfo: ClassificationEngineInfo? = null,
    val debugInfo: ClassificationDebugInfo? = null
)

data class ClassificationEngineStatus(
    val engineId: String,
    val displayName: String,
    val engineVersion: String? = null,
    val reducedMode: Boolean = false,
    val models: List<ModelRuntimeStatus> = emptyList()
)

data class ModelRuntimeStatus(
    val modelId: String,
    val displayName: String,
    val version: String? = null,
    val role: String = "",
    val assetPath: String? = null,
    val available: Boolean = false,
    val loaded: Boolean = false,
    val summary: String = ""
)

fun MediaItem.toFingerprint(): MediaFingerprint = MediaFingerprint(
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
