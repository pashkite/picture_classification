package com.codex.ppa.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.codex.ppa.AppContainer

class MainViewModelFactory(
    private val appContainer: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(
                mediaStoreScanner = appContainer.mediaStoreScanner,
                mediaOrganizer = appContainer.mediaOrganizer,
                classificationRepository = appContainer.classificationRepository,
                autoClassificationJobStore = appContainer.autoClassificationJobStore,
                classificationEngine = appContainer.classificationEngine,
                backupConnector = appContainer.backupConnector,
                workManager = appContainer.workManager
            ) as T
        }
        throw IllegalArgumentException("알 수 없는 ViewModel 클래스입니다: ${modelClass.name}")
    }
}
