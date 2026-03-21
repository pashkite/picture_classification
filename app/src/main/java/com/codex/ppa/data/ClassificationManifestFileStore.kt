package com.codex.ppa.data

import com.codex.ppa.domain.ClassificationEntry
import com.codex.ppa.domain.ClassificationManifest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class ClassificationManifestFileStore(
    val manifestFile: File
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): ClassificationManifest {
        if (!manifestFile.exists()) {
            return ClassificationManifest()
        }

        return manifestFile.inputStream().use { parseManifest(it) }
    }

    fun save(manifest: ClassificationManifest) {
        val normalized = normalize(manifest)
        manifestFile.parentFile?.mkdirs()
        val tempFile = File(manifestFile.parentFile, "${manifestFile.name}.tmp")
        tempFile.writeText(json.encodeToString(normalized))

        if (manifestFile.exists() && !manifestFile.delete()) {
            throw IllegalStateException("기존 manifest.json 파일을 교체할 수 없습니다.")
        }
        if (!tempFile.renameTo(manifestFile)) {
            throw IllegalStateException("manifest.json 파일을 저장할 수 없습니다.")
        }
    }

    fun parseManifest(inputStream: InputStream): ClassificationManifest {
        val raw = inputStream.bufferedReader().use { it.readText() }
        return normalize(json.decodeFromString(raw))
    }

    fun writeManifest(outputStream: OutputStream, manifest: ClassificationManifest) {
        outputStream.bufferedWriter().use { writer ->
            writer.write(json.encodeToString(normalize(manifest)))
        }
    }

    private fun normalize(manifest: ClassificationManifest): ClassificationManifest {
        val normalizedEntries = manifest.entries
            .map { entry ->
                if (entry.recordId.isBlank()) {
                    entry.copy(recordId = MediaIdentity.buildRecordId(entry.fingerprint))
                } else {
                    entry
                }
            }
            .groupBy(ClassificationEntry::recordId)
            .mapNotNull { (_, entries) -> entries.maxByOrNull(ClassificationEntry::updatedAtEpochMs) }
            .sortedByDescending(ClassificationEntry::updatedAtEpochMs)

        return manifest.copy(
            schemaVersion = if (manifest.schemaVersion < 2) 2 else manifest.schemaVersion,
            exportedAtEpochMs = if (manifest.exportedAtEpochMs == 0L) {
                System.currentTimeMillis()
            } else {
                manifest.exportedAtEpochMs
            },
            formatName = if (manifest.formatName.isBlank()) "manifest.json" else manifest.formatName,
            entries = normalizedEntries
        )
    }
}
