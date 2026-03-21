package com.codex.ppa.domain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OnDeviceAiClassificationEngine(
    context: Context,
    private val fallbackEngine: ClassificationEngine = BasicSuggestionClassificationEngine()
) : ClassificationEngine {
    override val engineId: String = "hybrid-mlkit-mediapipe"
    override val displayName: String = "하이브리드 자동분류 (EfficientNet-Lite4 + MobileCLIP2-S0 + ImageEmbedder + ML Kit)"
    override val engineVersion: String = "efficientnet-lite4-fp32 + mobileclip2-s0-onnx + mediapipe-0.10.29 + mlkit-bundled + rules-v5"

    private val appContext = context.applicationContext
    private val taxonomy by lazy { VisionTaxonomyLoader.load(appContext) }
    private val frameSampler by lazy { MediaFrameSampler(appContext) }
    private val mediaPipeProvider by lazy { MediaPipeVisionModelProvider(appContext) }
    private val mobileClipProvider by lazy { MobileClipVisionModelProvider(appContext) }
    private val semanticEngine by lazy {
        MediaPipeSemanticInferenceEngine(
            context = appContext,
            modelProvider = mediaPipeProvider,
            taxonomy = taxonomy
        )
    }
    private val mobileClipEngine by lazy { MobileClipSemanticInferenceEngine(appContext, mobileClipProvider) }
    private val mlKitEngine by lazy { MlKitAuxiliaryInferenceEngine(appContext) }
    private val pipeline by lazy {
        ClassificationPipeline(
            taxonomy = taxonomy,
            semanticEngine = semanticEngine,
            clipEngine = mobileClipEngine,
            mlKitEngine = mlKitEngine,
            fallbackEngine = fallbackEngine
        )
    }

    override fun runtimeStatus(): ClassificationEngineStatus {
        val modelStatuses = runCatching { pipeline.runtimeStatus() }
            .getOrElse { fallbackEngine.runtimeStatus().models }

        return ClassificationEngineStatus(
            engineId = engineId,
            displayName = displayName,
            engineVersion = engineVersion,
            reducedMode = modelStatuses.none { status ->
                status.loaded && status.modelId in setOf(
                    "main-image-classifier",
                    "mediapipe-image-embedder",
                    "mobileclip-vision-encoder"
                )
            },
            models = modelStatuses
        )
    }

    override suspend fun classify(mediaItem: MediaItem): ClassificationSuggestion? {
        val frames = frameSampler.sample(mediaItem)
        val engineInfo = ClassificationEngineInfo(
            engineId = engineId,
            engineVersion = engineVersion,
            engineDisplayName = displayName
        )
        if (frames.isEmpty()) {
            val fallbackSuggestion = fallbackEngine.classify(mediaItem)
            return fallbackSuggestion?.copy(
                engineInfo = engineInfo,
                debugInfo = fallbackSuggestion.debugInfo?.copy(
                    fallbackUsed = true,
                    usedEngines = listOf("rule-based-fallback"),
                    reasoning = listOf("대표 프레임을 읽지 못해 규칙 기반 축소 모드로 처리") +
                        fallbackSuggestion.debugInfo.reasoning,
                    reducedMode = true
                )
            )
        }

        val request = MediaInferenceRequest(
            mediaItem = mediaItem,
            frames = frames
        )

        return try {
            withContext(Dispatchers.Default) {
                pipeline.classify(
                    request = request,
                    engineInfo = engineInfo
                ) ?: fallbackEngine.classify(mediaItem)?.copy(engineInfo = engineInfo)
            }
        } finally {
            frames.forEach { frame ->
                frame.bitmap.recycle()
            }
        }
    }
}
