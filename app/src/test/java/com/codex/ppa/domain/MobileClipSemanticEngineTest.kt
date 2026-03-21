package com.codex.ppa.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileClipSemanticEngineTest {
    @Test
    fun softmaxSimilaritySignals_prefersHigherSimilarity() {
        val ranked = softmaxSimilaritySignals(
            rawScores = listOf(
                "일러스트" to 0.33f,
                "사람" to 0.11f,
                "풍경" to 0.08f
            ),
            temperature = 12f
        )

        assertEquals("일러스트", ranked.first().label)
        assertTrue(ranked.first().score > ranked[1].score)
    }

    @Test
    fun softmaxSimilaritySignals_returnsEmptyForEmptyScores() {
        val ranked = softmaxSimilaritySignals(
            rawScores = emptyList(),
            temperature = 12f
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun calibratedSimilaritySignals_downweightsAmbiguousScores() {
        val result = calibratedSimilaritySignals(
            rawScores = listOf(
                "사람" to 0.211f,
                "풍경" to 0.205f,
                "일러스트" to 0.202f
            ),
            temperature = 12f
        )

        assertTrue(result.calibration < 0.4f)
        assertTrue(result.signals.first().score < 0.2f)
    }

    @Test
    fun calibratedSimilaritySignals_preservesStrongDistinctSignal() {
        val result = calibratedSimilaritySignals(
            rawScores = listOf(
                "풍경" to 0.341f,
                "일러스트" to 0.214f,
                "사람" to 0.163f
            ),
            temperature = 12f
        )

        assertEquals("풍경", result.signals.first().label)
        assertTrue(result.calibration > 0.8f)
        assertTrue(result.signals.first().score > 0.7f)
    }
}
