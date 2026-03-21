package com.codex.ppa.data

import com.codex.ppa.domain.ClassificationLabels
import com.codex.ppa.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassificationPathPolicyTest {
    @Test
    fun `buildRelativePath creates media specific destination path`() {
        val path = ClassificationPathPolicy.buildRelativePath(
            mediaType = MediaType.IMAGE,
            labels = ClassificationLabels(
                level1 = "애니",
                level2 = "캐릭터",
                level3 = "원신"
            )
        )

        assertEquals("Pictures/PersonalMediaSorter/애니/캐릭터/원신/", path)
    }

    @Test
    fun `buildRelativePath sanitizes invalid path characters`() {
        val path = ClassificationPathPolicy.buildRelativePath(
            mediaType = MediaType.VIDEO,
            labels = ClassificationLabels(
                level1 = "게임",
                level2 = "스크린샷",
                level3 = "시리즈:/이름?"
            )
        )

        assertEquals("Movies/PersonalMediaSorter/게임/스크린샷/시리즈_이름/", path)
    }

    @Test
    fun `buildRelativePath returns null for empty labels`() {
        val path = ClassificationPathPolicy.buildRelativePath(
            mediaType = MediaType.IMAGE,
            labels = ClassificationLabels()
        )

        assertNull(path)
    }
}
