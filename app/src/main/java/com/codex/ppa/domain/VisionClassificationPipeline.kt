package com.codex.ppa.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.Size
import com.google.android.gms.tasks.Task
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

interface ModelProvider {
    fun runtimeStatus(): List<ModelRuntimeStatus>
}

interface InferenceEngine<ResultT> : ModelProvider {
    suspend fun infer(request: MediaInferenceRequest): ResultT?
}

data class MediaInferenceRequest(
    val mediaItem: MediaItem,
    val frames: List<SampledFrame>
)

data class SampledFrame(
    val label: String,
    val timestampMs: Long?,
    val bitmap: Bitmap
)

internal data class SemanticInferenceResult(
    val primaryScores: Map<String, Float>,
    val secondaryScores: Map<String, Float>,
    val classifierTags: List<ScoredSignal>,
    val prototypeTags: List<ScoredSignal>,
    val reasoning: List<String>,
    val reducedMode: Boolean,
    val classifierLoaded: Boolean = false,
    val classifierInvoked: Boolean = false,
    val embedderLoaded: Boolean = false,
    val embedderInvoked: Boolean = false,
    val frameSummaries: List<FrameDebugInfo> = emptyList()
)

internal data class MlKitAuxiliaryResult(
    val faceCount: Int,
    val centeredFaceScore: Float,
    val maxFaceAreaRatio: Float = 0f,
    val maxFaceCenteredness: Float = 0f,
    val recognizedText: String,
    val textLength: Int,
    val textLineCount: Int,
    val uiKeywordHits: List<String>,
    val receiptKeywordHits: List<String>,
    val auxiliaryTags: List<ScoredSignal>,
    val reasoning: List<String>,
    val faceInvoked: Boolean = false,
    val textInvoked: Boolean = false,
    val labelInvoked: Boolean = false,
    val frameSummaries: List<FrameDebugInfo> = emptyList()
)

internal data class MainClassifierOutput(
    val tags: List<ScoredSignal>,
    val backendLabel: String
)

internal class MediaFrameSampler(
    private val context: Context
) {
    suspend fun sample(mediaItem: MediaItem): List<SampledFrame> = withContext(Dispatchers.IO) {
        when (mediaItem.mediaType) {
            MediaType.IMAGE -> listOfNotNull(loadImageFrame(mediaItem))
            MediaType.VIDEO -> loadVideoFrames(mediaItem)
        }
    }

    private fun loadImageFrame(mediaItem: MediaItem): SampledFrame? {
        val bitmap = runCatching {
            context.contentResolver.loadThumbnail(mediaItem.contentUri, Size(320, 320), null)
        }.getOrElse {
            Log.w(LogTag, "image thumbnail load failed for ${mediaItem.displayName}: ${it.message}")
            null
        } ?: return null

        return SampledFrame(
            label = "대표 이미지",
            timestampMs = null,
            bitmap = bitmap
        )
    }

    private fun loadVideoFrames(mediaItem: MediaItem): List<SampledFrame> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, mediaItem.contentUri)
            val durationMs = mediaItem.durationMs?.takeIf { it > 0L } ?: 3_000L
            val fractions = listOf(0.2f, 0.5f, 0.8f)
            fractions.mapNotNull { fraction ->
                val timestampMs = (durationMs * fraction).roundToInt().toLong().coerceAtLeast(0L)
                val bitmap = retriever.getScaledFrameAtTime(
                    timestampMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    320,
                    320
                ) ?: retriever.getFrameAtTime(
                    timestampMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

                bitmap?.let {
                    SampledFrame(
                        label = "${(fraction * 100).roundToInt()}% 프레임",
                        timestampMs = timestampMs,
                        bitmap = it
                    )
                }
            }
        } finally {
            retriever.release()
        }
    }
}

internal class MainImageClassifier(
    private val context: Context
) : ModelProvider {
    private val initMutex = Mutex()
    private var taskClassifier: ImageClassifier? = null
    private var taskClassifierFailure: String? = null
    private var directClassifier: DirectLite4Classifier? = null
    private var directClassifierFailure: String? = null
    private var labelMap: Map<Int, String> = emptyMap()
    private var labelMapFailure: String? = null

    suspend fun ensureInitialized() {
        initMutex.withLock {
            if ((taskClassifier != null || taskClassifierFailure != null || directClassifier != null || directClassifierFailure != null) &&
                (labelMap.isNotEmpty() || labelMapFailure != null)
            ) {
                return
            }

            if (labelMap.isEmpty() && labelMapFailure == null) {
                runCatching {
                    loadEfficientNetLabelMap(context, LabelsAssetPath)
                }.onSuccess { labels ->
                    labelMap = labels
                }.onFailure { throwable ->
                    labelMapFailure = throwable.message ?: "ImageNet 라벨맵을 읽지 못했습니다."
                    Log.w(LogTag, "EfficientNet label map load failed", throwable)
                }
            }

            if (taskClassifier == null && taskClassifierFailure == null) {
                taskClassifier = runCatching { createTaskImageClassifier() }
                    .onFailure {
                        taskClassifierFailure = it.message ?: "MediaPipe ImageClassifier 초기화 실패"
                        Log.w(LogTag, "MediaPipe Lite4 classifier init failed", it)
                    }
                    .getOrNull()
            }

            if (directClassifier == null && directClassifierFailure == null) {
                directClassifier = runCatching {
                    DirectLite4Classifier(
                        context = context,
                        assetPath = ModelAssetPath,
                        labels = labelMap
                    )
                }.onFailure {
                    directClassifierFailure = it.message ?: "Lite4 direct classifier 초기화 실패"
                    Log.w(LogTag, "Direct Lite4 classifier init failed", it)
                }.getOrNull()
            }
        }
    }

    fun isLoaded(): Boolean = taskClassifier != null || directClassifier != null

    override fun runtimeStatus(): List<ModelRuntimeStatus> {
        return listOf(
            ModelRuntimeStatus(
                modelId = "main-image-classifier",
                displayName = "Main Image Classifier (EfficientNet-Lite4)",
                version = "efficientnet-lite4-fp32/1",
                role = "주된 일반 이미지 의미 분류",
                assetPath = ModelAssetPath,
                available = assetExists(context, ModelAssetPath),
                loaded = isLoaded(),
                summary = buildString {
                    when {
                        taskClassifier != null -> {
                            append("EfficientNet-Lite4 FP32를 MediaPipe ImageClassifier로 로드했다.")
                        }
                        directClassifier != null -> {
                            append("MediaPipe task 메타데이터 제약으로 같은 Lite4 모델을 TensorFlow Lite Interpreter로 직접 로드했다.")
                        }
                        taskClassifierFailure != null || directClassifierFailure != null -> {
                            append(taskClassifierFailure ?: directClassifierFailure ?: "Lite4 분류기를 로드하지 못했습니다.")
                        }
                        else -> {
                            append("아직 로드되지 않았지만 Lite4 모델 자산이 준비돼 있다.")
                        }
                    }
                    append(" 이 모델은 generic ImageNet 분류기라 앱 전용 의미 해석은 보수적으로 적용한다.")
                    if (labelMap.isNotEmpty()) {
                        append(" 외부 ImageNet 라벨맵을 함께 사용한다.")
                    } else if (labelMapFailure != null) {
                        append(" 라벨맵 상태: ${labelMapFailure}.")
                    }
                }
            ),
            ModelRuntimeStatus(
                modelId = "main-image-classifier-labels",
                displayName = "ImageNet Labels Map",
                version = "imagenet-1000",
                role = "Lite4 출력 index를 라벨로 해석",
                assetPath = LabelsAssetPath,
                available = assetExists(context, LabelsAssetPath),
                loaded = labelMap.isNotEmpty(),
                summary = labelMapFailure ?: if (labelMap.isNotEmpty()) {
                    "외부 labels_map.txt 형식 자산을 파싱해 Lite4 결과 라벨을 해석한다."
                } else {
                    "아직 읽지 않았지만 라벨맵 자산이 준비돼 있다."
                }
            )
        )
    }

    suspend fun classify(bitmap: Bitmap): MainClassifierOutput? {
        ensureInitialized()

        taskClassifier?.let { classifier ->
            runCatching {
                classifyWithTask(classifier, bitmap)
            }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { tags ->
                    return MainClassifierOutput(
                        tags = tags,
                        backendLabel = "MediaPipe ImageClassifier"
                    )
                }
        }

        directClassifier?.let { classifier ->
            runCatching {
                classifier.classify(bitmap)
            }.getOrElse { throwable ->
                Log.w(LogTag, "Direct Lite4 classify failed", throwable)
                emptyList()
            }.takeIf { it.isNotEmpty() }
                ?.let { tags ->
                    return MainClassifierOutput(
                        tags = tags,
                        backendLabel = "TensorFlow Lite Interpreter"
                    )
                }
        }

        return null
    }

    private fun classifyWithTask(
        classifier: ImageClassifier,
        bitmap: Bitmap
    ): List<ScoredSignal> {
        val mpImage = BitmapImageBuilder(bitmap).build()
        return classifier.classify(mpImage)
            .classificationResult()
            .classifications()
            .flatMap { it.categories() }
            .mapNotNull { category ->
                val label = category.categoryName()
                    .takeIf { it.isNotBlank() }
                    ?: labelMap[category.index()]
                label
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ScoredSignal(it, category.score()) }
            }
            .sortedByDescending { it.score }
            .take(MainClassifierMaxResults)
    }

    private fun createTaskImageClassifier(): ImageClassifier {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath(ModelAssetPath)
            .build()

        val options = ImageClassifier.ImageClassifierOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(0.05f)
            .setMaxResults(MainClassifierMaxResults)
            .build()

        return ImageClassifier.createFromOptions(context, options)
    }

    companion object {
        const val ModelAssetPath: String = "efficientnet-lite4.tflite"
        const val LabelsAssetPath: String = "efficientnet-imagenet-labels.txt"
        const val InputImageSize: Int = 300
        private const val MainClassifierMaxResults: Int = 6
    }
}

internal class DirectLite4Classifier(
    private val context: Context,
    private val assetPath: String,
    private val labels: Map<Int, String>
) {
    private val inferenceMutex = Mutex()
    private val interpreter: Interpreter by lazy {
        Interpreter(
            loadMappedAsset(assetPath),
            Interpreter.Options().apply {
                setNumThreads(4)
            }
        )
    }
    private val inputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(4 * MainImageClassifier.InputImageSize * MainImageClassifier.InputImageSize * 3)
            .order(ByteOrder.nativeOrder())
    }
    private val pixelBuffer = IntArray(MainImageClassifier.InputImageSize * MainImageClassifier.InputImageSize)
    private val outputSize: Int by lazy {
        interpreter.getOutputTensor(0).shape().lastOrNull()?.coerceAtLeast(1) ?: 1_000
    }

    suspend fun classify(bitmap: Bitmap): List<ScoredSignal> = inferenceMutex.withLock {
        try {
            val scaledBitmap = if (bitmap.width == MainImageClassifier.InputImageSize &&
                bitmap.height == MainImageClassifier.InputImageSize
            ) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(
                    bitmap,
                    MainImageClassifier.InputImageSize,
                    MainImageClassifier.InputImageSize,
                    true
                )
            }

            try {
                scaledBitmap.getPixels(
                    pixelBuffer,
                    0,
                    MainImageClassifier.InputImageSize,
                    0,
                    0,
                    MainImageClassifier.InputImageSize,
                    MainImageClassifier.InputImageSize
                )
                inputBuffer.clear()
                pixelBuffer.forEach { color ->
                    inputBuffer.putFloat((Color.red(color) - EfficientNetMean) / EfficientNetStd)
                    inputBuffer.putFloat((Color.green(color) - EfficientNetMean) / EfficientNetStd)
                    inputBuffer.putFloat((Color.blue(color) - EfficientNetMean) / EfficientNetStd)
                }
                inputBuffer.rewind()

                val logits = Array(1) { FloatArray(outputSize) }
                interpreter.run(inputBuffer, logits)

                return softmaxTopK(logits[0], labels, MaxResults)
            } finally {
                if (scaledBitmap !== bitmap) {
                    scaledBitmap.recycle()
                }
            }
        } catch (oom: OutOfMemoryError) {
            throw IllegalStateException("EfficientNet-Lite4 추론 중 메모리가 부족합니다.", oom)
        }
    }

    private fun loadMappedAsset(assetPath: String) =
        context.assets.openFd(assetPath).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength
            )
        }

    companion object {
        private const val EfficientNetMean = 127f
        private const val EfficientNetStd = 128f
        private const val MaxResults = 6
    }
}

internal class MediaPipeVisionModelProvider(
    private val context: Context
) : ModelProvider {
    private val initMutex = Mutex()
    private val mainClassifier = MainImageClassifier(context)

    private var embedder: ImageEmbedder? = null
    private var embedderFailure: String? = null

    suspend fun ensureInitialized() {
        initMutex.withLock {
            mainClassifier.ensureInitialized()

            if (embedder != null || embedderFailure != null) {
                return
            }

            if (embedder == null && embedderFailure == null) {
                embedder = runCatching { createImageEmbedder() }
                    .onFailure {
                        embedderFailure = it.message ?: "ImageEmbedder 초기화 실패"
                        Log.w(LogTag, "MediaPipe embedder init failed", it)
                    }
                    .getOrNull()
            }
        }
    }

    fun mainImageClassifier(): MainImageClassifier = mainClassifier

    fun imageEmbedderOrNull(): ImageEmbedder? = embedder

    override fun runtimeStatus(): List<ModelRuntimeStatus> {
        return mainClassifier.runtimeStatus() + listOf(
            ModelRuntimeStatus(
                modelId = "mediapipe-image-embedder",
                displayName = "MediaPipe ImageEmbedder",
                version = "mobilenet_v3_small/1",
                role = "스타일 유사도와 로컬 프로토타입 매칭",
                assetPath = ImageEmbedderAssetPath,
                available = assetExists(context, ImageEmbedderAssetPath),
                loaded = embedder != null,
                summary = embedderFailure ?: if (embedder != null) {
                    "MobileNet V3 Small 임베더를 로드했다."
                } else {
                    "아직 로드되지 않았지만 assets에 모델이 있다."
                }
            )
        )
    }

    private fun createImageEmbedder(): ImageEmbedder {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath(ImageEmbedderAssetPath)
            .build()
        val options = ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(baseOptions)
            .setL2Normalize(true)
            .build()

        return ImageEmbedder.createFromOptions(context, options)
    }

    companion object {
        const val ImageEmbedderAssetPath: String = "mobilenet_v3_small.tflite"
    }
}

internal class MediaPipeSemanticInferenceEngine(
    private val context: Context,
    private val modelProvider: MediaPipeVisionModelProvider,
    private val taxonomy: VisionTaxonomy
) : InferenceEngine<SemanticInferenceResult> {
    private val prototypeMutex = Mutex()
    private val frameCacheMutex = Mutex()
    private var prototypeVectors: Map<String, FloatArray> = emptyMap()
    private val classifierTagCache = linkedLruMap<String, MainClassifierOutput>(96)
    private val embeddingCache = linkedLruMap<String, FloatArray>(96)

    override fun runtimeStatus(): List<ModelRuntimeStatus> = modelProvider.runtimeStatus()

    override suspend fun infer(request: MediaInferenceRequest): SemanticInferenceResult? {
        modelProvider.ensureInitialized()
        val classifier = modelProvider.mainImageClassifier()
        val embedder = modelProvider.imageEmbedderOrNull()
        val classifierLoaded = classifier.isLoaded()
        val embedderLoaded = embedder != null

        if (!classifierLoaded && !embedderLoaded) {
            return SemanticInferenceResult(
                primaryScores = emptyMap(),
                secondaryScores = emptyMap(),
                classifierTags = emptyList(),
                prototypeTags = emptyList(),
                reasoning = listOf("MediaPipe 모델을 로드하지 못해 의미 분류를 건너뛰었다."),
                reducedMode = true,
                classifierLoaded = false,
                embedderLoaded = false
            )
        }

        val classifierFrameScores = mutableListOf<Map<String, Float>>()
        val primaryFrameScores = mutableListOf<Map<String, Float>>()
        val secondaryFrameScores = mutableListOf<Map<String, Float>>()
        val prototypeFrameScores = mutableListOf<Map<String, Float>>()
        val reasoning = mutableListOf<String>()
        val classifierRuntimeLabels = linkedSetOf<String>()
        val frameSummaries = mutableListOf<FrameDebugInfo>()
        var classifierInvoked = false
        var embedderInvoked = false

        if (embedder != null) {
            ensurePrototypeVectors(embedder)
        } else {
            reasoning += "ImageEmbedder를 로드하지 못해 스타일/프로토타입 유사도 보강을 건너뛰었다."
        }
        if (!classifierLoaded) {
            reasoning += "주 분류기를 로드하지 못해 Lite4 classifier 태그 없이 embedder/ML Kit 보조 신호에 더 의존한다."
        }

        request.frames.forEach { frame ->
            val cacheKey = buildFrameCacheKey(request.mediaItem, frame)
            val styleMetrics = StyleMetrics.from(frame.bitmap)
            val framePrimaryAccumulator = mutableMapOf<String, Float>()
            val frameSecondaryAccumulator = mutableMapOf<String, Float>()
            val frameClassifierAccumulator = mutableMapOf<String, Float>()
            val framePrototypeAccumulator = mutableMapOf<String, Float>()
            val frameNotes = mutableListOf<String>()

            val classifierOutput = cachedClassifierTags(cacheKey) {
                classifier.classify(frame.bitmap)
            }
            classifierInvoked = classifierInvoked || classifierLoaded
            val classifierTags = classifierOutput?.tags.orEmpty()
            classifierOutput?.backendLabel
                ?.takeIf { classifierTags.isNotEmpty() }
                ?.let(classifierRuntimeLabels::add)

            classifierTags.forEach { tag ->
                frameClassifierAccumulator.merge(tag.label.lowercase(), tag.score, Float::plus)
            }

            applyProfileKeywordScores(
                profiles = taxonomy.primaryCategories,
                classifierTags = classifierTags,
                styleMetrics = styleMetrics,
                accumulator = framePrimaryAccumulator
            )
            applyProfileKeywordScores(
                profiles = taxonomy.secondaryCategories,
                classifierTags = classifierTags,
                styleMetrics = styleMetrics,
                accumulator = frameSecondaryAccumulator
            )

            val imageEmbedding = if (embedder != null) {
                embedderInvoked = true
                cachedEmbedding(cacheKey) {
                    val mpImage = BitmapImageBuilder(frame.bitmap).build()
                    embedder.embed(mpImage)
                        .embeddingResult()
                        .embeddings()
                        .firstOrNull()
                        ?.floatEmbedding()
                }
            } else {
                null
            }

            if (imageEmbedding != null && prototypeVectors.isNotEmpty()) {
                val prototypeScores = prototypeVectors.mapValues { (_, prototypeVector) ->
                    cosineSimilarity(imageEmbedding, prototypeVector)
                }

                prototypeScores
                    .filterValues { it.isFinite() }
                    .forEach { (prototypeId, score) ->
                        framePrototypeAccumulator[prototypeId] = score
                        taxonomy.primaryCategories
                            .filter { it.prototypeId == prototypeId }
                            .forEach { profile ->
                                framePrimaryAccumulator.merge(
                                    profile.level1,
                                    ((score + 1f) / 2f).coerceAtLeast(0f) * 0.85f,
                                    Float::plus
                                )
                            }
                        taxonomy.secondaryCategories
                            .filter { it.prototypeId == prototypeId }
                            .forEach { profile ->
                                frameSecondaryAccumulator.merge(
                                    profile.level2,
                                    ((score + 1f) / 2f).coerceAtLeast(0f) * 0.6f,
                                    Float::plus
                                )
                            }
                    }
            }

            if (styleMetrics.highSaturationAnimeLike) {
                frameNotes += "고채도/선명한 일러스트풍 스타일 신호"
            }
            if (styleMetrics.rectilinearUiLike) {
                frameNotes += "직선/UI 패턴이 두드러짐"
            }
            if (styleMetrics.brightPageLike) {
                frameNotes += "밝은 문서/페이지형 배경"
            }

            val framePrototypeTags = framePrototypeAccumulator.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { ScoredSignal(prototypeDisplayName(it.key), it.value) }

            val frameTagSummary = buildList {
                classifierOutput?.backendLabel?.takeIf { classifierTags.isNotEmpty() }?.let { backend ->
                    add("분류기:$backend")
                }
                if (classifierTags.isNotEmpty()) {
                    add("Lite4 ${classifierTags.take(3).joinToString { "${it.label}:${formatScore(it.score)}" }}")
                }
                if (framePrototypeTags.isNotEmpty()) {
                    add("임베더 ${framePrototypeTags.take(2).joinToString { "${it.label}:${formatScore(it.score)}" }}")
                }
            }.joinToString(" · ")

            frameSummaries += FrameDebugInfo(
                frameLabel = frame.label,
                timestampMs = frame.timestampMs,
                summary = frameTagSummary,
                tags = (
                    classifierTags.take(4) +
                        framePrototypeTags.take(2).map { signal ->
                            ScoredSignal("임베딩:${signal.label}", signal.score)
                        }
                    ).take(6),
                notes = frameNotes
            )

            classifierFrameScores += frameClassifierAccumulator.toMap()
            primaryFrameScores += framePrimaryAccumulator.toMap()
            secondaryFrameScores += frameSecondaryAccumulator.toMap()
            prototypeFrameScores += framePrototypeAccumulator.toMap()
        }

        val normalizedPrimary = aggregateFrameScores(primaryFrameScores)
        val normalizedSecondary = aggregateFrameScores(secondaryFrameScores)
        val normalizedClassifier = aggregateFrameScores(classifierFrameScores)
            .entries
            .sortedByDescending { it.value }
            .take(8)
            .map { ScoredSignal(it.key, it.value) }
        val normalizedPrototype = aggregateFrameScores(prototypeFrameScores)
            .entries
            .sortedByDescending { it.value }
            .take(8)
            .map { ScoredSignal(prototypeDisplayName(it.key), it.value) }

        if (classifierRuntimeLabels.isNotEmpty()) {
            reasoning += "Lite4 분류기 백엔드: ${classifierRuntimeLabels.joinToString()}"
        }
        if (normalizedClassifier.isNotEmpty()) {
            reasoning += "Lite4 classifier 상위 태그: ${normalizedClassifier.take(4).joinToString { "${it.label}:${formatScore(it.score)}" }}"
        }
        if (normalizedPrototype.isNotEmpty()) {
            reasoning += "MediaPipe embedder 프로토타입 유사도: ${normalizedPrototype.take(4).joinToString { "${it.label}:${formatScore(it.score)}" }}"
        }

        return SemanticInferenceResult(
            primaryScores = normalizedPrimary,
            secondaryScores = normalizedSecondary,
            classifierTags = normalizedClassifier,
            prototypeTags = normalizedPrototype,
            reasoning = reasoning,
            reducedMode = false,
            classifierLoaded = classifierLoaded,
            classifierInvoked = classifierInvoked,
            embedderLoaded = embedderLoaded,
            embedderInvoked = embedderInvoked,
            frameSummaries = frameSummaries
        )
    }

    private fun applyProfileKeywordScores(
        profiles: List<TaxonomyProfile>,
        classifierTags: List<ScoredSignal>,
        styleMetrics: StyleMetrics,
        accumulator: MutableMap<String, Float>
    ) {
        profiles.forEach { profile ->
            var score = 0f
            classifierTags.forEach { tag ->
                if (profile.classifierKeywords.any { keyword -> tag.label.contains(keyword, ignoreCase = true) }) {
                    score += tag.score * 1.35f
                }
            }

            if (profile.prototypeId == "anime" && styleMetrics.highSaturationAnimeLike) {
                score += 0.35f
            }
            if (profile.prototypeId == "illustration" && styleMetrics.highSaturationAnimeLike) {
                score += 0.22f
            }
            if (profile.prototypeId == "screenshot" && styleMetrics.rectilinearUiLike) {
                score += 0.3f
            }
            if (profile.prototypeId == "document" && styleMetrics.brightPageLike) {
                score += 0.25f
            }

            if (score > 0f) {
                accumulator.merge(profile.outputLabel, score, Float::plus)
            }
        }
    }

    private suspend fun ensurePrototypeVectors(embedder: ImageEmbedder) {
        prototypeMutex.withLock {
            if (prototypeVectors.isNotEmpty()) {
                return
            }

            val vectors = taxonomy.prototypeIds.associateWithNotNull { prototypeId ->
                val bitmap = PrototypeBitmapFactory.create(prototypeId, PrototypeBitmapSize)
                try {
                    val mpImage = BitmapImageBuilder(bitmap).build()
                    val embedding = embedder.embed(mpImage)
                        .embeddingResult()
                        .embeddings()
                        .firstOrNull()
                        ?.floatEmbedding()

                    embedding?.let { prototypeId to it }
                } finally {
                    bitmap.recycle()
                }
            }

            prototypeVectors = vectors
        }
    }

    private suspend fun cachedClassifierTags(
        cacheKey: String,
        loader: suspend () -> MainClassifierOutput?
    ): MainClassifierOutput? {
        frameCacheMutex.withLock {
            classifierTagCache[cacheKey]?.let { cached -> return cached }
        }

        val loaded = loader()
        loaded?.takeIf { it.tags.isNotEmpty() }?.let { output ->
            frameCacheMutex.withLock {
                classifierTagCache[cacheKey] = output
            }
        }
        return loaded
    }

    private suspend fun cachedEmbedding(
        cacheKey: String,
        loader: suspend () -> FloatArray?
    ): FloatArray? {
        frameCacheMutex.withLock {
            embeddingCache[cacheKey]?.let { cached ->
                return cached.clone()
            }
        }

        val loaded = loader()
        loaded?.let { embedding ->
            frameCacheMutex.withLock {
                embeddingCache[cacheKey] = embedding.clone()
            }
        }
        return loaded
    }

    private fun buildFrameCacheKey(
        mediaItem: MediaItem,
        frame: SampledFrame
    ): String {
        return buildString {
            append(mediaItem.recordId)
            append('|')
            append(frame.timestampMs ?: -1L)
            append('|')
            append(frame.label)
            append('|')
            append(mediaItem.dateModifiedEpochSeconds)
        }
    }

    private fun prototypeDisplayName(prototypeId: String): String {
        return taxonomy.primaryCategories.firstOrNull { it.prototypeId == prototypeId }?.displayName
            ?: taxonomy.secondaryCategories.firstOrNull { it.prototypeId == prototypeId }?.displayName
            ?: prototypeId
    }
}

internal class MlKitAuxiliaryInferenceEngine(
    private val context: Context
) : InferenceEngine<MlKitAuxiliaryResult> {
    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
    }

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val labeler by lazy {
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.25f)
            .build()
        ImageLabeling.getClient(options)
    }

    override fun runtimeStatus(): List<ModelRuntimeStatus> {
        return listOf(
            ModelRuntimeStatus(
                modelId = "mlkit-face",
                displayName = "ML Kit Face Detection",
                version = "bundled",
                role = "얼굴/셀카 보조 신호",
                available = true,
                loaded = true,
                summary = "얼굴 수와 중앙 인물 강도를 계산한다."
            ),
            ModelRuntimeStatus(
                modelId = "mlkit-text",
                displayName = "ML Kit Text Recognition",
                version = "bundled-latin",
                role = "문서/영수증/스크린샷 텍스트 보조 신호",
                available = true,
                loaded = true,
                summary = "OCR 텍스트와 UI/영수증 키워드를 추출한다."
            ),
            ModelRuntimeStatus(
                modelId = "mlkit-label",
                displayName = "ML Kit Image Labeling",
                version = "17.0.9",
                role = "보조 태그 추출",
                available = true,
                loaded = true,
                summary = "주 분류가 아니라 보조 태그와 힌트만 제공한다."
            )
        )
    }

    override suspend fun infer(request: MediaInferenceRequest): MlKitAuxiliaryResult {
        val selectedFrames = when {
            request.frames.size <= 3 -> request.frames
            else -> listOf(
                request.frames.first(),
                request.frames[request.frames.lastIndex / 2],
                request.frames.last()
            ).distinctBy { it.label to it.timestampMs }
        }

        var maxFaceCount = 0
        var centeredFaceScore = 0f
        var maxFaceAreaRatio = 0f
        var maxFaceCenteredness = 0f
        var longestText = ""
        var textLength = 0
        var textLineCount = 0
        val uiKeywordHits = linkedSetOf<String>()
        val receiptKeywordHits = linkedSetOf<String>()
        val labelAccumulator = mutableMapOf<String, Float>()
        val reasoning = mutableListOf<String>()
        val frameSummaries = mutableListOf<FrameDebugInfo>()
        val faceInvoked = selectedFrames.isNotEmpty()
        val textInvoked = selectedFrames.isNotEmpty()
        val labelInvoked = selectedFrames.isNotEmpty()

        selectedFrames.forEach { frame ->
            val image = InputImage.fromBitmap(frame.bitmap, 0)
            var frameFaceCount = 0
            var frameMaxFaceArea = 0f
            var frameMaxCentered = 0f
            var frameTextLength = 0
            var frameTextLines = 0
            val frameKeywordNotes = mutableListOf<String>()
            val frameLabelAccumulator = mutableMapOf<String, Float>()

            runCatching {
                faceDetector.process(image).awaitResult()
            }.getOrDefault(emptyList()).also { faces ->
                maxFaceCount = max(maxFaceCount, faces.size)
                frameFaceCount = faces.size
                val frameCentered = faces.maxOfOrNull { face ->
                    val bounds = face.boundingBox
                    val centerX = bounds.exactCenterX() / frame.bitmap.width.toFloat()
                    val centerY = bounds.exactCenterY() / frame.bitmap.height.toFloat()
                    val areaRatio = (bounds.width() * bounds.height()).toFloat() /
                        (frame.bitmap.width * frame.bitmap.height).toFloat()
                    val centeredness = 1f - ((abs(centerX - 0.5f) + abs(centerY - 0.5f)) / 1f)
                    frameMaxFaceArea = max(frameMaxFaceArea, areaRatio)
                    frameMaxCentered = max(frameMaxCentered, centeredness.coerceAtLeast(0f))
                    maxFaceAreaRatio = max(maxFaceAreaRatio, areaRatio)
                    maxFaceCenteredness = max(maxFaceCenteredness, centeredness.coerceAtLeast(0f))
                    (areaRatio * 1.8f + centeredness).coerceAtLeast(0f)
                } ?: 0f
                centeredFaceScore = max(centeredFaceScore, frameCentered)
            }

            runCatching {
                textRecognizer.process(image).awaitResult()
            }.getOrNull()?.let { textResult ->
                val fullText = textResult.text.orEmpty().trim()
                frameTextLength = fullText.length
                frameTextLines = textResult.textBlocks.sumOf { block -> block.lines.size }
                if (fullText.length > textLength) {
                    longestText = fullText
                    textLength = fullText.length
                }
                textLineCount = max(textLineCount, frameTextLines)
                val frameUiKeywords = ScreenTextKeywords.filter { keyword -> fullText.contains(keyword, ignoreCase = true) }
                val frameReceiptKeywords = ReceiptTextKeywords.filter { keyword -> fullText.contains(keyword, ignoreCase = true) }
                uiKeywordHits += frameUiKeywords
                receiptKeywordHits += frameReceiptKeywords
                if (frameUiKeywords.isNotEmpty()) {
                    frameKeywordNotes += "UI:${frameUiKeywords.joinToString()}"
                }
                if (frameReceiptKeywords.isNotEmpty()) {
                    frameKeywordNotes += "영수증:${frameReceiptKeywords.joinToString()}"
                }
            }

            runCatching {
                labeler.process(image).awaitResult()
            }.getOrDefault(emptyList()).forEach { label ->
                labelAccumulator.merge(label.text.lowercase(), label.confidence, Float::plus)
                frameLabelAccumulator.merge(label.text.lowercase(), label.confidence, Float::plus)
            }

            frameSummaries += FrameDebugInfo(
                frameLabel = frame.label,
                timestampMs = frame.timestampMs,
                summary = buildString {
                    append("얼굴 ")
                    append(frameFaceCount)
                    append("개")
                    if (frameTextLength > 0) {
                        append(" · OCR ")
                        append(frameTextLength)
                        append("자/")
                        append(frameTextLines)
                        append("줄")
                    }
                    if (frameLabelAccumulator.isNotEmpty()) {
                        append(" · ML Kit 라벨 ")
                        append(
                            frameLabelAccumulator.entries
                                .sortedByDescending { it.value }
                                .take(2)
                                .joinToString { "${it.key}:${formatScore(it.value)}" }
                        )
                    }
                },
                tags = frameLabelAccumulator.entries
                    .sortedByDescending { it.value }
                    .take(4)
                    .map { ScoredSignal(it.key, it.value) },
                notes = buildList {
                    if (frameMaxFaceArea > 0f) {
                        add("최대 얼굴 비율 ${formatScore(frameMaxFaceArea)} / 중앙도 ${formatScore(frameMaxCentered)}")
                    }
                    addAll(frameKeywordNotes)
                }
            )
        }

        if (maxFaceCount > 0) {
            reasoning += "ML Kit 얼굴 감지: 최대 ${maxFaceCount}명"
        }
        if (textLength > 0) {
            reasoning += "ML Kit OCR 텍스트 길이 ${textLength}자, 줄 수 ${textLineCount}"
        }

        return MlKitAuxiliaryResult(
            faceCount = maxFaceCount,
            centeredFaceScore = centeredFaceScore,
            maxFaceAreaRatio = maxFaceAreaRatio,
            maxFaceCenteredness = maxFaceCenteredness,
            recognizedText = longestText.take(500),
            textLength = textLength,
            textLineCount = textLineCount,
            uiKeywordHits = uiKeywordHits.toList(),
            receiptKeywordHits = receiptKeywordHits.toList(),
            auxiliaryTags = labelAccumulator
                .entries
                .sortedByDescending { it.value }
                .take(8)
                .map { ScoredSignal(it.key, it.value / max(selectedFrames.size, 1)) },
            reasoning = reasoning,
            faceInvoked = faceInvoked,
            textInvoked = textInvoked,
            labelInvoked = labelInvoked,
            frameSummaries = frameSummaries
        )
    }
}

internal class FolderNameGenerator(
    private val taxonomy: VisionTaxonomy
) {
    fun chooseLevel3(
        level1: String,
        searchableText: String,
        auxiliary: MlKitAuxiliaryResult,
        fallback: ClassificationLabels?,
        confidence: Float
    ): Pair<String, List<ScoredSignal>> {
        val seriesCandidates = taxonomy.seriesCatalog.mapNotNull { entry ->
            if (entry.allowedLevel1.isNotEmpty() && level1 !in entry.allowedLevel1) {
                return@mapNotNull null
            }
            val score = keywordScore(searchableText, entry.keywords, 0.34f) +
                keywordScore(auxiliary.recognizedText, entry.keywords, 0.26f)
            if (score > 0f) {
                ScoredSignal(entry.name.take(24), score)
            } else {
                null
            }
        }.sortedByDescending { it.score }

        val selected = seriesCandidates.firstOrNull()
            ?.takeIf { it.score >= SeriesCandidateThreshold && confidence >= FolderNameConfidenceThreshold }
            ?.label
            ?: fallback?.level3
                ?.trim()
                ?.takeIf { it.isNotBlank() && confidence >= FolderNameConfidenceThreshold }
            ?: ""

        return selected to seriesCandidates.take(5)
    }

    companion object {
        private const val SeriesCandidateThreshold = 0.42f
        private const val FolderNameConfidenceThreshold = 0.46f
    }
}

internal class ClassificationPipeline(
    private val taxonomy: VisionTaxonomy,
    private val semanticEngine: MediaPipeSemanticInferenceEngine,
    private val clipEngine: MobileClipSemanticInferenceEngine,
    private val mlKitEngine: MlKitAuxiliaryInferenceEngine,
    private val fallbackEngine: ClassificationEngine
) : ModelProvider {
    private val folderNameGenerator = FolderNameGenerator(taxonomy)

    override fun runtimeStatus(): List<ModelRuntimeStatus> {
        return semanticEngine.runtimeStatus() +
            clipEngine.runtimeStatus() +
            mlKitEngine.runtimeStatus() +
            fallbackEngine.runtimeStatus().models
    }

    suspend fun classify(
        request: MediaInferenceRequest,
        engineInfo: ClassificationEngineInfo
    ): ClassificationSuggestion? {
        val fallback = fallbackEngine.classify(request.mediaItem)
        val semantic = semanticEngine.infer(request)
        val clipSemantic = clipEngine.infer(request)
        val auxiliary = mlKitEngine.infer(request)
        val searchableText = buildSearchableText(request.mediaItem, auxiliary)
        val reasoning = mutableListOf<String>()
        val primaryScores = taxonomy.primaryCategories.associate { it.level1 to 0f }.toMutableMap()
        val secondaryScores = taxonomy.secondaryCategories.associate { it.level2 to 0f }.toMutableMap()

        semantic?.primaryScores.orEmpty().forEach { (label, score) ->
            primaryScores.merge(label, score, Float::plus)
        }
        semantic?.secondaryScores.orEmpty().forEach { (label, score) ->
            secondaryScores.merge(label, score, Float::plus)
        }
        clipSemantic?.primaryScores.orEmpty().forEach { (label, score) ->
            primaryScores.merge(label, score * MobileClipPrimaryWeight, Float::plus)
        }
        clipSemantic?.secondaryScores.orEmpty().forEach { (label, score) ->
            secondaryScores.merge(label, score * MobileClipSecondaryWeight, Float::plus)
        }
        reasoning += semantic?.reasoning.orEmpty()
        reasoning += clipSemantic?.reasoning.orEmpty()
        reasoning += auxiliary.reasoning

        taxonomy.primaryCategories.forEach { profile ->
            val textMatches = keywordScore(searchableText, profile.pathKeywords, 0.18f) +
                keywordScore(searchableText, profile.textKeywords, 0.28f)
            if (textMatches > 0f) {
                primaryScores.merge(profile.level1, textMatches, Float::plus)
            }
        }
        taxonomy.secondaryCategories.forEach { profile ->
            val textMatches = keywordScore(searchableText, profile.pathKeywords, 0.18f) +
                keywordScore(searchableText, profile.textKeywords, 0.24f)
            if (textMatches > 0f) {
                secondaryScores.merge(profile.level2, textMatches, Float::plus)
            }
        }
        applyAuxiliaryLabelScores(
            primaryScores = primaryScores,
            secondaryScores = secondaryScores,
            auxiliary = auxiliary,
            reasoning = reasoning
        )

        val hasStrongUiSignals = auxiliary.uiKeywordHits.size >= 2 ||
            searchableText.containsAny("screenshot", "screen_shot", "screen-shot", "screenrecord", "screen_record", "캡처")
        val hasStrongDocumentSignals = auxiliary.receiptKeywordHits.isNotEmpty() ||
            auxiliary.textLineCount >= 14 ||
            (auxiliary.textLength >= 120 && !hasStrongUiSignals)
        val hasGameSignals = searchableText.containsAny(
            "game",
            "gaming",
            "steam",
            "genshin",
            "valorant",
            "minecraft",
            "inventory",
            "quest",
            "raid",
            "battle",
            "게임"
        ) || (semantic?.primaryScores?.get("게임 관련") ?: 0f) >= 0.35f ||
            (clipSemantic?.primaryScores?.get("게임 관련") ?: 0f) >= 0.22f

        if (auxiliary.faceCount > 0 && !hasStrongUiSignals && !hasStrongDocumentSignals) {
            when {
                auxiliary.maxFaceAreaRatio >= StrongFaceAreaRatio || auxiliary.centeredFaceScore >= StrongCenteredFaceScore -> {
                    primaryScores.merge(
                        "사람",
                        0.45f + auxiliary.faceCount * 0.1f + auxiliary.maxFaceAreaRatio * 1.25f,
                        Float::plus
                    )
                    reasoning += "얼굴이 충분히 크거나 중앙에 있어 사람 중심 가중치를 올렸다."
                }
                auxiliary.maxFaceAreaRatio >= MediumFaceAreaRatio || auxiliary.centeredFaceScore >= MediumCenteredFaceScore -> {
                    primaryScores.merge(
                        "사람",
                        0.2f + auxiliary.maxFaceAreaRatio * 0.9f,
                        Float::plus
                    )
                    reasoning += "얼굴이 보이지만 비중이 제한적이라 사람 가중치를 약하게만 더했다."
                }
                else -> reasoning += "작거나 주변부 얼굴만 보여 사람 중심 가중치를 크게 올리지 않았다."
            }
        } else if (auxiliary.faceCount > 0) {
            reasoning += "문서/UI 신호가 강해 얼굴 기반 사람 가중치를 억제했다."
        }
        if (
            auxiliary.faceCount > 0 &&
            auxiliary.maxFaceAreaRatio >= StrongFaceAreaRatio &&
            auxiliary.centeredFaceScore >= StrongCenteredFaceScore &&
            !hasStrongUiSignals &&
            !hasStrongDocumentSignals
        ) {
            primaryScores.merge(
                "셀카",
                0.48f + auxiliary.centeredFaceScore * 0.24f + auxiliary.maxFaceAreaRatio * 1.2f,
                Float::plus
            )
        }
        if (auxiliary.textLength >= 30) {
            primaryScores.merge("문서", 0.45f, Float::plus)
            primaryScores.merge("스크린샷", 0.25f, Float::plus)
        }
        if (auxiliary.textLineCount >= 12) {
            primaryScores.merge("문서", 0.35f, Float::plus)
        }
        if (auxiliary.receiptKeywordHits.isNotEmpty()) {
            primaryScores.merge("영수증", 0.95f, Float::plus)
            primaryScores.merge("문서", 0.25f, Float::plus)
        }
        if (auxiliary.uiKeywordHits.isNotEmpty()) {
            primaryScores.merge("스크린샷", 0.7f, Float::plus)
            primaryScores.merge("게임 관련", 0.22f, Float::plus)
            secondaryScores.merge("UI 중심", 0.55f, Float::plus)
            secondaryScores.merge("로고/타이틀 화면", 0.18f, Float::plus)
        }

        applyDominanceHeuristics(
            primaryScores = primaryScores,
            secondaryScores = secondaryScores,
            auxiliary = auxiliary,
            searchableText = searchableText,
            semantic = semantic,
            clipSemantic = clipSemantic,
            hasStrongUiSignals = hasStrongUiSignals,
            hasStrongDocumentSignals = hasStrongDocumentSignals,
            hasGameSignals = hasGameSignals,
            reasoning = reasoning
        )

        if (searchableText.containsAny("subtitle", "captions", "자막", "대사")) {
            secondaryScores.merge("대사/자막 중심", 0.55f, Float::plus)
        }
        if (searchableText.containsAny("battle", "boss", "combat", "raid", "weapon", "전투")) {
            secondaryScores.merge("전투 화면", 0.52f, Float::plus)
        }
        if (searchableText.containsAny("fanart", "pixiv", "fan_art")) {
            secondaryScores.merge("팬아트 가능성", 0.5f, Float::plus)
        }

        fallback?.labels?.level1?.takeIf { it.isNotBlank() }?.let { label ->
            primaryScores.merge(label, 0.22f, Float::plus)
            reasoning += "파일명/경로 기반 fallback prior를 ${label}에 더했다."
        }
        fallback?.labels?.level2?.takeIf { it.isNotBlank() }?.let { label ->
            secondaryScores.merge(label, 0.14f, Float::plus)
        }

        val sortedPrimary = primaryScores.entries
            .sortedByDescending { it.value }
            .map { ScoredSignal(it.key, it.value) }
        val topScore = sortedPrimary.firstOrNull()?.score ?: 0f
        val secondScore = sortedPrimary.getOrNull(1)?.score ?: 0f
        val topClassifierScore = semantic?.classifierTags?.firstOrNull()?.score ?: 0f
        val topPrototypeScore = semantic?.prototypeTags?.firstOrNull()?.score ?: 0f
        val topClipScore = clipSemantic?.tags?.firstOrNull()?.score ?: 0f
        val semanticSupportScore = max(
            max(
                topClassifierScore,
                ((topPrototypeScore + 1f) / 2f).coerceIn(0f, 1f)
            ),
            topClipScore
        )
        val confidence = (
            topScore.coerceIn(0f, 1.15f) * TopScoreWeight +
                (topScore - secondScore).coerceAtLeast(0f).coerceIn(0f, 1f) * ScoreMarginWeight +
                semanticSupportScore * SemanticSupportWeight
            ).coerceIn(0f, 1f)

        val provisionalLevel1 = sortedPrimary.firstOrNull()?.label
            ?.takeIf { topScore >= Level1MinimumScore }
            ?: fallback?.labels?.level1
                ?.takeIf { it.isNotBlank() }
            ?: "기타"

        val shouldReview = shouldUseReviewFallback(
            candidateLevel1 = provisionalLevel1,
            topScore = topScore,
            secondScore = secondScore,
            classifierScore = topClassifierScore,
            prototypeScore = topPrototypeScore,
            clipScore = topClipScore,
            auxiliary = auxiliary,
            hasStrongUiSignals = hasStrongUiSignals,
            hasStrongDocumentSignals = hasStrongDocumentSignals
        )

        val level1: String
        val level2: String
        val seriesCandidates: List<ScoredSignal>
        val level3: String

        if (shouldReview) {
            level1 = "기타"
            level2 = "검토 필요"
            val generated = folderNameGenerator.chooseLevel3(
                level1 = level1,
                searchableText = searchableText,
                auxiliary = auxiliary,
                fallback = null,
                confidence = 0f
            )
            seriesCandidates = generated.second
            level3 = ""
            reasoning += "Lite4 일반 분류기 신뢰도가 낮아 앱 전용 의미를 단정하지 않고 기타/검토 필요로 폴백했다."
        } else {
            level1 = provisionalLevel1
            level2 = chooseLevel2(
                level1 = level1,
                secondaryScores = secondaryScores,
                auxiliary = auxiliary,
                fallback = fallback?.labels
            )
            val generated = folderNameGenerator.chooseLevel3(
                level1 = level1,
                searchableText = searchableText,
                auxiliary = auxiliary,
                fallback = fallback?.labels,
                confidence = confidence
            )
            seriesCandidates = generated.second
            level3 = generated.first
        }

        reasoning += buildList {
            add("최종 1차 분류: $level1")
            if (level2.isNotBlank()) {
                add("최종 2차 분류: $level2")
            }
            if (level3.isNotBlank()) {
                add("작품/시리즈 후보 채택: $level3")
            } else {
                add("작품/시리즈는 미확정으로 남겼다.")
            }
        }

        val debugInfo = ClassificationDebugInfo(
            confidence = confidence,
            reducedMode = semantic?.reducedMode != false && clipSemantic?.reducedMode != false,
            fallbackUsed = semantic?.reducedMode != false && clipSemantic?.reducedMode != false,
            usedEngines = buildList {
                if (semantic?.classifierInvoked == true) add("main-image-classifier")
                if (semantic?.embedderInvoked == true) add("mediapipe-image-embedder")
                if (clipSemantic?.invoked == true) add("mobileclip-vision-encoder")
                if (auxiliary.faceInvoked) add("mlkit-face-detection")
                if (auxiliary.textInvoked) add("mlkit-text-recognition")
                if (auxiliary.labelInvoked) add("mlkit-image-labeling")
                if (semantic?.reducedMode != false && clipSemantic?.reducedMode != false) add("rule-based-fallback")
            },
            reasoning = reasoning,
            finalScores = sortedPrimary.take(6),
            seriesCandidates = seriesCandidates.take(5),
            frameSummaries = mergeFrameDebugSummaries(
                semanticFrames = mergeFrameDebugSummaries(
                    semanticFrames = semantic?.frameSummaries.orEmpty(),
                    mlKitFrames = clipSemantic?.frameSummaries.orEmpty()
                ),
                mlKitFrames = auxiliary.frameSummaries
            ),
            modelOutputs = buildList {
                semantic?.let {
                    add(
                        ModelDebugInfo(
                            modelId = "main-image-classifier",
                            displayName = "Main Image Classifier (EfficientNet-Lite4)",
                            loaded = it.classifierLoaded,
                            invoked = it.classifierInvoked,
                            summary = if (!it.classifierLoaded) {
                                "Lite4 주 분류기를 로드하지 못했다."
                            } else {
                                "대표 프레임별 Lite4 top-k 태그를 집계했다."
                            },
                            tags = it.classifierTags.take(8),
                            notes = listOfNotNull(
                                it.reasoning.firstOrNull { reason -> reason.contains("Lite4 분류기 백엔드") }
                            )
                        )
                    )
                    add(
                        ModelDebugInfo(
                            modelId = "mediapipe-image-embedder",
                            displayName = "MediaPipe ImageEmbedder",
                            loaded = it.embedderLoaded,
                            invoked = it.embedderInvoked,
                            summary = if (!it.embedderLoaded) {
                                "임베더를 로드하지 못해 프로토타입 유사도 보강을 건너뛰었다."
                            } else {
                                "로컬 프로토타입과의 유사도를 이용해 스타일/장르 점수를 보강했다."
                            },
                            tags = it.prototypeTags.take(8)
                        )
                    )
                }
                clipSemantic?.let {
                    add(
                        ModelDebugInfo(
                            modelId = "mobileclip-vision-encoder",
                            displayName = "MobileCLIP2-S0 Vision Encoder",
                            loaded = it.loaded,
                            invoked = it.invoked,
                            summary = if (it.reducedMode) {
                                "MobileCLIP vision encoder 또는 prompt catalog 를 못 불러와 의미 보강을 건너뛰었다."
                            } else {
                                "사전 계산한 프롬프트 임베딩과의 유사도로 의미 기반 후보를 추가했다."
                            },
                            tags = it.tags.take(8)
                        )
                    )
                }
                add(
                    ModelDebugInfo(
                        modelId = "mlkit-face",
                        displayName = "ML Kit Face Detection",
                        loaded = true,
                        invoked = auxiliary.faceInvoked,
                        summary = "얼굴 ${auxiliary.faceCount}개, 최대 얼굴 비율 ${formatScore(auxiliary.maxFaceAreaRatio)}, 중앙도 ${formatScore(auxiliary.maxFaceCenteredness)}",
                        notes = buildList {
                            if (auxiliary.faceCount == 0) {
                                add("얼굴이 검출되지 않았다.")
                            }
                            if (auxiliary.faceCount > 0 && auxiliary.maxFaceAreaRatio < MediumFaceAreaRatio) {
                                add("작은 얼굴만 보여 사람 중심으로 과도하게 밀지 않도록 제한했다.")
                            }
                        }
                    )
                )
                add(
                    ModelDebugInfo(
                        modelId = "mlkit-text",
                        displayName = "ML Kit Text Recognition",
                        loaded = true,
                        invoked = auxiliary.textInvoked,
                        summary = "OCR ${auxiliary.textLength}자 / ${auxiliary.textLineCount}줄, UI 힌트 ${auxiliary.uiKeywordHits.size}개, 영수증 힌트 ${auxiliary.receiptKeywordHits.size}개",
                        tags = (auxiliary.uiKeywordHits + auxiliary.receiptKeywordHits)
                            .distinct()
                            .take(6)
                            .map { ScoredSignal(it, 1f) },
                        notes = auxiliary.recognizedText
                            .takeIf { it.isNotBlank() }
                            ?.let { listOf(it.take(120)) }
                            .orEmpty()
                    )
                )
                add(
                    ModelDebugInfo(
                        modelId = "mlkit-label",
                        displayName = "ML Kit Image Labeling",
                        loaded = true,
                        invoked = auxiliary.labelInvoked,
                        summary = "일반 보조 태그를 약한 prior로만 반영했다.",
                        tags = auxiliary.auxiliaryTags.take(8)
                    )
                )
                fallback?.let {
                    add(
                        ModelDebugInfo(
                            modelId = "rule-based-fallback",
                            displayName = "규칙 기반 fallback",
                            loaded = true,
                            invoked = true,
                            summary = "추가 모델 실패나 낮은 신뢰도 상황에서 약한 prior로만 사용했다.",
                            tags = listOf(
                                ScoredSignal(it.labels.level1.ifBlank { "기타" }, it.confidence)
                            )
                        )
                    )
                }
            }
        )

        return ClassificationSuggestion(
            labels = ClassificationLabels(
                level1 = level1,
                level2 = level2,
                level3 = level3
            ).normalized(),
            confidence = confidence,
            engineInfo = engineInfo,
            debugInfo = debugInfo
        )
    }

    private fun chooseLevel2(
        level1: String,
        secondaryScores: Map<String, Float>,
        auxiliary: MlKitAuxiliaryResult,
        fallback: ClassificationLabels?
    ): String {
        val allowedLevel2 = when (level1) {
            "애니 관련", "게임 관련", "일러스트", "그림" -> taxonomy.secondaryCategories
                .filter { it.level1 == level1 }
                .map { it.level2 }
                .distinct()
            "스크린샷" -> listOf("UI 중심", "로고/타이틀 화면", "대사/자막 중심")
            else -> emptyList()
        }

        if (allowedLevel2.isEmpty()) {
            return when {
                auxiliary.textLength >= 40 && level1 == "문서" -> "문서 중심"
                auxiliary.faceCount > 0 && level1 in setOf("사람", "셀카") -> "인물 중심"
                else -> fallback?.level2.orEmpty()
            }
        }

        return secondaryScores.entries
            .filter { it.key in allowedLevel2 }
            .sortedByDescending { it.value }
            .firstOrNull()
            ?.takeIf { it.value >= 0.18f }
            ?.key
            ?: fallback?.level2
            ?.takeIf { it.isNotBlank() && it in allowedLevel2 }
            ?: allowedLevel2.first()
    }

    private fun buildSearchableText(
        mediaItem: MediaItem,
        auxiliary: MlKitAuxiliaryResult
    ): String {
        return listOf(
            mediaItem.displayName,
            mediaItem.relativePath.orEmpty(),
            mediaItem.mimeType.orEmpty(),
            auxiliary.recognizedText,
            auxiliary.uiKeywordHits.joinToString(" "),
            auxiliary.receiptKeywordHits.joinToString(" ")
        ).joinToString(separator = " ").lowercase()
    }

    private fun applyAuxiliaryLabelScores(
        primaryScores: MutableMap<String, Float>,
        secondaryScores: MutableMap<String, Float>,
        auxiliary: MlKitAuxiliaryResult,
        reasoning: MutableList<String>
    ) {
        val strongAuxiliaryTags = auxiliary.auxiliaryTags
            .filter { it.score >= AuxiliaryLabelMinimumScore }
            .take(6)
        if (strongAuxiliaryTags.isEmpty()) {
            return
        }

        taxonomy.primaryCategories.forEach { profile ->
            if (profile.level1 in setOf("사람", "셀카") &&
                auxiliary.faceCount == 0 &&
                auxiliary.maxFaceAreaRatio < MediumFaceAreaRatio
            ) {
                return@forEach
            }

            val score = strongAuxiliaryTags.sumOf { tag ->
                if (profile.classifierKeywords.any { keyword -> tag.label.contains(keyword, ignoreCase = true) }) {
                    (tag.score * AuxiliaryPrimaryWeight).toDouble()
                } else {
                    0.0
                }
            }.toFloat()

            if (score > 0f) {
                primaryScores.merge(profile.level1, score, Float::plus)
            }
        }

        taxonomy.secondaryCategories.forEach { profile ->
            if (profile.level2 == "캐릭터 중심" &&
                auxiliary.faceCount == 0 &&
                auxiliary.maxFaceAreaRatio < MediumFaceAreaRatio
            ) {
                return@forEach
            }

            val score = strongAuxiliaryTags.sumOf { tag ->
                if (profile.classifierKeywords.any { keyword -> tag.label.contains(keyword, ignoreCase = true) }) {
                    (tag.score * AuxiliarySecondaryWeight).toDouble()
                } else {
                    0.0
                }
            }.toFloat()

            if (score > 0f) {
                secondaryScores.merge(profile.level2, score, Float::plus)
            }
        }

        reasoning += "ML Kit 이미지 라벨 상위 태그를 약한 보조 prior로 반영했다: ${strongAuxiliaryTags.joinToString { "${it.label}:${formatScore(it.score)}" }}"
    }
}

internal fun applyDominanceHeuristics(
    primaryScores: MutableMap<String, Float>,
    secondaryScores: MutableMap<String, Float>,
    auxiliary: MlKitAuxiliaryResult,
    searchableText: String,
    semantic: SemanticInferenceResult?,
    clipSemantic: MobileClipSemanticResult?,
    hasStrongUiSignals: Boolean,
    hasStrongDocumentSignals: Boolean,
    hasGameSignals: Boolean,
    reasoning: MutableList<String>
) {
    if (hasStrongUiSignals) {
        primaryScores.merge("스크린샷", 1.05f, Float::plus)
        secondaryScores.merge("UI 중심", 0.75f, Float::plus)
        decreaseScore(primaryScores, "사람", 0.95f)
        decreaseScore(primaryScores, "셀카", 1.05f)
        if (auxiliary.receiptKeywordHits.isEmpty()) {
            decreaseScore(primaryScores, "문서", 0.35f)
        }
        reasoning += "UI/스크린샷 신호가 강해 사람/셀카 점수를 낮추고 스크린샷을 우선했다."
    }

    if (hasGameSignals && hasStrongUiSignals) {
        primaryScores.merge("게임 관련", 0.78f, Float::plus)
        secondaryScores.merge("게임 이미지", 0.62f, Float::plus)
        secondaryScores.merge("UI 중심", 0.34f, Float::plus)
        if (searchableText.containsAny("battle", "combat", "raid", "boss", "weapon", "전투")) {
            secondaryScores.merge("전투 화면", 0.48f, Float::plus)
        }
        reasoning += "게임 키워드와 UI 신호가 함께 보여 게임 스크린샷 쪽으로 가중치를 올렸다."
    }

    if (hasStrongDocumentSignals && !hasStrongUiSignals) {
        primaryScores.merge("문서", 1.2f, Float::plus)
        decreaseScore(primaryScores, "사람", 1.05f)
        decreaseScore(primaryScores, "셀카", 1.15f)
        decreaseScore(primaryScores, "애니 관련", 0.35f)
        decreaseScore(primaryScores, "게임 관련", 0.25f)
        if (auxiliary.receiptKeywordHits.isNotEmpty()) {
            primaryScores.merge("영수증", 1.25f, Float::plus)
        }
        reasoning += "OCR 텍스트/문서 신호가 강해 사람/캐릭터 계열보다 문서를 우선했다."
    }

    if ((semantic?.primaryScores?.get("문서") ?: 0f) >= 0.6f && auxiliary.textLength >= 60) {
        primaryScores.merge("문서", 0.55f, Float::plus)
    }

    val artSemanticScore = (semantic?.primaryScores?.get("일러스트") ?: 0f) +
        (semantic?.primaryScores?.get("애니 관련") ?: 0f) +
        (semantic?.primaryScores?.get("그림") ?: 0f)
    val clipArtPrimaryScore = (clipSemantic?.primaryScores?.get("일러스트") ?: 0f) +
        (clipSemantic?.primaryScores?.get("애니 관련") ?: 0f) +
        (clipSemantic?.primaryScores?.get("그림") ?: 0f)
    val artSecondaryScore = (semantic?.secondaryScores?.get("일반 일러스트") ?: 0f) +
        (semantic?.secondaryScores?.get("배경 중심") ?: 0f) +
        (semantic?.secondaryScores?.get("애니 이미지") ?: 0f)
    val clipArtSecondaryScore = (clipSemantic?.secondaryScores?.get("일반 일러스트") ?: 0f) +
        (clipSemantic?.secondaryScores?.get("배경 중심") ?: 0f) +
        (clipSemantic?.secondaryScores?.get("애니 이미지") ?: 0f)
    val weakFaceSubject = auxiliary.faceCount == 0 ||
        (auxiliary.maxFaceAreaRatio < MediumFaceAreaRatio &&
            auxiliary.centeredFaceScore < MediumCenteredFaceScore)
    val strongCharacterSubject = auxiliary.faceCount > 0 &&
        (auxiliary.maxFaceAreaRatio >= StrongFaceAreaRatio ||
            auxiliary.centeredFaceScore >= StrongCenteredFaceScore)
    val hasBackgroundArtworkSignal = searchableText.containsAny(
        "background",
        "scenery",
        "wallpaper",
        "landscape",
        "배경",
        "풍경"
    )
    val hasExplicitHumanTextSignal = searchableText.containsAny(
        "person",
        "portrait",
        "selfie",
        "family",
        "friends",
        "human",
        "인물",
        "사람",
        "셀카"
    )
    val hasHumanClassifierSignal = semantic?.classifierTags.orEmpty().any { tag ->
        tag.score >= 0.16f &&
            HumanClassifierKeywords.any { keyword -> tag.label.contains(keyword, ignoreCase = true) }
    }
    val hasScenicClassifierSignal = semantic?.classifierTags.orEmpty().any { tag ->
        tag.score >= 0.12f &&
            ScenicClassifierKeywords.any { keyword -> tag.label.contains(keyword, ignoreCase = true) }
    }
    val hasScenicClipSignal = clipSemantic?.tags.orEmpty().any { signal ->
        signal.score >= 0.16f && signal.label in ScenicClipLabels
    }
    val scenicPrimaryScore = (semantic?.primaryScores?.get("풍경") ?: 0f) +
        (clipSemantic?.primaryScores?.get("풍경") ?: 0f)
    val backgroundSecondaryScore = max(
        semantic?.secondaryScores?.get("배경 중심") ?: 0f,
        clipSemantic?.secondaryScores?.get("배경 중심") ?: 0f
    )
    val noFaceScenicArtworkCandidate = !hasStrongUiSignals &&
        !hasStrongDocumentSignals &&
        !hasGameSignals &&
        auxiliary.faceCount == 0 &&
        !hasExplicitHumanTextSignal &&
        !hasHumanClassifierSignal &&
        (
            scenicPrimaryScore >= 0.28f ||
                backgroundSecondaryScore >= 0.26f ||
                (artSemanticScore + clipArtPrimaryScore + artSecondaryScore + clipArtSecondaryScore) >= 0.92f ||
                hasBackgroundArtworkSignal ||
                hasScenicClassifierSignal ||
                hasScenicClipSignal
            )

    if (!hasStrongUiSignals && !hasStrongDocumentSignals && !hasGameSignals) {
        if ((artSemanticScore + artSecondaryScore) >= 0.9f && weakFaceSubject) {
            decreaseScore(primaryScores, "사람", 0.85f)
            decreaseScore(primaryScores, "셀카", 0.95f)
            secondaryScores.merge("배경 중심", 0.82f, Float::plus)
            secondaryScores.merge("일반 일러스트", 0.24f, Float::plus)
            decreaseScore(secondaryScores, "캐릭터 중심", 0.55f)
            reasoning += "일러스트/배경 신호가 우세하고 얼굴 비중이 작아 캐릭터 중심 대신 배경 중심을 우선했다."
        } else if ((artSemanticScore >= 0.7f || hasBackgroundArtworkSignal) && strongCharacterSubject) {
            secondaryScores.merge("캐릭터 중심", 0.28f, Float::plus)
            reasoning += "일러스트 계열이지만 얼굴 비중이 충분해 캐릭터 중심 후보를 유지했다."
        }
    }

    if (noFaceScenicArtworkCandidate) {
        decreaseScore(primaryScores, "사람", 1.2f)
        decreaseScore(primaryScores, "셀카", 1.3f)
        decreaseScore(secondaryScores, "캐릭터 중심", 0.82f)

        if (scenicPrimaryScore > 0f || hasScenicClassifierSignal || hasScenicClipSignal) {
            primaryScores.merge("풍경", 0.82f, Float::plus)
        }
        if (backgroundSecondaryScore > 0f || (artSemanticScore + clipArtPrimaryScore) >= 0.35f || hasBackgroundArtworkSignal) {
            primaryScores.merge("일러스트", 0.48f, Float::plus)
            secondaryScores.merge("배경 중심", 0.96f, Float::plus)
        }
        if ((semantic?.primaryScores?.get("애니 관련") ?: 0f) + (clipSemantic?.primaryScores?.get("애니 관련") ?: 0f) >= 0.48f) {
            primaryScores.merge("애니 관련", 0.18f, Float::plus)
        }

        reasoning += "얼굴과 명시적 인물 근거 없이 풍경/배경 신호가 우세해 사람/인물 중심을 억제했다."
    } else if (auxiliary.faceCount == 0 && !hasExplicitHumanTextSignal && !hasHumanClassifierSignal) {
        decreaseScore(primaryScores, "셀카", 0.55f)
        reasoning += "얼굴이나 명시적 인물 근거가 없어 셀카 점수를 보수적으로 낮췄다."
    }
}

internal fun shouldUseReviewFallback(
    candidateLevel1: String,
    topScore: Float,
    secondScore: Float,
    classifierScore: Float,
    prototypeScore: Float,
    clipScore: Float,
    auxiliary: MlKitAuxiliaryResult,
    hasStrongUiSignals: Boolean,
    hasStrongDocumentSignals: Boolean
): Boolean {
    if (candidateLevel1 in setOf("문서", "영수증", "스크린샷") && (hasStrongUiSignals || hasStrongDocumentSignals)) {
        return false
    }
    if (candidateLevel1 in setOf("사람", "셀카") &&
        auxiliary.faceCount > 0 &&
        auxiliary.centeredFaceScore >= 0.45f &&
        auxiliary.maxFaceAreaRatio >= MediumFaceAreaRatio
    ) {
        return false
    }

    if (
        candidateLevel1 in ScenicInterpretationLabels &&
        auxiliary.faceCount == 0 &&
        !hasStrongUiSignals &&
        !hasStrongDocumentSignals
    ) {
        val scenicLowTopScore = topScore < ScenicConservativeTopScoreThreshold
        val scenicLowMargin = (topScore - secondScore) < ScenicConservativeMarginThreshold
        val scenicWeakSemanticSupport = classifierScore < ScenicClassifierThreshold &&
            prototypeScore < ScenicPrototypeThreshold &&
            clipScore < ScenicClipThreshold

        return scenicLowTopScore && scenicLowMargin && scenicWeakSemanticSupport
    }

    val lowTopScore = topScore < ConservativeTopScoreThreshold
    val lowMargin = (topScore - secondScore) < ConservativeMarginThreshold
    val weakSemanticSupport = classifierScore < ConservativeClassifierThreshold &&
        prototypeScore < ConservativePrototypeThreshold &&
        clipScore < ConservativeClipThreshold
    val weakMlKitSupport = auxiliary.faceCount == 0 &&
        auxiliary.textLength < 18 &&
        auxiliary.uiKeywordHits.isEmpty() &&
        auxiliary.receiptKeywordHits.isEmpty()

    if (topScore < VeryLowTopScoreThreshold && lowMargin) {
        return true
    }

    return candidateLevel1 in ConservativeInterpretationLabels &&
        (lowTopScore || lowMargin) &&
        weakSemanticSupport &&
        weakMlKitSupport
}

@Serializable
internal data class VisionTaxonomy(
    val schemaVersion: Int = 1,
    val primaryCategories: List<TaxonomyProfile> = emptyList(),
    val secondaryCategories: List<TaxonomyProfile> = emptyList(),
    val seriesCatalog: List<SeriesCatalogEntry> = emptyList()
) {
    val prototypeIds: Set<String>
        get() = (primaryCategories + secondaryCategories)
            .mapNotNull(TaxonomyProfile::prototypeId)
            .toSet()
}

@Serializable
internal data class TaxonomyProfile(
    val id: String,
    val displayName: String,
    val level1: String,
    val level2: String = "",
    val classifierKeywords: List<String> = emptyList(),
    val pathKeywords: List<String> = emptyList(),
    val textKeywords: List<String> = emptyList(),
    val prototypeId: String? = null
) {
    val outputLabel: String
        get() = level2.ifBlank { level1 }
}

@Serializable
internal data class SeriesCatalogEntry(
    val name: String,
    val allowedLevel1: List<String> = emptyList(),
    val keywords: List<String> = emptyList()
)

internal object VisionTaxonomyLoader {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun load(context: Context): VisionTaxonomy {
        return runCatching {
            context.assets.open(TaxonomyAssetPath).bufferedReader().use { reader ->
                json.decodeFromString<VisionTaxonomy>(reader.readText())
            }
        }.getOrElse { throwable ->
            Log.w(LogTag, "vision taxonomy asset load failed, using defaults", throwable)
            defaultTaxonomy()
        }
    }

    private fun defaultTaxonomy(): VisionTaxonomy {
        return VisionTaxonomy(
            primaryCategories = listOf(
                TaxonomyProfile(
                    id = "fallback-photo",
                    displayName = "사람",
                    level1 = "사람",
                    classifierKeywords = listOf("person", "portrait"),
                    prototypeId = "person"
                ),
                TaxonomyProfile(
                    id = "fallback-screenshot",
                    displayName = "스크린샷",
                    level1 = "스크린샷",
                    classifierKeywords = listOf("monitor", "web site"),
                    prototypeId = "screenshot"
                ),
                TaxonomyProfile(
                    id = "fallback-illustration",
                    displayName = "일러스트",
                    level1 = "일러스트",
                    classifierKeywords = listOf("illustration", "painting"),
                    prototypeId = "illustration"
                ),
                TaxonomyProfile(
                    id = "fallback-other",
                    displayName = "기타",
                    level1 = "기타",
                    prototypeId = "other"
                )
            )
        )
    }

    private const val TaxonomyAssetPath = "vision_taxonomy.json"
}

private object PrototypeBitmapFactory {
    fun create(prototypeId: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (prototypeId) {
            "person" -> drawPerson(canvas, paint, size, selfie = false)
            "selfie" -> drawPerson(canvas, paint, size, selfie = true)
            "landscape" -> drawLandscape(canvas, paint, size)
            "food" -> drawFood(canvas, paint, size)
            "pet" -> drawPet(canvas, paint, size)
            "document" -> drawDocument(canvas, paint, size, receipt = false)
            "receipt" -> drawDocument(canvas, paint, size, receipt = true)
            "screenshot" -> drawScreenshot(canvas, paint, size)
            "drawing" -> drawDrawing(canvas, paint, size)
            "illustration" -> drawIllustration(canvas, paint, size)
            "anime" -> drawAnime(canvas, paint, size)
            "game" -> drawGame(canvas, paint, size)
            "meme" -> drawMeme(canvas, paint, size)
            else -> {
                canvas.drawColor(Color.rgb(92, 107, 192))
                paint.color = Color.WHITE
                paint.textSize = size * 0.16f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("OTHER", size / 2f, size / 2f, paint)
            }
        }

        return bitmap
    }

    private fun drawPerson(canvas: Canvas, paint: Paint, size: Int, selfie: Boolean) {
        canvas.drawColor(if (selfie) Color.rgb(255, 214, 219) else Color.rgb(210, 226, 255))
        paint.color = Color.rgb(255, 224, 189)
        canvas.drawCircle(size * 0.5f, size * 0.38f, size * 0.18f, paint)
        paint.color = if (selfie) Color.rgb(80, 48, 96) else Color.rgb(53, 53, 53)
        canvas.drawOval(RectF(size * 0.28f, size * 0.48f, size * 0.72f, size * 0.88f), paint)
        paint.color = Color.WHITE
        canvas.drawCircle(size * 0.44f, size * 0.37f, size * 0.018f, paint)
        canvas.drawCircle(size * 0.56f, size * 0.37f, size * 0.018f, paint)
        if (selfie) {
            paint.color = Color.rgb(255, 255, 255)
            canvas.drawRoundRect(RectF(size * 0.14f, size * 0.54f, size * 0.33f, size * 0.78f), 20f, 20f, paint)
        }
    }

    private fun drawLandscape(canvas: Canvas, paint: Paint, size: Int) {
        canvas.drawColor(Color.rgb(133, 196, 255))
        paint.color = Color.rgb(245, 210, 76)
        canvas.drawCircle(size * 0.18f, size * 0.18f, size * 0.08f, paint)
        paint.color = Color.rgb(71, 133, 78)
        canvas.drawRect(0f, size * 0.62f, size.toFloat(), size.toFloat(), paint)
        paint.color = Color.rgb(90, 110, 160)
        canvas.drawPath(android.graphics.Path().apply {
            moveTo(0f, size * 0.72f)
            lineTo(size * 0.26f, size * 0.38f)
            lineTo(size * 0.46f, size * 0.66f)
            lineTo(size * 0.72f, size * 0.3f)
            lineTo(size.toFloat(), size * 0.72f)
            close()
        }, paint)
    }

    private fun drawFood(canvas: Canvas, paint: Paint, size: Int) {
        canvas.drawColor(Color.rgb(255, 242, 224))
        paint.color = Color.WHITE
        canvas.drawCircle(size * 0.5f, size * 0.56f, size * 0.22f, paint)
        paint.color = Color.rgb(229, 115, 115)
        canvas.drawCircle(size * 0.45f, size * 0.52f, size * 0.07f, paint)
        paint.color = Color.rgb(255, 183, 77)
        canvas.drawCircle(size * 0.56f, size * 0.58f, size * 0.08f, paint)
        paint.color = Color.rgb(129, 199, 132)
        canvas.drawCircle(size * 0.52f, size * 0.47f, size * 0.05f, paint)
    }

    private fun drawPet(canvas: Canvas, paint: Paint, size: Int) {
        canvas.drawColor(Color.rgb(237, 231, 246))
        paint.color = Color.rgb(120, 81, 45)
        canvas.drawCircle(size * 0.5f, size * 0.5f, size * 0.2f, paint)
        canvas.drawCircle(size * 0.34f, size * 0.28f, size * 0.09f, paint)
        canvas.drawCircle(size * 0.66f, size * 0.28f, size * 0.09f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(size * 0.44f, size * 0.48f, size * 0.02f, paint)
        canvas.drawCircle(size * 0.56f, size * 0.48f, size * 0.02f, paint)
    }

    private fun drawDocument(canvas: Canvas, paint: Paint, size: Int, receipt: Boolean) {
        canvas.drawColor(Color.rgb(240, 240, 240))
        paint.color = Color.WHITE
        val width = if (receipt) size * 0.46f else size * 0.68f
        canvas.drawRoundRect(
            RectF(size * 0.5f - width / 2f, size * 0.16f, size * 0.5f + width / 2f, size * 0.86f),
            20f,
            20f,
            paint
        )
        paint.color = Color.rgb(160, 160, 160)
        var y = size * 0.28f
        repeat(if (receipt) 9 else 6) {
            canvas.drawRect(size * 0.5f - width * 0.34f, y, size * 0.5f + width * 0.34f, y + size * 0.025f, paint)
            y += size * if (receipt) 0.065f else 0.09f
        }
    }

    private fun drawScreenshot(canvas: Canvas, paint: Paint, size: Int) {
        canvas.drawColor(Color.rgb(33, 33, 33))
        paint.color = Color.rgb(66, 165, 245)
        canvas.drawRoundRect(RectF(size * 0.12f, size * 0.12f, size * 0.88f, size * 0.24f), 18f, 18f, paint)
        paint.color = Color.rgb(255, 255, 255)
        canvas.drawRoundRect(RectF(size * 0.18f, size * 0.32f, size * 0.82f, size * 0.5f), 16f, 16f, paint)
        paint.color = Color.rgb(236, 239, 241)
        canvas.drawRoundRect(RectF(size * 0.18f, size * 0.56f, size * 0.48f, size * 0.8f), 16f, 16f, paint)
        canvas.drawRoundRect(RectF(size * 0.52f, size * 0.56f, size * 0.82f, size * 0.8f), 16f, 16f, paint)
    }

    private fun drawDrawing(canvas: Canvas, paint: Paint, size: Int) {
        canvas.drawColor(Color.WHITE)
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.03f
        canvas.drawLine(size * 0.18f, size * 0.72f, size * 0.42f, size * 0.28f, paint)
        canvas.drawLine(size * 0.42f, size * 0.28f, size * 0.64f, size * 0.7f, paint)
        canvas.drawCircle(size * 0.56f, size * 0.4f, size * 0.1f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawIllustration(canvas: Canvas, paint: Paint, size: Int) {
        canvas.drawColor(Color.rgb(255, 244, 229))
        paint.color = Color.rgb(244, 143, 177)
        canvas.drawRoundRect(RectF(size * 0.1f, size * 0.14f, size * 0.9f, size * 0.86f), 36f, 36f, paint)
        paint.color = Color.rgb(255, 255, 255)
        canvas.drawCircle(size * 0.5f, size * 0.42f, size * 0.18f, paint)
        paint.color = Color.rgb(41, 121, 255)
        canvas.drawRect(size * 0.24f, size * 0.62f, size * 0.76f, size * 0.76f, paint)
    }

    private fun drawAnime(canvas: Canvas, paint: Paint, size: Int) {
        canvas.drawColor(Color.rgb(208, 232, 255))
        paint.color = Color.rgb(123, 31, 162)
        canvas.drawOval(RectF(size * 0.22f, size * 0.12f, size * 0.78f, size * 0.48f), paint)
        paint.color = Color.rgb(255, 224, 189)
        canvas.drawCircle(size * 0.5f, size * 0.48f, size * 0.2f, paint)
        paint.color = Color.WHITE
        canvas.drawOval(RectF(size * 0.37f, size * 0.44f, size * 0.47f, size * 0.54f), paint)
        canvas.drawOval(RectF(size * 0.53f, size * 0.44f, size * 0.63f, size * 0.54f), paint)
        paint.color = Color.rgb(33, 33, 33)
        canvas.drawCircle(size * 0.42f, size * 0.49f, size * 0.018f, paint)
        canvas.drawCircle(size * 0.58f, size * 0.49f, size * 0.018f, paint)
    }

    private fun drawGame(canvas: Canvas, paint: Paint, size: Int) {
        canvas.drawColor(Color.rgb(15, 23, 42))
        paint.color = Color.rgb(239, 68, 68)
        canvas.drawRect(size * 0.08f, size * 0.1f, size * 0.36f, size * 0.16f, paint)
        paint.color = Color.rgb(34, 197, 94)
        canvas.drawRect(size * 0.64f, size * 0.1f, size * 0.92f, size * 0.16f, paint)
        paint.color = Color.rgb(96, 165, 250)
        canvas.drawRoundRect(RectF(size * 0.18f, size * 0.26f, size * 0.82f, size * 0.72f), 18f, 18f, paint)
        paint.color = Color.rgb(245, 158, 11)
        canvas.drawCircle(size * 0.5f, size * 0.5f, size * 0.09f, paint)
        paint.color = Color.WHITE
        canvas.drawText("HUD", size * 0.68f, size * 0.88f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.12f
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
        })
    }

    private fun drawMeme(canvas: Canvas, paint: Paint, size: Int) {
        canvas.drawColor(Color.rgb(250, 250, 250))
        paint.color = Color.BLACK
        canvas.drawRect(0f, 0f, size.toFloat(), size * 0.18f, paint)
        canvas.drawRect(0f, size * 0.82f, size.toFloat(), size.toFloat(), paint)
        paint.color = Color.rgb(255, 236, 179)
        canvas.drawRoundRect(RectF(size * 0.18f, size * 0.24f, size * 0.82f, size * 0.76f), 20f, 20f, paint)
    }
}

private data class StyleMetrics(
    val averageSaturation: Float,
    val averageValue: Float,
    val edgeDensity: Float
) {
    val highSaturationAnimeLike: Boolean
        get() = averageSaturation >= 0.42f && edgeDensity >= 0.14f

    val rectilinearUiLike: Boolean
        get() = averageValue <= 0.7f && edgeDensity >= 0.19f

    val brightPageLike: Boolean
        get() = averageValue >= 0.82f && averageSaturation <= 0.22f

    companion object {
        fun from(bitmap: Bitmap): StyleMetrics {
            val width = bitmap.width
            val height = bitmap.height
            var saturationSum = 0f
            var valueSum = 0f
            var edgeHits = 0
            var sampleCount = 0
            val hsv = FloatArray(3)
            val step = max(1, minOf(width, height) / 40)

            for (y in 0 until height step step) {
                for (x in 0 until width step step) {
                    val color = bitmap.getPixel(x, y)
                    Color.colorToHSV(color, hsv)
                    saturationSum += hsv[1]
                    valueSum += hsv[2]
                    sampleCount += 1

                    if (x + step < width) {
                        val right = bitmap.getPixel(x + step, y)
                        if (luminanceDelta(color, right) >= 40f) {
                            edgeHits += 1
                        }
                    }
                    if (y + step < height) {
                        val bottom = bitmap.getPixel(x, y + step)
                        if (luminanceDelta(color, bottom) >= 40f) {
                            edgeHits += 1
                        }
                    }
                }
            }

            val samples = max(sampleCount, 1)
            val possibleEdges = max(sampleCount * 2, 1)
            return StyleMetrics(
                averageSaturation = saturationSum / samples,
                averageValue = valueSum / samples,
                edgeDensity = edgeHits.toFloat() / possibleEdges.toFloat()
            )
        }

        private fun luminanceDelta(first: Int, second: Int): Float {
            val firstLuma = (0.2126f * Color.red(first)) + (0.7152f * Color.green(first)) + (0.0722f * Color.blue(first))
            val secondLuma = (0.2126f * Color.red(second)) + (0.7152f * Color.green(second)) + (0.0722f * Color.blue(second))
            return abs(firstLuma - secondLuma)
        }
    }
}

private fun keywordScore(searchableText: String, keywords: List<String>, hitWeight: Float): Float {
    if (keywords.isEmpty() || searchableText.isBlank()) {
        return 0f
    }
    val normalizedText = searchableText.lowercase()
    return keywords.sumOf { keyword ->
        if (normalizedText.contains(keyword.lowercase())) hitWeight.toDouble() else 0.0
    }.toFloat()
}

private fun decreaseScore(scores: MutableMap<String, Float>, label: String, delta: Float) {
    scores[label] = (scores[label] ?: 0f) - delta
}

internal fun aggregateFrameScores(frameScores: List<Map<String, Float>>): Map<String, Float> {
    if (frameScores.isEmpty()) {
        return emptyMap()
    }

    val labels = frameScores.flatMap { it.keys }.toSet()
    return labels.associateWith { label ->
        robustAggregateScore(frameScores.map { scores -> scores[label] ?: 0f })
    }.filterValues { it > 0.001f && it.isFinite() }
}

internal fun robustAggregateScore(values: List<Float>): Float {
    if (values.isEmpty()) {
        return 0f
    }
    if (values.size == 1) {
        return values.first().coerceAtLeast(0f)
    }
    if (values.size == 2) {
        return values.average().toFloat().coerceAtLeast(0f)
    }

    val normalizedValues = values.map { it.coerceAtLeast(0f) }.sorted()
    val median = normalizedValues[normalizedValues.size / 2]
    val mean = normalizedValues.average().toFloat()
    return (median * 0.55f + mean * 0.45f).coerceAtLeast(0f)
}

private fun mergeFrameDebugSummaries(
    semanticFrames: List<FrameDebugInfo>,
    mlKitFrames: List<FrameDebugInfo>
): List<FrameDebugInfo> {
    if (semanticFrames.isEmpty()) {
        return mlKitFrames
    }
    if (mlKitFrames.isEmpty()) {
        return semanticFrames
    }

    val mergedKeys = linkedSetOf<Pair<String, Long?>>()
    (semanticFrames + mlKitFrames).forEach { frame ->
        mergedKeys += frame.frameLabel to frame.timestampMs
    }

    return mergedKeys.map { (frameLabel, timestampMs) ->
        val semanticFrame = semanticFrames.firstOrNull { it.frameLabel == frameLabel && it.timestampMs == timestampMs }
        val mlKitFrame = mlKitFrames.firstOrNull { it.frameLabel == frameLabel && it.timestampMs == timestampMs }
        FrameDebugInfo(
            frameLabel = frameLabel,
            timestampMs = timestampMs,
            summary = listOfNotNull(
                semanticFrame?.summary?.takeIf { it.isNotBlank() },
                mlKitFrame?.summary?.takeIf { it.isNotBlank() }
            ).joinToString(" || "),
            tags = (semanticFrame?.tags.orEmpty() + mlKitFrame?.tags.orEmpty()).take(8),
            notes = (semanticFrame?.notes.orEmpty() + mlKitFrame?.notes.orEmpty()).distinct()
        )
    }
}

private fun cosineSimilarity(first: FloatArray, second: FloatArray): Float {
    if (first.isEmpty() || second.isEmpty() || first.size != second.size) {
        return 0f
    }

    var dot = 0f
    var firstNorm = 0f
    var secondNorm = 0f

    first.indices.forEach { index ->
        dot += first[index] * second[index]
        firstNorm += first[index].pow(2)
        secondNorm += second[index].pow(2)
    }

    if (firstNorm == 0f || secondNorm == 0f) {
        return 0f
    }

    return (dot / (sqrt(firstNorm) * sqrt(secondNorm))).coerceIn(-1f, 1f)
}

private fun formatScore(value: Float): String = "%.2f".format(value)

private fun assetExists(context: Context, assetPath: String): Boolean {
    return runCatching {
        context.assets.open(assetPath).close()
        true
    }.getOrDefault(false)
}

private fun loadEfficientNetLabelMap(
    context: Context,
    assetPath: String
): Map<Int, String> {
    val raw = context.assets.open(assetPath).bufferedReader().use { reader -> reader.readText() }
    val entries = LinkedHashMap<Int, String>()

    raw.lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && it != "{" && it != "}" }
        .forEachIndexed { lineIndex, line ->
            val normalizedLine = line.removeSuffix(",")
            val dictMatch = LabelMapDictEntryRegex.matchEntire(normalizedLine)
            if (dictMatch != null) {
                val index = dictMatch.groupValues[1].toInt()
                val label = dictMatch.groupValues[2].trim()
                if (label.isNotBlank()) {
                    entries[index] = label
                }
            } else {
                val label = normalizedLine.trim().trim('"').trim('\'')
                if (label.isNotBlank()) {
                    entries[lineIndex] = label
                }
            }
        }

    require(entries.isNotEmpty()) { "ImageNet 라벨맵을 파싱하지 못했습니다." }
    return entries
}

private fun softmaxTopK(
    logits: FloatArray,
    labels: Map<Int, String>,
    maxResults: Int
): List<ScoredSignal> {
    if (logits.isEmpty()) {
        return emptyList()
    }

    val maxLogit = logits.maxOrNull() ?: return emptyList()
    val expValues = DoubleArray(logits.size)
    var sum = 0.0

    logits.indices.forEach { index ->
        val scaled = exp((logits[index] - maxLogit).toDouble())
        expValues[index] = scaled
        sum += scaled
    }

    if (sum <= 0.0) {
        return emptyList()
    }

    return logits.indices
        .map { index ->
            val label = labels[index]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "class_$index"
            ScoredSignal(label, (expValues[index] / sum).toFloat())
        }
        .sortedByDescending { it.score }
        .take(maxResults)
}

private fun <K, V> Iterable<K>.associateWithNotNull(transform: (K) -> Pair<K, V>?): Map<K, V> {
    val destination = linkedMapOf<K, V>()
    for (element in this) {
        transform(element)?.let { (key, value) ->
            destination[key] = value
        }
    }
    return destination
}

private fun <K, V> linkedLruMap(maxEntries: Int): LinkedHashMap<K, V> {
    return object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) {
            continuation.resume(result)
        }
    }
    addOnFailureListener { exception ->
        if (continuation.isActive) {
            continuation.resumeWithException(exception)
        }
    }
}

private val ScreenTextKeywords = listOf(
    "login",
    "sign in",
    "menu",
    "settings",
    "quest",
    "inventory",
    "notification",
    "friends",
    "profile",
    "start",
    "옵션",
    "설정",
    "퀘스트",
    "인벤토리"
)

private val ReceiptTextKeywords = listOf(
    "receipt",
    "total",
    "subtotal",
    "tax",
    "krw",
    "won",
    "cash",
    "card",
    "vat",
    "합계",
    "결제",
    "승인",
    "과세"
)

private const val PrototypeBitmapSize = 224
private const val Level1MinimumScore = 0.2f
private const val TopScoreWeight = 0.45f
private const val ScoreMarginWeight = 0.35f
private const val SemanticSupportWeight = 0.20f
private const val ConservativeTopScoreThreshold = 0.42f
private const val ConservativeMarginThreshold = 0.12f
private const val VeryLowTopScoreThreshold = 0.24f
private const val ConservativeClassifierThreshold = 0.25f
private const val ConservativePrototypeThreshold = 0.52f
private const val ConservativeClipThreshold = 0.22f
private const val ScenicConservativeTopScoreThreshold = 0.26f
private const val ScenicConservativeMarginThreshold = 0.05f
private const val ScenicClassifierThreshold = 0.10f
private const val ScenicPrototypeThreshold = 0.26f
private const val ScenicClipThreshold = 0.08f
private const val MobileClipPrimaryWeight = 1.1f
private const val MobileClipSecondaryWeight = 0.95f
private const val AuxiliaryLabelMinimumScore = 0.18f
private const val AuxiliaryPrimaryWeight = 0.14f
private const val AuxiliarySecondaryWeight = 0.11f
private const val MediumFaceAreaRatio = 0.04f
private const val StrongFaceAreaRatio = 0.08f
private const val MediumCenteredFaceScore = 0.58f
private const val StrongCenteredFaceScore = 0.74f
private val ConservativeInterpretationLabels = setOf(
    "사람",
    "셀카",
    "그림",
    "일러스트",
    "애니 관련",
    "게임 관련",
    "밈"
)
private val ScenicInterpretationLabels = setOf(
    "풍경",
    "그림",
    "일러스트"
)
private val HumanClassifierKeywords = listOf(
    "person",
    "human",
    "portrait",
    "face",
    "man",
    "woman",
    "boy",
    "girl",
    "bride",
    "groom"
)
private val ScenicClassifierKeywords = listOf(
    "landscape",
    "seashore",
    "mountain",
    "valley",
    "forest",
    "sky",
    "cloud",
    "moon",
    "star",
    "night",
    "sunset",
    "nature"
)
private val ScenicClipLabels = setOf(
    "풍경",
    "일러스트",
    "애니 관련",
    "일반 일러스트",
    "배경 중심"
)
private val LabelMapDictEntryRegex = Regex("""^\s*(\d+)\s*:\s*['"](.+?)['"]\s*$""")
private const val LogTag = "VisionPipeline"
