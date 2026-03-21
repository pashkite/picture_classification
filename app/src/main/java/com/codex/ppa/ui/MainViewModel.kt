package com.codex.ppa.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.util.Log
import com.codex.ppa.data.AutoClassificationJobSpec
import com.codex.ppa.data.AutoClassificationJobStore
import com.codex.ppa.data.ClassificationEntryLookup
import com.codex.ppa.data.ClassificationRepository
import com.codex.ppa.data.MediaOrganizer
import com.codex.ppa.data.MediaPermissionSnapshot
import com.codex.ppa.data.MediaStoreScanner
import com.codex.ppa.data.toQueuedMediaItem
import com.codex.ppa.domain.ClassificationEngine
import com.codex.ppa.domain.ClassificationEngineStatus
import com.codex.ppa.domain.ClassificationEngineInfo
import com.codex.ppa.domain.ClassificationLabels
import com.codex.ppa.domain.ClassificationSuggestion
import com.codex.ppa.domain.ClassificationSource
import com.codex.ppa.domain.CloudBackupConnector
import com.codex.ppa.domain.MediaItem
import com.codex.ppa.notifications.PromotedNotificationSupport
import com.codex.ppa.worker.AutoClassificationWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

val Level1Defaults = listOf(
    "사람",
    "셀카",
    "풍경",
    "음식",
    "반려동물",
    "문서",
    "영수증",
    "스크린샷",
    "그림",
    "일러스트",
    "애니 관련",
    "게임 관련",
    "밈",
    "기타"
)
val Level2Defaults = listOf(
    "애니 이미지",
    "게임 이미지",
    "일반 일러스트",
    "만화풍/웹툰풍",
    "팬아트 가능성",
    "캐릭터 중심",
    "UI 중심",
    "전투 화면",
    "대사/자막 중심",
    "로고/타이틀 화면",
    "문서 중심",
    "인물 중심",
    "검토 필요"
)

data class MediaListItemUiModel(
    val media: MediaItem,
    val entry: com.codex.ppa.domain.ClassificationEntry?
) {
    val classification: ClassificationLabels?
        get() = entry?.classification
}

data class PendingWritePermissionRequest(
    val requestId: Long = System.currentTimeMillis(),
    val uris: List<Uri>,
    val reason: String
)

data class MainUiState(
    val permissionSnapshot: MediaPermissionSnapshot = MediaPermissionSnapshot(false, false),
    val isScanning: Boolean = false,
    val mediaItems: List<MediaListItemUiModel> = emptyList(),
    val manifestEntryCount: Int = 0,
    val storagePath: String = "",
    val engineDisplayName: String = "",
    val engineStatus: ClassificationEngineStatus = ClassificationEngineStatus(
        engineId = "",
        displayName = ""
    ),
    val backupDisplayName: String = "",
    val lastStatusMessage: String? = null,
    val errorMessage: String? = null,
    val lastScanAtEpochMs: Long? = null,
    val manifestUpdatedAtEpochMs: Long? = null,
    val isAutoClassifying: Boolean = false,
    val autoClassificationProcessed: Int = 0,
    val autoClassificationTotal: Int = 0,
    val autoClassificationModeLabel: String? = null,
    val autoClassificationMovedCount: Int = 0,
    val autoClassificationResultStateLabel: String? = null,
    val autoClassificationResultSummary: String? = null,
    val savingRecordId: String? = null,
    val completedSaveRecordId: String? = null
)

private data class CombinedMainState(
    val permissionSnapshot: MediaPermissionSnapshot,
    val mediaItems: List<MediaItem>,
    val manifestEntryCount: Int,
    val storagePath: String,
    val engineDisplayName: String,
    val engineStatus: ClassificationEngineStatus,
    val backupDisplayName: String,
    val mappedClassifications: List<MediaListItemUiModel>,
    val isScanning: Boolean,
    val lastStatusMessage: String?,
    val errorMessage: String?,
    val lastScanAtEpochMs: Long?,
    val manifestUpdatedAtEpochMs: Long?
)

private data class AutoClassificationUiState(
    val isRunning: Boolean,
    val processed: Int,
    val total: Int,
    val modeLabel: String?,
    val movedCount: Int,
    val resultStateLabel: String?,
    val resultSummary: String?
)

private sealed interface PendingWriteAction {
    data class AutoClassification(val jobSpec: AutoClassificationJobSpec) : PendingWriteAction

    data class ManualSave(
        val originalRecordId: String,
        val mediaItem: MediaItem,
        val labels: ClassificationLabels,
        val source: ClassificationSource,
        val engineInfo: ClassificationEngineInfo?,
        val debugInfo: com.codex.ppa.domain.ClassificationDebugInfo?
    ) : PendingWriteAction
}

class MainViewModel(
    private val mediaStoreScanner: MediaStoreScanner,
    private val mediaOrganizer: MediaOrganizer,
    private val classificationRepository: ClassificationRepository,
    private val autoClassificationJobStore: AutoClassificationJobStore,
    private val classificationEngine: ClassificationEngine,
    private val backupConnector: CloudBackupConnector,
    private val workManager: WorkManager
) : ViewModel() {
    private val permissionSnapshot = MutableStateFlow(MediaPermissionSnapshot(false, false))
    private val scannedMedia = MutableStateFlow<List<MediaItem>>(emptyList())
    private val isScanning = MutableStateFlow(false)
    private val lastStatusMessage = MutableStateFlow<String?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val lastScanAtEpochMs = MutableStateFlow<Long?>(null)
    private val savingRecordId = MutableStateFlow<String?>(null)
    private val completedSaveRecordId = MutableStateFlow<String?>(null)

    private val isAutoClassifying = MutableStateFlow(false)
    private val autoClassificationProcessed = MutableStateFlow(0)
    private val autoClassificationTotal = MutableStateFlow(0)
    private val autoClassificationModeLabel = MutableStateFlow<String?>(null)
    private val autoClassificationMovedCount = MutableStateFlow(0)
    private val autoClassificationResultStateLabel = MutableStateFlow<String?>(null)
    private val autoClassificationResultSummary = MutableStateFlow<String?>(null)
    private val engineStatus = MutableStateFlow(classificationEngine.runtimeStatus())

    private val _pendingWritePermissionRequest = MutableStateFlow<PendingWritePermissionRequest?>(null)
    val pendingWritePermissionRequest: StateFlow<PendingWritePermissionRequest?> =
        _pendingWritePermissionRequest.asStateFlow()

    private val handledTerminalWorkIds = mutableSetOf<UUID>()
    private val autoClassificationWorkLiveData =
        workManager.getWorkInfosForUniqueWorkLiveData(AutoClassificationWorker.UNIQUE_WORK_NAME)
    private val autoClassificationWorkObserver = Observer<List<WorkInfo>>(::syncWorkState)

    private var pendingWriteAction: PendingWriteAction? = null
    private val suggestedResults = mutableMapOf<String, ClassificationSuggestion>()

    private val autoClassificationUiState = combine(
        isAutoClassifying,
        autoClassificationProcessed,
        autoClassificationTotal,
        autoClassificationModeLabel,
        autoClassificationMovedCount
    ) { isRunning, processed, total, modeLabel, movedCount ->
        AutoClassificationUiState(
            isRunning = isRunning,
            processed = processed,
            total = total,
            modeLabel = modeLabel,
            movedCount = movedCount,
            resultStateLabel = null,
            resultSummary = null
        )
    }.combine(autoClassificationResultStateLabel) { base, resultStateLabel ->
        base.copy(resultStateLabel = resultStateLabel)
    }.combine(autoClassificationResultSummary) { base, resultSummary ->
        base.copy(resultSummary = resultSummary)
    }

    val uiState: StateFlow<MainUiState> = combine(
        permissionSnapshot,
        scannedMedia,
        classificationRepository.manifest,
        isScanning,
        lastStatusMessage
    ) { permission, media, manifest, scanning, status ->
        val entryLookup = ClassificationEntryLookup(manifest.entries)
        CombinedMainState(
            permissionSnapshot = permission,
            mediaItems = media,
            manifestEntryCount = manifest.entries.size,
            storagePath = classificationRepository.storagePath(),
            engineDisplayName = classificationEngine.displayName,
            engineStatus = engineStatus.value,
            backupDisplayName = backupConnector.displayName,
            mappedClassifications = media.map { item ->
                MediaListItemUiModel(
                    media = item,
                    entry = entryLookup.find(item)
                )
            },
            isScanning = scanning,
            lastStatusMessage = status,
            errorMessage = null,
            lastScanAtEpochMs = null,
            manifestUpdatedAtEpochMs = manifest.exportedAtEpochMs
        )
    }.combine(engineStatus) { base, currentEngineStatus ->
        base.copy(engineStatus = currentEngineStatus)
    }.combine(errorMessage) { base, error ->
        base.copy(errorMessage = error)
    }.combine(lastScanAtEpochMs) { base, lastScan ->
        base.copy(lastScanAtEpochMs = lastScan)
    }.combine(autoClassificationUiState) { base, autoClassification ->
        MainUiState(
            permissionSnapshot = base.permissionSnapshot,
            isScanning = base.isScanning,
            mediaItems = base.mappedClassifications,
            manifestEntryCount = base.manifestEntryCount,
            storagePath = base.storagePath,
            engineDisplayName = base.engineDisplayName,
            engineStatus = base.engineStatus,
            backupDisplayName = base.backupDisplayName,
            lastStatusMessage = base.lastStatusMessage,
            errorMessage = base.errorMessage,
            lastScanAtEpochMs = base.lastScanAtEpochMs,
            manifestUpdatedAtEpochMs = base.manifestUpdatedAtEpochMs,
            isAutoClassifying = autoClassification.isRunning,
            autoClassificationProcessed = autoClassification.processed,
            autoClassificationTotal = autoClassification.total,
            autoClassificationModeLabel = autoClassification.modeLabel,
            autoClassificationMovedCount = autoClassification.movedCount,
            autoClassificationResultStateLabel = autoClassification.resultStateLabel,
            autoClassificationResultSummary = autoClassification.resultSummary
        )
    }.combine(savingRecordId) { base, activeSavingRecordId ->
        base.copy(savingRecordId = activeSavingRecordId)
    }.combine(completedSaveRecordId) { base, savedRecordId ->
        base.copy(completedSaveRecordId = savedRecordId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    init {
        autoClassificationWorkLiveData.observeForever(autoClassificationWorkObserver)
        viewModelScope.launch {
            classificationRepository.load()
            refreshEngineStatus()
        }
    }

    override fun onCleared() {
        autoClassificationWorkLiveData.removeObserver(autoClassificationWorkObserver)
        super.onCleared()
    }

    fun onPermissionSnapshotChanged(snapshot: MediaPermissionSnapshot) {
        val previous = permissionSnapshot.value
        permissionSnapshot.value = snapshot

        if (!snapshot.hasAnyAccess) {
            scannedMedia.value = emptyList()
            return
        }

        if (!previous.hasAnyAccess || scannedMedia.value.isEmpty()) {
            scanMedia()
        }
    }

    fun scanMedia() {
        scanMediaInternal(silent = false)
    }

    private fun scanMediaInternal(silent: Boolean) {
        val currentPermission = permissionSnapshot.value
        if (!currentPermission.hasAnyAccess || isScanning.value) {
            return
        }

        viewModelScope.launch {
            isScanning.value = true
            errorMessage.value = null
            runCatching {
                mediaStoreScanner.scan(currentPermission)
            }.onSuccess { items ->
                scannedMedia.value = items
                lastScanAtEpochMs.value = System.currentTimeMillis()
                if (!silent) {
                    lastStatusMessage.value = "${items.size}개의 미디어를 불러왔습니다."
                }
            }.onFailure { throwable ->
                errorMessage.value = throwable.message ?: "미디어를 불러오지 못했습니다."
            }
            isScanning.value = false
        }
    }

    fun mediaItem(recordId: String): MediaListItemUiModel? {
        return uiState.value.mediaItems.firstOrNull { it.media.recordId == recordId }
    }

    fun runAutomaticClassification(onlyUnclassified: Boolean) {
        if (isAutoClassifying.value) {
            Log.i(PromotedNotificationSupport.LogTag, "runAutomaticClassification ignored: already running")
            return
        }

        val mediaItems = scannedMedia.value
        if (mediaItems.isEmpty()) {
            Log.i(PromotedNotificationSupport.LogTag, "runAutomaticClassification aborted: no scanned media")
            lastStatusMessage.value = "자동분류할 미디어가 없습니다."
            return
        }

        val currentEntries = classificationRepository.manifest.value.entries
        val targetItems = if (onlyUnclassified) {
            mediaItems.filter { item ->
                classificationRepository.findClassification(item, currentEntries)?.classification?.isEmpty != false
            }
        } else {
            mediaItems
        }

        if (targetItems.isEmpty()) {
            Log.i(
                PromotedNotificationSupport.LogTag,
                "runAutomaticClassification aborted: no target items, onlyUnclassified=$onlyUnclassified"
            )
            lastStatusMessage.value = "자동분류할 대상이 없습니다."
            return
        }

        Log.i(
            PromotedNotificationSupport.LogTag,
            "runAutomaticClassification: onlyUnclassified=$onlyUnclassified, scanned=${mediaItems.size}, targets=${targetItems.size}"
        )

        val jobSpec = AutoClassificationJobSpec(
            jobId = UUID.randomUUID().toString(),
            onlyUnclassified = onlyUnclassified,
            moveFiles = true,
            mediaItems = targetItems.map { it.toQueuedMediaItem() }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingWriteAction = PendingWriteAction.AutoClassification(jobSpec)
            _pendingWritePermissionRequest.value = PendingWritePermissionRequest(
                uris = targetItems.map(MediaItem::contentUri),
                reason = "AI 자동분류 후 파일 이동"
            )
            lastStatusMessage.value = "파일 이동 승인을 받으면 백그라운드 분류를 시작합니다."
            return
        }

        enqueueAutoClassification(jobSpec)
    }

    fun cancelAutomaticClassification() {
        workManager.cancelUniqueWork(AutoClassificationWorker.UNIQUE_WORK_NAME)
        lastStatusMessage.value = "AI 자동분류 중지를 요청했습니다."
    }

    fun consumePendingWritePermissionRequest() {
        _pendingWritePermissionRequest.value = null
    }

    fun onWritePermissionResult(granted: Boolean) {
        when (val action = pendingWriteAction) {
            is PendingWriteAction.AutoClassification -> {
                val jobSpec = if (granted) {
                    action.jobSpec
                } else {
                    lastStatusMessage.value = "파일 이동 권한이 없어 분류만 백그라운드에서 수행합니다."
                    action.jobSpec.copy(moveFiles = false)
                }
                enqueueAutoClassification(jobSpec)
            }

            is PendingWriteAction.ManualSave -> {
                performSaveClassification(
                    originalRecordId = action.originalRecordId,
                    mediaItem = action.mediaItem,
                    labels = action.labels,
                    source = action.source,
                    engineInfo = action.engineInfo,
                    debugInfo = action.debugInfo,
                    allowMove = granted
                )
            }

            null -> Unit
        }

        pendingWriteAction = null
    }

    fun requestSaveClassification(
        recordId: String,
        level1: String,
        level2: String,
        level3: String
    ) {
        val mediaItem = scannedMedia.value.firstOrNull { it.recordId == recordId }
        if (mediaItem == null) {
            errorMessage.value = "선택한 미디어를 찾을 수 없습니다."
            return
        }

        val labels = ClassificationLabels(
            level1 = level1,
            level2 = level2,
            level3 = level3
        ).normalized()
        val suggestedResult = suggestedResults[recordId]
        val isSuggestedSave = suggestedResult?.labels?.normalized() == labels
        val source = if (isSuggestedSave) {
            ClassificationSource.MODEL_SUGGESTION
        } else {
            ClassificationSource.MANUAL
        }
        val engineInfo = suggestedResult?.engineInfo
        val debugInfo = suggestedResult?.debugInfo

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mediaOrganizer.needsMove(mediaItem, labels)) {
            pendingWriteAction = PendingWriteAction.ManualSave(
                originalRecordId = recordId,
                mediaItem = mediaItem,
                labels = labels,
                source = source,
                engineInfo = engineInfo,
                debugInfo = debugInfo
            )
            _pendingWritePermissionRequest.value = PendingWritePermissionRequest(
                uris = listOf(mediaItem.contentUri),
                reason = "분류 저장 후 파일 이동"
            )
            lastStatusMessage.value = "파일 이동 승인을 받으면 분류 저장과 이동을 이어서 진행합니다."
            return
        }

        performSaveClassification(
            originalRecordId = recordId,
            mediaItem = mediaItem,
            labels = labels,
            source = source,
            engineInfo = engineInfo,
            debugInfo = debugInfo,
            allowMove = true
        )
    }

    fun requestSuggestedClassification(
        recordId: String,
        onSuggestionReady: (ClassificationSuggestion) -> Unit,
        onFinished: () -> Unit
    ) {
        val mediaItem = scannedMedia.value.firstOrNull { it.recordId == recordId }
        if (mediaItem == null) {
            errorMessage.value = "선택한 미디어를 찾을 수 없습니다."
            onFinished()
            return
        }

        viewModelScope.launch {
            runCatching {
                classificationEngine.classify(mediaItem)
            }.onSuccess { suggestion ->
                refreshEngineStatus()
                val normalizedSuggestion = suggestion?.copy(
                    labels = suggestion.labels.normalized()
                )
                if (normalizedSuggestion == null || normalizedSuggestion.labels.isEmpty) {
                    lastStatusMessage.value = "현재 엔진이 제안할 분류를 만들지 못했습니다."
                } else {
                    suggestedResults[recordId] = normalizedSuggestion
                    onSuggestionReady(normalizedSuggestion)
                    lastStatusMessage.value = "${mediaItem.displayName} AI 자동분류 제안을 적용했습니다."
                }
                onFinished()
            }.onFailure { throwable ->
                refreshEngineStatus()
                errorMessage.value = throwable.message ?: "AI 자동분류 제안에 실패했습니다."
                onFinished()
            }
        }
    }

    fun exportToUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    classificationRepository.exportTo(outputStream)
                } ?: error("내보내기 파일을 열 수 없습니다.")
            }.onSuccess {
                lastStatusMessage.value = "분류 데이터를 내보냈습니다."
            }.onFailure { throwable ->
                errorMessage.value = throwable.message ?: "내보내기에 실패했습니다."
            }
        }
    }

    fun importFromUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    classificationRepository.importFrom(inputStream)
                } ?: error("가져오기 파일을 열 수 없습니다.")
            }.onSuccess {
                lastStatusMessage.value = "분류 데이터를 가져왔습니다."
            }.onFailure { throwable ->
                errorMessage.value = throwable.message ?: "가져오기에 실패했습니다."
            }
        }
    }

    fun consumeCompletedSave(recordId: String) {
        if (completedSaveRecordId.value == recordId) {
            completedSaveRecordId.value = null
        }
    }

    fun clearTransientMessages() {
        lastStatusMessage.value = null
        errorMessage.value = null
    }

    private fun refreshEngineStatus() {
        engineStatus.value = classificationEngine.runtimeStatus()
    }

    private fun performSaveClassification(
        originalRecordId: String,
        mediaItem: MediaItem,
        labels: ClassificationLabels,
        source: ClassificationSource,
        engineInfo: ClassificationEngineInfo?,
        debugInfo: com.codex.ppa.domain.ClassificationDebugInfo?,
        allowMove: Boolean
    ) {
        viewModelScope.launch {
            savingRecordId.value = originalRecordId
            val moveWasRequested = mediaOrganizer.needsMove(mediaItem, labels)
            runCatching {
                classificationRepository.upsert(
                    mediaItem = mediaItem,
                    labels = labels,
                    source = source,
                    engineInfo = engineInfo,
                    debugInfo = debugInfo
                )

                var moved = false
                if (allowMove && mediaOrganizer.needsMove(mediaItem, labels)) {
                    val moveResult = mediaOrganizer.moveToClassification(
                        mediaItem = mediaItem,
                        labels = labels
                    )
                    if (moveResult.moved) {
                        moved = true
                        classificationRepository.upsert(
                            mediaItem = moveResult.mediaItem,
                            labels = labels,
                            source = source,
                            engineInfo = engineInfo,
                            debugInfo = debugInfo
                        )
                    }
                }

                moved
            }.onSuccess { moved ->
                completedSaveRecordId.value = originalRecordId
                suggestedResults.remove(originalRecordId)
                if (moved) {
                    lastStatusMessage.value = "${mediaItem.displayName} 분류를 저장하고 파일도 이동했습니다."
                    scanMediaInternal(silent = true)
                } else if (moveWasRequested && !allowMove) {
                    lastStatusMessage.value = "${mediaItem.displayName} 분류를 저장했습니다. 파일 이동은 권한 없이 건너뛰었습니다."
                } else {
                    lastStatusMessage.value = "${mediaItem.displayName} 분류를 저장했습니다."
                }
            }.onFailure { throwable ->
                errorMessage.value = throwable.message ?: "분류 저장에 실패했습니다."
            }
            savingRecordId.value = null
        }
    }

    private fun enqueueAutoClassification(jobSpec: AutoClassificationJobSpec) {
        autoClassificationJobStore.save(jobSpec)
        val request = AutoClassificationWorker.buildRequest(jobSpec.jobId)
        autoClassificationResultStateLabel.value = null
        autoClassificationResultSummary.value = null
        Log.i(
            PromotedNotificationSupport.LogTag,
            "enqueueAutoClassification: jobId=${jobSpec.jobId}, mode=${jobSpec.modeLabel}, moveFiles=${jobSpec.moveFiles}, mediaCount=${jobSpec.mediaItems.size}"
        )
        workManager.enqueueUniqueWork(
            AutoClassificationWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        lastStatusMessage.value = buildString {
            append(jobSpec.modeLabel)
            append(" AI 자동분류를 백그라운드에서 시작했습니다.")
            if (jobSpec.moveFiles) {
                append(" 분류 후 파일 이동까지 진행합니다.")
            }
        }
    }

    private fun syncWorkState(workInfos: List<WorkInfo>) {
        refreshEngineStatus()
        val activeWork = workInfos.firstOrNull { !it.state.isFinished }
        if (activeWork != null) {
            isAutoClassifying.value = true
            autoClassificationProcessed.value =
                activeWork.progress.getInt(AutoClassificationWorker.PROGRESS_PROCESSED, 0)
            autoClassificationTotal.value =
                activeWork.progress.getInt(AutoClassificationWorker.PROGRESS_TOTAL, 0)
            autoClassificationModeLabel.value =
                activeWork.progress.getString(AutoClassificationWorker.PROGRESS_MODE_LABEL)
            autoClassificationMovedCount.value =
                activeWork.progress.getInt(AutoClassificationWorker.PROGRESS_MOVED, 0)
            autoClassificationResultStateLabel.value = "진행 중"
            autoClassificationResultSummary.value = null
            return
        }

        isAutoClassifying.value = false

        val latestFinishedWork = workInfos
            .filter { it.state.isFinished }
            .maxByOrNull { it.outputData.getLong(AutoClassificationWorker.OUTPUT_COMPLETED_AT, 0L) }
            ?: return

        autoClassificationProcessed.value =
            latestFinishedWork.outputData.getInt(AutoClassificationWorker.OUTPUT_PROCESSED, 0)
        autoClassificationTotal.value =
            latestFinishedWork.outputData.getInt(AutoClassificationWorker.OUTPUT_TOTAL, 0)
        autoClassificationModeLabel.value =
            latestFinishedWork.outputData.getString(AutoClassificationWorker.OUTPUT_MODE_LABEL)
        autoClassificationMovedCount.value =
            latestFinishedWork.outputData.getInt(AutoClassificationWorker.OUTPUT_MOVED, 0)

        if (!handledTerminalWorkIds.add(latestFinishedWork.id)) {
            return
        }

        when (latestFinishedWork.state) {
            WorkInfo.State.SUCCEEDED -> {
                val summary = latestFinishedWork.outputData
                    .getString(AutoClassificationWorker.OUTPUT_SUMMARY)
                    ?: "AI 자동분류가 완료되었습니다."
                autoClassificationResultStateLabel.value = "완료"
                autoClassificationResultSummary.value = summary
                lastStatusMessage.value = summary
                val movedCount = latestFinishedWork.outputData
                    .getInt(AutoClassificationWorker.OUTPUT_MOVED, 0)
                viewModelScope.launch {
                    classificationRepository.load()
                    if (movedCount > 0) {
                        scanMediaInternal(silent = true)
                    }
                }
            }

            WorkInfo.State.CANCELLED -> {
                autoClassificationResultStateLabel.value = "중지됨"
                autoClassificationResultSummary.value = "사용자 요청으로 자동분류를 중지했다."
                lastStatusMessage.value = "AI 자동분류가 중지되었습니다."
            }

            WorkInfo.State.FAILED -> {
                val summary = latestFinishedWork.outputData
                    .getString(AutoClassificationWorker.OUTPUT_SUMMARY)
                    ?: "AI 자동분류가 실패했습니다."
                autoClassificationResultStateLabel.value = "실패"
                autoClassificationResultSummary.value = summary
                errorMessage.value = summary
            }

            else -> Unit
        }
    }
}
