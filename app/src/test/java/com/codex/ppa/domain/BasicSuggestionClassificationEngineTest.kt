package com.codex.ppa.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BasicSuggestionClassificationEngineTest {
    @Test
    fun suggest_leavesGenericCameraPhotoAsOther() {
        val suggestion = BasicSuggestionClassifier.suggest(
            displayName = "IMG_20260320.jpg",
            relativePath = "DCIM/Camera/",
            mimeType = "image/jpeg",
            mediaType = MediaType.IMAGE
        )

        assertEquals("기타", suggestion.level1)
        assertEquals("", suggestion.level2)
    }

    @Test
    fun suggest_marksScreenshotAsScreenshot() {
        val suggestion = BasicSuggestionClassifier.suggest(
            displayName = "Screenshot_20260320.png",
            relativePath = "Pictures/Screenshots/",
            mimeType = "image/png",
            mediaType = MediaType.IMAGE
        )

        assertEquals("스크린샷", suggestion.level1)
        assertEquals("UI 중심", suggestion.level2)
    }

    @Test
    fun suggest_usesLastMeaningfulFolderAsLevel3() {
        val suggestion = BasicSuggestionClassifier.suggest(
            displayName = "cover.png",
            relativePath = "Pictures/Frieren/",
            mimeType = "image/png",
            mediaType = MediaType.IMAGE
        )

        assertEquals("Frieren", suggestion.level3)
    }

    @Test
    fun suggest_marksAnimeFolderAsAnimeRelated() {
        val suggestion = BasicSuggestionClassifier.suggest(
            displayName = "frieren_wallpaper.png",
            relativePath = "Pictures/Anime/Frieren/",
            mimeType = "image/png",
            mediaType = MediaType.IMAGE
        )

        assertEquals("애니 관련", suggestion.level1)
        assertEquals("애니 이미지", suggestion.level2)
    }

    @Test
    fun suggest_usesPersonFocusForRealPeopleInsteadOfCharacterFocus() {
        val suggestion = BasicSuggestionClassifier.suggest(
            displayName = "family_portrait.jpg",
            relativePath = "DCIM/People/",
            mimeType = "image/jpeg",
            mediaType = MediaType.IMAGE
        )

        assertEquals("사람", suggestion.level1)
        assertEquals("인물 중심", suggestion.level2)
    }
}
