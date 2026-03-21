package com.codex.ppa.domain

interface ClassificationEngine {
    val engineId: String
    val displayName: String
    val engineVersion: String?
        get() = null

    fun runtimeStatus(): ClassificationEngineStatus = ClassificationEngineStatus(
        engineId = engineId,
        displayName = displayName,
        engineVersion = engineVersion
    )

    suspend fun classify(mediaItem: MediaItem): ClassificationSuggestion?

    suspend fun suggest(mediaItem: MediaItem): ClassificationLabels? = classify(mediaItem)?.labels
}

class BasicSuggestionClassificationEngine : ClassificationEngine {
    override val engineId: String = "basic-rule-based"
    override val displayName: String = "기본 규칙 기반 자동분류 제안"
    override val engineVersion: String = "rules-v2"

    override fun runtimeStatus(): ClassificationEngineStatus {
        return ClassificationEngineStatus(
            engineId = engineId,
            displayName = displayName,
            engineVersion = engineVersion,
            models = listOf(
                ModelRuntimeStatus(
                    modelId = "rule-based-fallback",
                    displayName = "규칙 기반 보조 엔진",
                    version = engineVersion,
                    role = "파일명/경로 기반 축소 모드",
                    available = true,
                    loaded = true,
                    summary = "모델 파일 없이 항상 동작하는 안전장치"
                )
            )
        )
    }

    override suspend fun classify(mediaItem: MediaItem): ClassificationSuggestion {
        val labels = BasicSuggestionClassifier.suggest(
            displayName = mediaItem.displayName,
            relativePath = mediaItem.relativePath,
            mimeType = mediaItem.mimeType,
            mediaType = mediaItem.mediaType
        )

        return ClassificationSuggestion(
            labels = labels,
            confidence = 0.18f,
            engineInfo = ClassificationEngineInfo(
                engineId = engineId,
                engineVersion = engineVersion,
                engineDisplayName = displayName
            ),
            debugInfo = ClassificationDebugInfo(
                confidence = 0.18f,
                reducedMode = true,
                reasoning = listOf("추가 비전 모델 또는 ML Kit가 없을 때 파일명·경로·MIME 규칙만 사용"),
                finalScores = listOf(
                    ScoredSignal(labels.level1.ifBlank { "기타" }, 0.18f)
                ),
                modelOutputs = listOf(
                    ModelDebugInfo(
                        modelId = "rule-based-fallback",
                        displayName = "규칙 기반 보조 엔진",
                        summary = "파일명·상대 경로·MIME 타입에서 키워드를 찾았다."
                    )
                )
            )
        )
    }
}

object BasicSuggestionClassifier {
    fun suggest(
        displayName: String,
        relativePath: String?,
        mimeType: String?,
        mediaType: MediaType
    ): ClassificationLabels {
        val searchableText = listOf(
            displayName,
            relativePath.orEmpty(),
            mimeType.orEmpty()
        ).joinToString(separator = " ").lowercase()

        val inferredLevel1 = when {
            searchableText.containsAny("receipt", "bill", "invoice", "영수증", "결제") -> "영수증"
            searchableText.containsAny("document", "docs", "memo", "note", "scan", "문서", "메모") -> "문서"
            searchableText.containsAny("selfie", "front", "셀카") -> "셀카"
            searchableText.containsAny("portrait", "person", "family", "friends", "인물", "사람") -> "사람"
            searchableText.containsAny("dog", "cat", "pet", "puppy", "kitten", "반려", "강아지", "고양이") -> "반려동물"
            searchableText.containsAny("food", "meal", "cafe", "restaurant", "음식", "맛집") -> "음식"
            searchableText.containsAny("travel", "trip", "landscape", "nature", "풍경", "여행") -> "풍경"
            searchableText.containsAny("anime", "ani", "manga", "comic", "애니", "만화") -> "애니 관련"
            searchableText.containsAny("game", "steam", "genshin", "valorant", "minecraft", "gaming", "게임") -> "게임 관련"
            searchableText.containsAny("illust", "illustration", "artwork", "pixiv", "fanart", "일러스트") -> "일러스트"
            searchableText.containsAny("drawing", "sketch", "doodle", "그림", "낙서") -> "그림"
            searchableText.containsAny("meme", "shitpost", "짤", "밈") -> "밈"
            searchableText.containsAny(
                "screenshot",
                "screen_shot",
                "screen-shot",
                "screenrecord",
                "screen_record",
                "캡처"
            ) -> "스크린샷"
            mediaType == MediaType.VIDEO -> "기타"
            else -> "기타"
        }

        val inferredLevel2 = when {
            inferredLevel1 == "스크린샷" -> "UI 중심"
            inferredLevel1 == "애니 관련" -> "애니 이미지"
            inferredLevel1 == "게임 관련" -> "게임 이미지"
            inferredLevel1 == "일러스트" -> "일반 일러스트"
            inferredLevel1 == "그림" -> "만화풍/웹툰풍"
            inferredLevel1 in setOf("사람", "셀카") -> "캐릭터 중심"
            else -> ""
        }

        val inferredLevel3 = relativePath
            ?.split('/')
            ?.map { it.trim() }
            ?.filter { segment -> segment.isNotBlank() }
            ?.lastOrNull { segment ->
                val normalized = segment.lowercase()
                normalized !in GenericDirectoryNames
            }
            .orEmpty()

        return ClassificationLabels(
            level1 = inferredLevel1,
            level2 = inferredLevel2,
            level3 = inferredLevel3
        )
    }
}

internal val GenericDirectoryNames = setOf(
    "dcim",
    "camera",
    "pictures",
    "picture",
    "movies",
    "movie",
    "download",
    "downloads",
    "images",
    "image",
    "videos",
    "video",
    "screenshots",
    "screenrecord",
    "screenrecords",
    "screen recordings",
    "screen_recordings"
)

internal fun String.containsAny(vararg keywords: String): Boolean {
    return keywords.any { keyword -> contains(keyword) }
}
