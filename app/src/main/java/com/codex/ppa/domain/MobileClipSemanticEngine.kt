package com.codex.ppa.domain

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.FloatBuffer
import kotlin.math.exp

@Serializable
internal data class MobileClipPromptCatalog(
    val schemaVersion: Int = 1,
    val modelId: String = "",
    val embeddingSize: Int = 512,
    val entries: List<MobileClipPromptEntry> = emptyList()
)

@Serializable
internal data class MobileClipPromptEntry(
    val level: String,
    val label: String,
    val prompts: List<String> = emptyList(),
    val embedding: List<Float> = emptyList()
)

internal data class MobileClipSemanticResult(
    val primaryScores: Map<String, Float>,
    val secondaryScores: Map<String, Float>,
    val tags: List<ScoredSignal>,
    val reasoning: List<String>,
    val loaded: Boolean,
    val invoked: Boolean,
    val reducedMode: Boolean,
    val frameSummaries: List<FrameDebugInfo> = emptyList()
)

internal data class LoadedMobileClipPromptEntry(
    val level: String,
    val label: String,
    val prompts: List<String>,
    val embedding: FloatArray
)

internal data class LoadedMobileClipPromptCatalog(
    val modelId: String,
    val embeddingSize: Int,
    val entries: List<LoadedMobileClipPromptEntry>
)

internal class MobileClipVisionModelProvider(
    private val context: Context
) : ModelProvider {
    private val initMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private var session: OrtSession? = null
    private var sessionFailure: String? = null
    private var promptCatalog: LoadedMobileClipPromptCatalog? = null
    private var promptCatalogFailure: String? = null

    suspend fun ensureInitialized() {
        initMutex.withLock {
            if ((session != null || sessionFailure != null) &&
                (promptCatalog != null || promptCatalogFailure != null)
            ) {
                return
            }

            if (session == null && sessionFailure == null) {
                session = runCatching {
                    val sessionOptions = OrtSession.SessionOptions()
                    context.assets.open(ModelAssetPath).use { input ->
                        environment.createSession(input.readBytes(), sessionOptions)
                    }
                }.onFailure { throwable ->
                    sessionFailure = throwable.message ?: "MobileCLIP vision encoder 초기화 실패"
                    Log.w(MobileClipLogTag, "MobileCLIP session init failed", throwable)
                }.getOrNull()
            }

            if (promptCatalog == null && promptCatalogFailure == null) {
                promptCatalog = runCatching {
                    context.assets.open(PromptCatalogAssetPath).bufferedReader().use { reader ->
                        val decoded = json.decodeFromString<MobileClipPromptCatalog>(reader.readText())
                        LoadedMobileClipPromptCatalog(
                            modelId = decoded.modelId,
                            embeddingSize = decoded.embeddingSize,
                            entries = decoded.entries.map { entry ->
                                LoadedMobileClipPromptEntry(
                                    level = entry.level,
                                    label = entry.label,
                                    prompts = entry.prompts,
                                    embedding = normalizeVector(entry.embedding.toFloatArray())
                                )
                            }
                        )
                    }
                }.onFailure { throwable ->
                    promptCatalogFailure = throwable.message ?: "MobileCLIP prompt catalog 로드 실패"
                    Log.w(MobileClipLogTag, "MobileCLIP prompt catalog init failed", throwable)
                }.getOrNull()
            }
        }
    }

    fun sessionOrNull(): OrtSession? = session

    fun promptCatalogOrNull(): LoadedMobileClipPromptCatalog? = promptCatalog

    fun environment(): OrtEnvironment = environment

    override fun runtimeStatus(): List<ModelRuntimeStatus> {
        return listOf(
            ModelRuntimeStatus(
                modelId = "mobileclip-vision-encoder",
                displayName = "MobileCLIP2-S0 Vision Encoder",
                version = "mobileclip2-s0/onnx",
                role = "추가 의미 기반 이미지 임베딩",
                assetPath = ModelAssetPath,
                available = mobileClipAssetExists(context, ModelAssetPath),
                loaded = session != null,
                summary = sessionFailure ?: if (session != null) {
                    "MobileCLIP2-S0 vision encoder를 ONNX Runtime으로 로드했다."
                } else {
                    "아직 로드되지 않았지만 vision encoder 자산이 준비돼 있다."
                }
            ),
            ModelRuntimeStatus(
                modelId = "mobileclip-prompt-catalog",
                displayName = "MobileCLIP Prompt Embeddings",
                version = "schema-1",
                role = "사전 계산된 CLIP 텍스트 프롬프트 임베딩",
                assetPath = PromptCatalogAssetPath,
                available = mobileClipAssetExists(context, PromptCatalogAssetPath),
                loaded = promptCatalog != null,
                summary = promptCatalogFailure ?: if (promptCatalog != null) {
                    "앱 분류 라벨용 프롬프트 임베딩 ${promptCatalog?.entries?.size ?: 0}개를 로드했다."
                } else {
                    "아직 로드되지 않았지만 프롬프트 임베딩 자산이 준비돼 있다."
                }
            )
        )
    }

    companion object {
        const val ModelAssetPath: String = "mobileclip2_s0_vision.onnx"
        const val PromptCatalogAssetPath: String = "mobileclip_prompt_embeddings.json"
    }
}

internal class MobileClipSemanticInferenceEngine(
    private val context: Context,
    private val modelProvider: MobileClipVisionModelProvider
) : InferenceEngine<MobileClipSemanticResult> {
    override fun runtimeStatus(): List<ModelRuntimeStatus> = modelProvider.runtimeStatus()

    override suspend fun infer(request: MediaInferenceRequest): MobileClipSemanticResult? {
        modelProvider.ensureInitialized()
        val session = modelProvider.sessionOrNull()
        val promptCatalog = modelProvider.promptCatalogOrNull()

        if (session == null || promptCatalog == null) {
            return MobileClipSemanticResult(
                primaryScores = emptyMap(),
                secondaryScores = emptyMap(),
                tags = emptyList(),
                reasoning = listOf("MobileCLIP vision encoder 또는 prompt catalog 를 로드하지 못해 CLIP 의미 보강을 건너뛰었다."),
                loaded = false,
                invoked = false,
                reducedMode = true
            )
        }

        val primaryFrameScores = mutableListOf<Map<String, Float>>()
        val secondaryFrameScores = mutableListOf<Map<String, Float>>()
        val frameSummaries = mutableListOf<FrameDebugInfo>()
        val reasoning = mutableListOf<String>()
        var invoked = false

        request.frames.forEach { frame ->
            val embedding = runCatching {
                embedBitmap(frame.bitmap, session, modelProvider.environment())
            }.onFailure { throwable ->
                Log.w(MobileClipLogTag, "MobileCLIP frame inference failed: ${frame.label}", throwable)
            }.getOrNull() ?: return@forEach

            invoked = true
            val primarySignals = scorePromptEntries(embedding, promptCatalog.entries.filter { it.level == "primary" })
            val secondarySignals = scorePromptEntries(embedding, promptCatalog.entries.filter { it.level == "secondary" })

            primaryFrameScores += primarySignals.associate { it.label to it.score }
            secondaryFrameScores += secondarySignals.associate { it.label to it.score }

            frameSummaries += FrameDebugInfo(
                frameLabel = frame.label,
                timestampMs = frame.timestampMs,
                summary = buildString {
                    if (primarySignals.isNotEmpty()) {
                        append("MobileCLIP 1차 ")
                        append(primarySignals.take(3).joinToString { "${it.label}:${mobileClipFormatScore(it.score)}" })
                    }
                    if (secondarySignals.isNotEmpty()) {
                        append(" · 2차 ")
                        append(secondarySignals.take(2).joinToString { "${it.label}:${mobileClipFormatScore(it.score)}" })
                    }
                },
                tags = (primarySignals.take(3) + secondarySignals.take(2)).take(5),
                notes = listOf("CLIP류 vision encoder 유사도 기반 랭킹")
            )
        }

        if (!invoked) {
            return MobileClipSemanticResult(
                primaryScores = emptyMap(),
                secondaryScores = emptyMap(),
                tags = emptyList(),
                reasoning = listOf("대표 프레임에서 MobileCLIP 임베딩을 생성하지 못했다."),
                loaded = true,
                invoked = false,
                reducedMode = true
            )
        }

        val primaryScores = aggregateFrameScores(primaryFrameScores)
        val secondaryScores = aggregateFrameScores(secondaryFrameScores)
        val topSignals = (
            primaryScores.entries.map { ScoredSignal(it.key, it.value) } +
                secondaryScores.entries.map { ScoredSignal(it.key, it.value) }
            ).sortedByDescending { it.score }
            .take(10)

        if (topSignals.isNotEmpty()) {
            reasoning += "MobileCLIP 상위 후보: ${topSignals.take(5).joinToString { "${it.label}:${mobileClipFormatScore(it.score)}" }}"
        }

        return MobileClipSemanticResult(
            primaryScores = primaryScores,
            secondaryScores = secondaryScores,
            tags = topSignals,
            reasoning = reasoning,
            loaded = true,
            invoked = true,
            reducedMode = false,
            frameSummaries = frameSummaries
        )
    }

    private fun embedBitmap(
        bitmap: Bitmap,
        session: OrtSession,
        environment: OrtEnvironment
    ): FloatArray {
        val preparedBitmap = centerCropAndResize(bitmap, MobileClipImageSize)
        val pixelCount = MobileClipImageSize * MobileClipImageSize
        val pixels = IntArray(pixelCount)
        val inputData = FloatArray(pixelCount * 3)

        try {
            preparedBitmap.getPixels(
                pixels,
                0,
                MobileClipImageSize,
                0,
                0,
                MobileClipImageSize,
                MobileClipImageSize
            )

            pixels.forEachIndexed { index, color ->
                inputData[index] = Color.red(color) / 255f
                inputData[pixelCount + index] = Color.green(color) / 255f
                inputData[(pixelCount * 2) + index] = Color.blue(color) / 255f
            }

            val tensor = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(inputData),
                longArrayOf(1, 3, MobileClipImageSize.toLong(), MobileClipImageSize.toLong())
            )

            try {
                val result = session.run(mapOf("pixel_values" to tensor))
                try {
                    @Suppress("UNCHECKED_CAST")
                    val output = result[0].value as Array<FloatArray>
                    return normalizeVector(output[0].clone())
                } finally {
                    result.close()
                }
            } finally {
                tensor.close()
            }
        } finally {
            if (preparedBitmap !== bitmap) {
                preparedBitmap.recycle()
            }
        }
    }

    private fun scorePromptEntries(
        imageEmbedding: FloatArray,
        entries: List<LoadedMobileClipPromptEntry>
    ): List<ScoredSignal> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val rawScores = entries.map { entry ->
            entry.label to mobileClipCosineSimilarity(imageEmbedding, entry.embedding)
        }.filter { (_, score) -> score.isFinite() }

        if (rawScores.isEmpty()) {
            return emptyList()
        }

        return softmaxSimilaritySignals(rawScores, MobileClipSoftmaxTemperature)
    }

    private fun centerCropAndResize(bitmap: Bitmap, size: Int): Bitmap {
        val cropSize = minOf(bitmap.width, bitmap.height)
        val left = ((bitmap.width - cropSize) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - cropSize) / 2).coerceAtLeast(0)
        val cropped = if (bitmap.width == cropSize && bitmap.height == cropSize) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, left, top, cropSize, cropSize)
        }

        return if (cropped.width == size && cropped.height == size) {
            cropped
        } else {
            val resized = Bitmap.createScaledBitmap(cropped, size, size, true)
            if (cropped !== bitmap) {
                cropped.recycle()
            }
            resized
        }
    }

    companion object {
        private const val MobileClipImageSize = 256
        private const val MobileClipSoftmaxTemperature = 12f
    }
}

private fun normalizeVector(values: FloatArray): FloatArray {
    val norm = values.fold(0f) { acc, value -> acc + (value * value) }
    if (norm <= 1e-12f) {
        return values
    }
    val scale = kotlin.math.sqrt(norm)
    return FloatArray(values.size) { index -> values[index] / scale }
}

private fun mobileClipAssetExists(context: Context, assetPath: String): Boolean {
    return runCatching {
        context.assets.open(assetPath).close()
        true
    }.getOrDefault(false)
}

private fun mobileClipFormatScore(value: Float): String = "%.2f".format(value)

private fun mobileClipCosineSimilarity(first: FloatArray, second: FloatArray): Float {
    if (first.isEmpty() || second.isEmpty() || first.size != second.size) {
        return 0f
    }

    var dot = 0f
    var firstNorm = 0f
    var secondNorm = 0f
    first.indices.forEach { index ->
        dot += first[index] * second[index]
        firstNorm += first[index] * first[index]
        secondNorm += second[index] * second[index]
    }

    if (firstNorm == 0f || secondNorm == 0f) {
        return 0f
    }

    return (dot / (kotlin.math.sqrt(firstNorm) * kotlin.math.sqrt(secondNorm))).coerceIn(-1f, 1f)
}

internal fun softmaxSimilaritySignals(
    rawScores: List<Pair<String, Float>>,
    temperature: Float
): List<ScoredSignal> {
    if (rawScores.isEmpty()) {
        return emptyList()
    }

    val maxScore = rawScores.maxOf { it.second }
    val expScores = rawScores.map { (label, score) ->
        label to exp(((score - maxScore) * temperature).toDouble())
    }
    val total = expScores.sumOf { it.second }.takeIf { it > 0.0 } ?: return emptyList()

    return expScores.map { (label, value) ->
        ScoredSignal(label, (value / total).toFloat())
    }.sortedByDescending { it.score }
}

private const val MobileClipLogTag = "MobileClipEngine"
