package com.codex.ppa.data

import com.codex.ppa.domain.ClassificationEntry
import com.codex.ppa.domain.ClassificationLabels
import com.codex.ppa.domain.ClassificationManifest
import com.codex.ppa.domain.ModelDebugInfo
import com.codex.ppa.domain.MediaFingerprint
import com.codex.ppa.domain.MediaType
import com.codex.ppa.domain.ClassificationDebugInfo
import com.codex.ppa.domain.ScoredSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ClassificationManifestFileStoreTest {
    @Test
    fun saveAndLoad_roundTripsEntries() {
        val tempDir = Files.createTempDirectory("manifest-store-test").toFile()
        val store = ClassificationManifestFileStore(tempDir.resolve("manifest.json"))
        val manifest = ClassificationManifest(
            entries = listOf(
                ClassificationEntry(
                    recordId = "record-1",
                    fingerprint = sampleFingerprint(contentUri = "content://media/1"),
                    classification = ClassificationLabels(
                        level1 = "애니",
                        level2 = "캐릭터",
                        level3 = "테스트 작품"
                    ),
                    debugInfo = ClassificationDebugInfo(
                        confidence = 0.82f,
                        reasoning = listOf("MediaPipe classifier hit"),
                        finalScores = listOf(ScoredSignal("애니 관련", 1.4f)),
                        modelOutputs = listOf(
                            ModelDebugInfo(
                                modelId = "mediapipe-semantic",
                                displayName = "MediaPipe 의미 분류",
                                summary = "classifier + embedder"
                            )
                        )
                    )
                )
            )
        )

        store.save(manifest)

        val loaded = store.load()
        assertEquals(1, loaded.entries.size)
        assertEquals("애니", loaded.entries.first().classification.level1)
        assertEquals("테스트 작품", loaded.entries.first().classification.level3)
        assertEquals(0.82f, loaded.entries.first().debugInfo?.confidence ?: 0f, 0.001f)
    }

    @Test
    fun parseManifest_normalizesBlankRecordId() {
        val tempDir = Files.createTempDirectory("manifest-store-normalize-test").toFile()
        val store = ClassificationManifestFileStore(tempDir.resolve("manifest.json"))
        val rawJson = """
            {
              "schemaVersion": 1,
              "entries": [
                {
                  "recordId": "",
                  "fingerprint": {
                    "mediaType": "IMAGE",
                    "contentUri": "content://media/external/images/media/23",
                    "mediaStoreId": 23,
                    "displayName": "sample.jpg",
                    "relativePath": "Pictures/Sample/",
                    "mimeType": "image/jpeg",
                    "sizeBytes": 1200,
                    "dateModifiedEpochSeconds": 1000,
                    "dateAddedEpochSeconds": 900,
                    "width": 1080,
                    "height": 720,
                    "durationMs": null
                  },
                  "classification": {
                    "level1": "실사",
                    "level2": "일반",
                    "level3": ""
                  },
                  "updatedAtEpochMs": 1000,
                  "source": "MANUAL",
                  "engineInfo": null
                }
              ]
            }
        """.trimIndent()

        val manifest = store.parseManifest(rawJson.byteInputStream())

        assertTrue(manifest.entries.first().recordId.isNotBlank())
    }

    private fun sampleFingerprint(contentUri: String): MediaFingerprint {
        return MediaFingerprint(
            mediaType = MediaType.IMAGE,
            contentUri = contentUri,
            mediaStoreId = 1,
            displayName = "sample.jpg",
            relativePath = "Pictures/",
            mimeType = "image/jpeg",
            sizeBytes = 1024,
            dateModifiedEpochSeconds = 100,
            dateAddedEpochSeconds = 90,
            width = 640,
            height = 480,
            durationMs = null
        )
    }
}
