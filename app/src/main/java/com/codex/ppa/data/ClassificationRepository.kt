package com.codex.ppa.data

import com.codex.ppa.domain.ClassificationEntry
import com.codex.ppa.domain.ClassificationDebugInfo
import com.codex.ppa.domain.ClassificationEngineInfo
import com.codex.ppa.domain.ClassificationLabels
import com.codex.ppa.domain.ClassificationManifest
import com.codex.ppa.domain.ClassificationSource
import com.codex.ppa.domain.MediaItem
import com.codex.ppa.domain.toFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class ClassificationRepository(
    private val store: ClassificationManifestFileStore
) {
    private val manifestMutex = Mutex()
    private val _manifest = MutableStateFlow(ClassificationManifest())
    val manifest: StateFlow<ClassificationManifest> = _manifest.asStateFlow()

    suspend fun load() {
        manifestMutex.withLock {
            _manifest.value = withContext(Dispatchers.IO) { store.load() }
        }
    }

    fun manifestSnapshot(): ClassificationManifest = _manifest.value

    fun storagePath(): String = store.manifestFile.absolutePath

    fun findClassification(
        mediaItem: MediaItem,
        entries: List<ClassificationEntry> = _manifest.value.entries
    ): ClassificationEntry? {
        return entries.firstOrNull { entry -> MediaIdentity.matches(entry, mediaItem) }
    }

    suspend fun upsert(
        mediaItem: MediaItem,
        labels: ClassificationLabels,
        source: ClassificationSource = ClassificationSource.MANUAL,
        engineInfo: ClassificationEngineInfo? = null,
        debugInfo: ClassificationDebugInfo? = null
    ) {
        upsertAll(
            writes = listOf(
                ClassificationWrite(
                    mediaItem = mediaItem,
                    labels = labels,
                    source = source,
                    engineInfo = engineInfo,
                    debugInfo = debugInfo
                )
            )
        )
    }

    suspend fun upsertAll(writes: List<ClassificationWrite>) {
        manifestMutex.withLock {
            val updatedManifest = buildUpdatedManifest(
                baseManifest = _manifest.value,
                writes = writes
            )
            withContext(Dispatchers.IO) {
                store.save(updatedManifest)
            }
            _manifest.value = updatedManifest
        }
    }

    suspend fun saveManifest(
        manifest: ClassificationManifest,
        publish: Boolean = true
    ) {
        manifestMutex.withLock {
            withContext(Dispatchers.IO) {
                store.save(manifest)
                if (publish) {
                    _manifest.value = manifest
                }
            }
        }
    }

    suspend fun importFrom(inputStream: InputStream) {
        manifestMutex.withLock {
            withContext(Dispatchers.IO) {
                val importedManifest = store.parseManifest(inputStream)
                store.save(importedManifest)
                _manifest.value = importedManifest
            }
        }
    }

    suspend fun exportTo(outputStream: OutputStream) {
        withContext(Dispatchers.IO) {
            store.writeManifest(outputStream, _manifest.value)
        }
    }

    fun buildUpdatedManifest(
        baseManifest: ClassificationManifest,
        writes: List<ClassificationWrite>
    ): ClassificationManifest {
        if (writes.isEmpty()) {
            return baseManifest
        }

        var updatedEntries = baseManifest.entries
        val updatedAt = System.currentTimeMillis()

        writes.forEach { write ->
            val normalizedLabels = write.labels.normalized()
            val newEntry = ClassificationEntry(
                recordId = write.mediaItem.recordId,
                fingerprint = write.mediaItem.toFingerprint(),
                classification = normalizedLabels,
                updatedAtEpochMs = updatedAt,
                source = write.source,
                engineInfo = write.engineInfo,
                debugInfo = write.debugInfo
            )

            updatedEntries = (updatedEntries
                .filterNot { existing ->
                    existing.recordId == write.mediaItem.recordId ||
                        MediaIdentity.matches(existing, write.mediaItem)
                } + newEntry).sortedByDescending(ClassificationEntry::updatedAtEpochMs)
        }

        return baseManifest.copy(
            exportedAtEpochMs = updatedAt,
            entries = updatedEntries
        )
    }
}

data class ClassificationWrite(
    val mediaItem: MediaItem,
    val labels: ClassificationLabels,
    val source: ClassificationSource = ClassificationSource.MANUAL,
    val engineInfo: ClassificationEngineInfo? = null,
    val debugInfo: ClassificationDebugInfo? = null
)
