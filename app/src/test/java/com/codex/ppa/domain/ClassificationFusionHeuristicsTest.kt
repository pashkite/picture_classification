package com.codex.ppa.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificationFusionHeuristicsTest {
    @Test
    fun applyDominanceHeuristics_prefersGameScreenshotOverPerson() {
        val primaryScores = mutableMapOf(
            "사람" to 1.2f,
            "스크린샷" to 0.4f,
            "게임 관련" to 0.35f
        )
        val secondaryScores = mutableMapOf(
            "UI 중심" to 0.2f,
            "게임 이미지" to 0.1f
        )
        val reasoning = mutableListOf<String>()

        applyDominanceHeuristics(
            primaryScores = primaryScores,
            secondaryScores = secondaryScores,
            auxiliary = MlKitAuxiliaryResult(
                faceCount = 1,
                centeredFaceScore = 0.7f,
                recognizedText = "quest inventory raid boss",
                textLength = 84,
                textLineCount = 7,
                uiKeywordHits = listOf("quest", "inventory"),
                receiptKeywordHits = emptyList(),
                auxiliaryTags = emptyList(),
                reasoning = emptyList()
            ),
            searchableText = "screenshot game quest inventory battle",
            semantic = SemanticInferenceResult(
                primaryScores = mapOf("게임 관련" to 0.55f),
                secondaryScores = mapOf("게임 이미지" to 0.42f),
                classifierTags = emptyList(),
                prototypeTags = emptyList(),
                reasoning = emptyList(),
                reducedMode = false
            ),
            hasStrongUiSignals = true,
            hasStrongDocumentSignals = false,
            hasGameSignals = true,
            reasoning = reasoning
        )

        assertTrue(primaryScores.getValue("스크린샷") > primaryScores.getValue("사람"))
        assertTrue(primaryScores.getValue("게임 관련") > primaryScores.getValue("사람"))
        assertTrue(secondaryScores.getValue("UI 중심") >= 0.75f)
        assertTrue(secondaryScores.getValue("게임 이미지") >= 0.62f)
    }

    @Test
    fun applyDominanceHeuristics_prefersDocumentOverPersonWhenOcrIsDense() {
        val primaryScores = mutableMapOf(
            "사람" to 0.9f,
            "문서" to 0.4f,
            "영수증" to 0.2f
        )
        val secondaryScores = mutableMapOf<String, Float>()
        val reasoning = mutableListOf<String>()

        applyDominanceHeuristics(
            primaryScores = primaryScores,
            secondaryScores = secondaryScores,
            auxiliary = MlKitAuxiliaryResult(
                faceCount = 1,
                centeredFaceScore = 0.4f,
                recognizedText = "합계 VAT 승인 total card 결제 금액",
                textLength = 168,
                textLineCount = 18,
                uiKeywordHits = emptyList(),
                receiptKeywordHits = listOf("합계", "결제"),
                auxiliaryTags = emptyList(),
                reasoning = emptyList()
            ),
            searchableText = "document receipt invoice memo 합계 결제 total",
            semantic = SemanticInferenceResult(
                primaryScores = mapOf("문서" to 0.72f),
                secondaryScores = emptyMap(),
                classifierTags = emptyList(),
                prototypeTags = emptyList(),
                reasoning = emptyList(),
                reducedMode = false
            ),
            hasStrongUiSignals = false,
            hasStrongDocumentSignals = true,
            hasGameSignals = false,
            reasoning = reasoning
        )

        assertTrue(primaryScores.getValue("문서") > primaryScores.getValue("사람"))
        assertTrue(primaryScores.getValue("영수증") > 1.0f)
    }

    @Test
    fun shouldUseReviewFallback_returnsTrueForWeakAnimeLikeSignal() {
        val shouldReview = shouldUseReviewFallback(
            candidateLevel1 = "애니 관련",
            topScore = 0.31f,
            secondScore = 0.24f,
            classifierScore = 0.14f,
            prototypeScore = 0.22f,
            auxiliary = MlKitAuxiliaryResult(
                faceCount = 0,
                centeredFaceScore = 0f,
                recognizedText = "",
                textLength = 0,
                textLineCount = 0,
                uiKeywordHits = emptyList(),
                receiptKeywordHits = emptyList(),
                auxiliaryTags = emptyList(),
                reasoning = emptyList()
            ),
            hasStrongUiSignals = false,
            hasStrongDocumentSignals = false
        )

        assertTrue(shouldReview)
    }

    @Test
    fun shouldUseReviewFallback_returnsFalseForStrongDocumentSignal() {
        val shouldReview = shouldUseReviewFallback(
            candidateLevel1 = "문서",
            topScore = 0.34f,
            secondScore = 0.18f,
            classifierScore = 0.21f,
            prototypeScore = 0.10f,
            auxiliary = MlKitAuxiliaryResult(
                faceCount = 0,
                centeredFaceScore = 0f,
                recognizedText = "invoice total vat card 결제 승인",
                textLength = 148,
                textLineCount = 19,
                uiKeywordHits = emptyList(),
                receiptKeywordHits = listOf("invoice", "결제"),
                auxiliaryTags = emptyList(),
                reasoning = emptyList()
            ),
            hasStrongUiSignals = false,
            hasStrongDocumentSignals = true
        )

        assertTrue(!shouldReview)
    }

    @Test
    fun applyDominanceHeuristics_prefersBackgroundFocusForIllustrationWhenFaceIsTiny() {
        val primaryScores = mutableMapOf(
            "사람" to 1.05f,
            "일러스트" to 0.96f,
            "애니 관련" to 0.48f
        )
        val secondaryScores = mutableMapOf(
            "캐릭터 중심" to 0.72f,
            "일반 일러스트" to 0.31f,
            "배경 중심" to 0.28f
        )
        val reasoning = mutableListOf<String>()

        applyDominanceHeuristics(
            primaryScores = primaryScores,
            secondaryScores = secondaryScores,
            auxiliary = MlKitAuxiliaryResult(
                faceCount = 1,
                centeredFaceScore = 0.41f,
                maxFaceAreaRatio = 0.02f,
                maxFaceCenteredness = 0.46f,
                recognizedText = "",
                textLength = 0,
                textLineCount = 0,
                uiKeywordHits = emptyList(),
                receiptKeywordHits = emptyList(),
                auxiliaryTags = listOf(ScoredSignal("illustration", 0.42f)),
                reasoning = emptyList()
            ),
            searchableText = "illust wallpaper background scenery",
            semantic = SemanticInferenceResult(
                primaryScores = mapOf("일러스트" to 0.88f, "애니 관련" to 0.31f),
                secondaryScores = mapOf("일반 일러스트" to 0.58f, "배경 중심" to 0.52f),
                classifierTags = listOf(ScoredSignal("illustration", 0.61f)),
                prototypeTags = listOf(ScoredSignal("일러스트", 0.66f)),
                reasoning = emptyList(),
                reducedMode = false
            ),
            hasStrongUiSignals = false,
            hasStrongDocumentSignals = false,
            hasGameSignals = false,
            reasoning = reasoning
        )

        assertTrue(primaryScores.getValue("일러스트") > primaryScores.getValue("사람"))
        assertTrue(secondaryScores.getValue("배경 중심") > secondaryScores.getValue("캐릭터 중심"))
        assertTrue(reasoning.any { it.contains("배경 중심") })
    }

    @Test
    fun applyDominanceHeuristics_keepsCharacterFocusWhenFaceIsLargeInArtwork() {
        val primaryScores = mutableMapOf(
            "일러스트" to 0.82f,
            "애니 관련" to 0.74f,
            "사람" to 0.51f
        )
        val secondaryScores = mutableMapOf(
            "캐릭터 중심" to 0.64f,
            "배경 중심" to 0.16f
        )
        val reasoning = mutableListOf<String>()

        applyDominanceHeuristics(
            primaryScores = primaryScores,
            secondaryScores = secondaryScores,
            auxiliary = MlKitAuxiliaryResult(
                faceCount = 1,
                centeredFaceScore = 0.83f,
                maxFaceAreaRatio = 0.12f,
                maxFaceCenteredness = 0.79f,
                recognizedText = "",
                textLength = 0,
                textLineCount = 0,
                uiKeywordHits = emptyList(),
                receiptKeywordHits = emptyList(),
                auxiliaryTags = listOf(ScoredSignal("fictional character", 0.48f)),
                reasoning = emptyList()
            ),
            searchableText = "anime character portrait key visual",
            semantic = SemanticInferenceResult(
                primaryScores = mapOf("애니 관련" to 0.91f, "일러스트" to 0.42f),
                secondaryScores = mapOf("캐릭터 중심" to 0.75f, "애니 이미지" to 0.4f),
                classifierTags = listOf(ScoredSignal("fictional character", 0.59f)),
                prototypeTags = listOf(ScoredSignal("애니 관련", 0.62f)),
                reasoning = emptyList(),
                reducedMode = false
            ),
            hasStrongUiSignals = false,
            hasStrongDocumentSignals = false,
            hasGameSignals = false,
            reasoning = reasoning
        )

        assertTrue(secondaryScores.getValue("캐릭터 중심") > secondaryScores.getValue("배경 중심"))
    }

    @Test
    fun robustAggregateScore_downweightsSingleOutlierFrame() {
        val outlierDominated = aggregateFrameScores(
            listOf(
                mapOf("사람" to 1.08f),
                emptyMap(),
                emptyMap()
            )
        )
        val stableSignal = aggregateFrameScores(
            listOf(
                mapOf("풍경" to 0.74f),
                mapOf("풍경" to 0.68f),
                emptyMap()
            )
        )

        assertTrue(outlierDominated.getValue("사람") < 0.25f)
        assertTrue(stableSignal.getValue("풍경") > 0.45f)
    }
}
