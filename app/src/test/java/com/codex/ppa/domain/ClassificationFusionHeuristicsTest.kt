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
}
