package com.codex.ppa

import android.content.Context
import androidx.work.WorkManager
import com.codex.ppa.data.AutoClassificationJobStore
import com.codex.ppa.data.ClassificationManifestFileStore
import com.codex.ppa.data.ClassificationRepository
import com.codex.ppa.data.MediaOrganizer
import com.codex.ppa.data.MediaStoreScanner
import com.codex.ppa.data.ThumbnailRepository
import com.codex.ppa.domain.ClassificationEngine
import com.codex.ppa.domain.GoogleDrivePlaceholderConnector
import com.codex.ppa.domain.OnDeviceAiClassificationEngine
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val manifestFile = File(appContext.filesDir, "classification-data/manifest.json")

    val mediaStoreScanner: MediaStoreScanner = MediaStoreScanner(appContext)
    val thumbnailRepository: ThumbnailRepository = ThumbnailRepository(appContext.contentResolver)
    val mediaOrganizer: MediaOrganizer = MediaOrganizer(appContext, mediaStoreScanner)
    val classificationStore: ClassificationManifestFileStore = ClassificationManifestFileStore(manifestFile)
    val classificationRepository: ClassificationRepository =
        ClassificationRepository(classificationStore)
    val autoClassificationJobStore: AutoClassificationJobStore = AutoClassificationJobStore(
        File(appContext.filesDir, "classification-data/auto-classification-jobs")
    )
    val classificationEngine: ClassificationEngine = OnDeviceAiClassificationEngine(appContext)
    val backupConnector = GoogleDrivePlaceholderConnector()
    val workManager: WorkManager = WorkManager.getInstance(appContext)
}
