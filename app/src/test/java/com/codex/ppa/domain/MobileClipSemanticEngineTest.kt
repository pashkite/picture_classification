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
}
