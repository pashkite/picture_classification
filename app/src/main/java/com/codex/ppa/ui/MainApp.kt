package com.codex.ppa.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.codex.ppa.appContainer
import com.codex.ppa.data.MediaPermissions
import com.codex.ppa.domain.ClassificationDebugInfo
import com.codex.ppa.domain.ClassificationLabels
import com.codex.ppa.domain.ClassificationSuggestion
import com.codex.ppa.domain.ModelRuntimeStatus
import com.codex.ppa.domain.MediaItem
import com.codex.ppa.domain.MediaType
import com.codex.ppa.notifications.PromotedNotificationSupport
import com.codex.ppa.worker.AutoClassificationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object AppRoute {
    const val MediaList = "media-list"
    const val Settings = "settings"
    const val EditPattern = "edit/{recordId}"
    fun edit(recordId: String): String = "edit/$recordId"
}

@Composable
fun PersonalMediaSorterApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()
    val pendingWritePermissionRequest by viewModel.pendingWritePermissionRequest.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var initialPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var pendingAutoClassificationMode by rememberSaveable { mutableStateOf<Boolean?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onPermissionSnapshotChanged(MediaPermissions.currentSnapshot(context))
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.exportToUri(context.contentResolver, uri)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFromUri(context.contentResolver, uri)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        PromotedNotificationSupport.logRuntimeState(
            context = context,
            channelId = AutoClassificationWorker.NOTIFICATION_CHANNEL_ID,
            reason = "notification_permission_result"
        )
        val mode = pendingAutoClassificationMode
        pendingAutoClassificationMode = null
        if (mode != null) {
            viewModel.runAutomaticClassification(mode)
        }
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onWritePermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(Unit) {
        AutoClassificationWorker.ensureNotificationChannel(context)
        PromotedNotificationSupport.logRuntimeState(
            context = context,
            channelId = AutoClassificationWorker.NOTIFICATION_CHANNEL_ID,
            reason = "app_start"
        )
        viewModel.onPermissionSnapshotChanged(MediaPermissions.currentSnapshot(context))
    }

    LaunchedEffect(uiState.permissionSnapshot.hasAnyAccess) {
        if (!uiState.permissionSnapshot.hasAnyAccess && !initialPermissionRequested) {
            initialPermissionRequested = true
            permissionLauncher.launch(MediaPermissions.requiredPermissions())
        }
    }

    LaunchedEffect(uiState.lastStatusMessage, uiState.errorMessage) {
        val message = uiState.errorMessage ?: uiState.lastStatusMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearTransientMessages()
        }
    }

    LaunchedEffect(pendingWritePermissionRequest?.requestId) {
        val request = pendingWritePermissionRequest ?: return@LaunchedEffect
        viewModel.consumePendingWritePermissionRequest()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            viewModel.onWritePermissionResult(true)
            return@LaunchedEffect
        }

        val intentSender = runCatching {
            MediaStore.createWriteRequest(context.contentResolver, request.uris).intentSender
        }.getOrElse {
            viewModel.onWritePermissionResult(false)
            return@LaunchedEffect
        }

        writePermissionLauncher.launch(
            IntentSenderRequest.Builder(intentSender).build()
        )
    }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val currentEditItem = currentBackStackEntry?.arguments?.getString("recordId")
        ?.let(viewModel::mediaItem)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                currentRoute = currentRoute,
                currentEditItemName = currentEditItem?.media?.displayName,
                onNavigateBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(AppRoute.Settings) },
                onRescan = viewModel::scanMedia
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppRoute.MediaList,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(AppRoute.MediaList) {
                MediaListScreen(
                    uiState = uiState,
                    onRequestPermission = {
                        permissionLauncher.launch(MediaPermissions.requiredPermissions())
                    },
                    onRequestNotificationPermission = {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onOpenAppSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                        )
                    },
                    onRescan = viewModel::scanMedia,
                    onOpenNotificationSettings = {
                        val opened = openBestEffortSettings(
                            context = context,
                            candidates = listOf(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            )
                        )
                        if (!opened) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                            )
                        }
                    },
                    onOpenPromotedNotificationSettings = {
                        val opened = openBestEffortSettings(
                            context = context,
                            candidates = buildList {
                                if (Build.VERSION.SDK_INT >= 36) {
                                    add(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                    )
                                }
                                add(
                                    Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                )
                                add(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                )
                            }
                        )
                        if (!opened) {
                            Toast.makeText(
                                context,
                                "이 기기에는 고급 진행 알림 전용 설정 화면이 없어 일반 앱 알림 설정을 연다.",
                                Toast.LENGTH_LONG
                            ).show()
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                            )
                        }
                    },
                    onRunAutoClassification = { onlyUnclassified ->
                        if (requiresNotificationPermission(context)) {
                            pendingAutoClassificationMode = onlyUnclassified
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.runAutomaticClassification(onlyUnclassified)
                        }
                    },
                    onCancelAutoClassification = viewModel::cancelAutomaticClassification,
                    onOpenEditor = { recordId -> navController.navigate(AppRoute.edit(recordId)) }
                )
            }

            composable(
                route = AppRoute.EditPattern,
                arguments = listOf(navArgument("recordId") { type = NavType.StringType })
            ) { backStackEntry ->
                val recordId = backStackEntry.arguments?.getString("recordId").orEmpty()

                LaunchedEffect(uiState.completedSaveRecordId, recordId) {
                    if (uiState.completedSaveRecordId == recordId) {
                        navController.popBackStack()
                        viewModel.consumeCompletedSave(recordId)
                    }
                }

                ClassificationEditScreen(
                    item = viewModel.mediaItem(recordId),
                    engineDisplayName = uiState.engineDisplayName,
                    engineModels = uiState.engineStatus.models,
                    isSaving = uiState.savingRecordId == recordId,
                    onRequestAutoClassification = { onSuggestionReady, onFinished ->
                        viewModel.requestSuggestedClassification(
                            recordId = recordId,
                            onSuggestionReady = onSuggestionReady,
                            onFinished = onFinished
                        )
                    },
                    onSave = { level1, level2, level3 ->
                        viewModel.requestSaveClassification(recordId, level1, level2, level3)
                    }
                )
            }

            composable(AppRoute.Settings) {
                SettingsScreen(
                    uiState = uiState,
                    supportsDedicatedPromotedSettings = supportsDedicatedPromotedSettings(context),
                    onRequestNotificationPermission = {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onOpenNotificationSettings = {
                        val opened = openBestEffortSettings(
                            context = context,
                            candidates = listOf(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            )
                        )
                        if (!opened) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                            )
                        }
                    },
                    onOpenPromotedNotificationSettings = {
                        val opened = openBestEffortSettings(
                            context = context,
                            candidates = buildList {
                                if (Build.VERSION.SDK_INT >= 36) {
                                    add(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                    )
                                }
                                add(
                                    Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                )
                                add(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                )
                            }
                        )
                        if (!opened) {
                            Toast.makeText(
                                context,
                                "이 기기에는 고급 진행 알림 전용 설정 화면이 없어 일반 앱 알림 설정을 연다.",
                                Toast.LENGTH_LONG
                            ).show()
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                            )
                        }
                    },
                    onExport = {
                        exportLauncher.launch("ppa-classification-manifest.json")
                    },
                    onImport = {
                        importLauncher.launch(arrayOf("application/json", "*/*"))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    currentRoute: String?,
    currentEditItemName: String?,
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRescan: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = when (currentRoute) {
                    AppRoute.MediaList -> "개인 미디어 분류기"
                    AppRoute.Settings -> "설정"
                    AppRoute.EditPattern -> currentEditItemName ?: "분류 편집"
                    else -> "개인 미디어 분류기"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            if (currentRoute == AppRoute.Settings || currentRoute == AppRoute.EditPattern) {
                TextButton(onClick = onNavigateBack) {
                    Text("뒤로")
                }
            }
        },
        actions = {
            when (currentRoute) {
                AppRoute.MediaList -> {
                    TextButton(onClick = onRescan) {
                        Text("재스캔")
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text("설정")
                    }
                }
            }
        }
    )
}

@Composable
private fun MediaListScreen(
    uiState: MainUiState,
    onRequestPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRescan: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenPromotedNotificationSettings: () -> Unit,
    onRunAutoClassification: (Boolean) -> Unit,
    onCancelAutoClassification: () -> Unit,
    onOpenEditor: (String) -> Unit
) {
    val context = LocalContext.current
    val notificationAttention = notificationAttentionState(
        context = context
    )

    if (!uiState.permissionSnapshot.hasAnyAccess) {
        PermissionRequiredScreen(
            onRequestPermission = onRequestPermission,
            onOpenAppSettings = onOpenAppSettings
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OverviewCard(
                title = "라이브러리 상태",
                lines = buildList {
                    add("읽은 미디어: ${uiState.mediaItems.size}개")
                    add("저장된 분류 레코드: ${uiState.manifestEntryCount}개")
                    add(
                        if (uiState.permissionSnapshot.hasFullAccess) {
                            "권한 상태: 사진/동영상 전체 접근 허용"
                        } else {
                            "권한 상태: 일부만 허용됨"
                        }
                    )
                    uiState.lastScanAtEpochMs?.let { add("마지막 스캔: ${formatTimestamp(it)}") }
                },
                action = {
                    FilledTonalButton(onClick = onRescan) {
                        Text("목록 갱신")
                    }
                }
            )
        }

        notificationAttention?.let { attention ->
            item {
                OverviewCard(
                    title = attention.title,
                    lines = attention.lines,
                    action = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (attention.showPermissionRequest) {
                                FilledTonalButton(onClick = onRequestNotificationPermission) {
                                    Text("알림 권한 요청")
                                }
                            }
                            OutlinedButton(onClick = onOpenNotificationSettings) {
                                Text("앱 알림 설정")
                            }
                            if (attention.showPromotedSettings) {
                                OutlinedButton(onClick = onOpenPromotedNotificationSettings) {
                                    Text("고급 진행 알림 설정")
                                }
                            }
                        }
                    }
                )
            }
        }

        item {
            OverviewCard(
                title = "AI 자동분류",
                lines = listOf(
                    "현재 엔진: ${uiState.engineDisplayName}",
                    if (uiState.engineStatus.reducedMode) {
                        "추가 비전 모델 로딩에 실패해 일부 항목은 축소 모드로 처리될 수 있다."
                    } else {
                        "EfficientNet-Lite4와 MobileCLIP2-S0가 의미 분류를 맡고, ImageEmbedder와 ML Kit가 스타일·얼굴·OCR 보조 신호를 더한다."
                    },
                    "주 분류기는 generic ImageNet 계열이라 애니·게임·작품명은 낮은 확신도에서 기타/검토 필요로 보수적으로 폴백한다.",
                    "앱을 닫아도 WorkManager 기반 백그라운드 작업으로 계속 진행한다.",
                    "분류가 끝난 항목은 카테고리 기준으로 실제 파일 경로까지 이동한다.",
                    "진행 중에는 알림창에서 상태를 보고, 완료되면 별도 완료 알림이 남는다.",
                    "수동 검토 전이라면 미분류만 채우는 안전 모드를 먼저 쓰는 편이 낫다."
                ),
                action = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = { onRunAutoClassification(false) },
                            enabled = uiState.mediaItems.isNotEmpty() && !uiState.isAutoClassifying
                        ) {
                            Text("전체 AI 자동분류")
                        }
                        OutlinedButton(
                            onClick = { onRunAutoClassification(true) },
                            enabled = uiState.mediaItems.isNotEmpty() && !uiState.isAutoClassifying
                        ) {
                            Text("미분류만 AI 자동분류")
                        }
                        val hasAutoClassificationSnapshot =
                            uiState.autoClassificationTotal > 0 ||
                                !uiState.autoClassificationResultSummary.isNullOrBlank()
                        if (uiState.isAutoClassifying) {
                            val progress = if (uiState.autoClassificationTotal > 0) {
                                uiState.autoClassificationProcessed.toFloat() /
                                    uiState.autoClassificationTotal.toFloat()
                            } else {
                                null
                            }
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                progress = { progress?.coerceIn(0f, 1f) ?: 0f }
                            )
                            Text(
                                text = buildString {
                                    append(uiState.autoClassificationModeLabel ?: "전체")
                                    append(" 진행 중: ")
                                    append(uiState.autoClassificationProcessed)
                                    append(" / ")
                                    append(uiState.autoClassificationTotal)
                                    append(" · 이동 ")
                                    append(uiState.autoClassificationMovedCount)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(onClick = onCancelAutoClassification) {
                                Text("작업 중지")
                            }
                        } else if (hasAutoClassificationSnapshot) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = buildString {
                                            append(uiState.autoClassificationResultStateLabel ?: "최근 결과")
                                            append(": ")
                                            append(uiState.autoClassificationModeLabel ?: "전체")
                                            append(" ")
                                            append(uiState.autoClassificationProcessed)
                                            append(" / ")
                                            append(uiState.autoClassificationTotal)
                                            append(" · 이동 ")
                                            append(uiState.autoClassificationMovedCount)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    uiState.autoClassificationResultSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                                        Text(
                                            text = summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }

        if (!uiState.permissionSnapshot.hasFullAccess) {
            item {
                OverviewCard(
                    title = "일부 권한만 허용됨",
                    lines = listOf(
                        "현재는 허용된 종류만 목록에 보인다.",
                        "사진과 동영상을 모두 관리하려면 권한을 다시 요청하라."
                    ),
                    action = {
                        OutlinedButton(onClick = onRequestPermission) {
                            Text("권한 다시 요청")
                        }
                    }
                )
            }
        }

        if (uiState.isScanning) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        if (!uiState.isScanning && uiState.mediaItems.isEmpty()) {
            item {
                OverviewCard(
                    title = "표시할 미디어가 없음",
                    lines = listOf(
                        "기기 저장소에 접근 가능한 사진 또는 동영상이 없거나",
                        "선택한 권한 범위 안에서 아직 찾지 못했다."
                    ),
                    action = {
                        FilledTonalButton(onClick = onRescan) {
                            Text("다시 스캔")
                        }
                    }
                )
            }
        }

        items(
            items = uiState.mediaItems,
            key = { item -> item.media.recordId }
        ) { item ->
            MediaListCard(
                item = item,
                onClick = { onOpenEditor(item.media.recordId) }
            )
        }
    }
}

@Composable
private fun PermissionRequiredScreen(
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "사진과 동영상 권한이 필요하다",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "이 앱은 MediaStore를 통해 기기 내부 미디어를 읽고, 각 항목의 3단계 분류를 로컬 manifest.json 파일로 저장한다.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRequestPermission) {
            Text("권한 요청")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenAppSettings) {
            Text("앱 설정 열기")
        }
    }
}

@Composable
private fun MediaListCard(
    item: MediaListItemUiModel,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MediaThumbnail(
                mediaItem = item.media,
                modifier = Modifier.size(92.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.media.displayName.ifBlank { "(이름 없음)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(if (item.media.mediaType == MediaType.IMAGE) "이미지" else "동영상")
                        append(" · ")
                        append(formatSize(context, item.media.sizeBytes))
                        append(" · ")
                        append(formatTimestamp(item.media.dateModifiedEpochSeconds * 1_000))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.media.relativePath.orEmpty().ifBlank { "경로 정보 없음" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.media.durationMs != null) {
                    Text(
                        text = "재생 길이: ${DateUtils.formatElapsedTime(item.media.durationMs / 1_000)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ClassificationSummary(item.classification)
            }
        }
    }
}

@Composable
private fun ClassificationSummary(classification: ClassificationLabels?) {
    val summary = classification
        ?.takeUnless { it.isEmpty }
        ?.let {
            listOf(it.level1, it.level2, it.level3)
                .filter { value -> value.isNotBlank() }
                .joinToString(separator = " / ")
        }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = summary ?: "분류 없음",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClassificationEditScreen(
    item: MediaListItemUiModel?,
    engineDisplayName: String,
    engineModels: List<ModelRuntimeStatus>,
    isSaving: Boolean,
    onRequestAutoClassification: (
        onSuggestionReady: (ClassificationSuggestion) -> Unit,
        onFinished: () -> Unit
    ) -> Unit,
    onSave: (String, String, String) -> Unit
) {
    val context = LocalContext.current

    if (item == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("선택한 미디어를 찾을 수 없다.")
        }
        return
    }

    var level1 by remember(item.media.recordId) {
        mutableStateOf(item.classification?.level1.orEmpty())
    }
    var level2 by remember(item.media.recordId) {
        mutableStateOf(item.classification?.level2.orEmpty())
    }
    var level3 by remember(item.media.recordId) {
        mutableStateOf(item.classification?.level3.orEmpty())
    }
    var isSuggesting by remember(item.media.recordId) {
        mutableStateOf(false)
    }
    var latestSuggestion by remember(item.media.recordId) {
        mutableStateOf<ClassificationSuggestion?>(null)
    }
    val activeDebugInfo = latestSuggestion?.debugInfo ?: item.entry?.debugInfo

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MediaThumbnail(
                    mediaItem = item.media,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
                Text(
                    text = item.media.displayName.ifBlank { "(이름 없음)" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                MetadataLine("유형", if (item.media.mediaType == MediaType.IMAGE) "이미지" else "동영상")
                MetadataLine("상대 경로", item.media.relativePath.orEmpty().ifBlank { "경로 정보 없음" })
                MetadataLine("크기", formatSize(context, item.media.sizeBytes))
                MetadataLine(
                    "수정 시각",
                    formatTimestamp(item.media.dateModifiedEpochSeconds * 1_000)
                )
                MetadataLine("식별 키", item.media.recordId.take(16) + "…")
            }
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "3단계 분류 입력",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "1차와 2차는 기본 후보를 제공하고, 직접 입력으로 덮어쓸 수 있다. 3차는 작품명·게임명·시리즈명 등 확장용 자유 입력이다. 저장하면 manifest에 기록하고 가능한 경우 실제 파일도 해당 분류 폴더로 이동한다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "자동분류 제안",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "현재 엔진: $engineDisplayName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "버튼을 누르면 EfficientNet-Lite4, MobileCLIP2-S0, ImageEmbedder, ML Kit 보조 신호를 함께 사용해 1차~3차 초안을 채운다. generic 분류기와 CLIP prompt 매칭을 함께 쓰므로, 확신이 낮으면 검토 필요로 폴백할 수 있고 저장 전에 자유롭게 수정할 수 있다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        FilledTonalButton(
                            onClick = {
                                isSuggesting = true
                                onRequestAutoClassification(
                                    { suggestion ->
                                        latestSuggestion = suggestion
                                        level1 = suggestion.labels.level1
                                        level2 = suggestion.labels.level2
                                        level3 = suggestion.labels.level3
                                    },
                                    {
                                        isSuggesting = false
                                    }
                                )
                            },
                            enabled = !isSuggesting
                        ) {
                            Text(
                                if (isSuggesting) {
                                    "AI 자동분류 제안 생성 중..."
                                } else {
                                    "AI 자동분류 제안 적용"
                                }
                            )
                        }
                    }
                }

                if (engineModels.isNotEmpty()) {
                    Text(
                        text = "활성 모델: ${engineModels.joinToString { model -> "${model.displayName}(${if (model.loaded) "ready" else "standby"})" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ClassificationField(
                    title = "1차 분류",
                    value = level1,
                    suggestions = Level1Defaults,
                    onValueChange = { level1 = it }
                )

                ClassificationField(
                    title = "2차 분류",
                    value = level2,
                    suggestions = Level2Defaults,
                    onValueChange = { level2 = it }
                )

                OutlinedTextField(
                    value = level3,
                    onValueChange = { level3 = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("3차 분류") },
                    placeholder = { Text("작품명, 게임명, 시리즈명 등 자유 입력") },
                    singleLine = true
                )
            }
        }

        ClassificationDebugCard(debugInfo = activeDebugInfo)

        Button(
            onClick = { onSave(level1.trim(), level2.trim(), level3.trim()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaving
        ) {
            Text(if (isSaving) "분류 저장 중..." else "분류 저장")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClassificationField(
    title: String,
    value: String,
    suggestions: List<String>,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { suggestion ->
                FilterChip(
                    selected = value == suggestion,
                    onClick = { onValueChange(suggestion) },
                    label = { Text(suggestion) }
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("$title 직접 입력") },
            singleLine = true
        )
    }
}

@Composable
private fun ClassificationDebugCard(debugInfo: ClassificationDebugInfo?) {
    if (debugInfo == null) {
        return
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "추론 디버그 정보",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = buildString {
                    append("신뢰도 ")
                    append("%.2f".format(debugInfo.confidence))
                    if (debugInfo.reducedMode) {
                        append(" · 축소 모드")
                    }
                    if (debugInfo.fallbackUsed) {
                        append(" · fallback 사용")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (debugInfo.usedEngines.isNotEmpty()) {
                Text(
                    text = "사용 엔진: ${debugInfo.usedEngines.joinToString()}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (debugInfo.finalScores.isNotEmpty()) {
                Text(
                    text = "최종 점수: ${debugInfo.finalScores.take(5).joinToString { "${it.label}:${"%.2f".format(it.score)}" }}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (debugInfo.seriesCandidates.isNotEmpty()) {
                Text(
                    text = "작품 후보: ${debugInfo.seriesCandidates.take(3).joinToString { "${it.label}:${"%.2f".format(it.score)}" }}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            debugInfo.modelOutputs.forEach { model ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = buildString {
                                append(model.displayName)
                                append(" · ")
                                append(if (model.loaded) "로드됨" else "미로드")
                                append(" / ")
                                append(if (model.invoked) "실행됨" else "미실행")
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (model.summary.isNotBlank()) {
                            Text(
                                text = model.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (model.tags.isNotEmpty()) {
                            Text(
                                text = model.tags.take(6).joinToString { "${it.label}:${"%.2f".format(it.score)}" },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (model.notes.isNotEmpty()) {
                            Text(
                                text = model.notes.take(3).joinToString(separator = "\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (debugInfo.frameSummaries.isNotEmpty()) {
                Text(
                    text = "대표 프레임 요약",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                debugInfo.frameSummaries.take(3).forEach { frame ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = buildString {
                                    append(frame.frameLabel)
                                    frame.timestampMs?.let {
                                        append(" · ")
                                        append(it)
                                        append("ms")
                                    }
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium
                            )
                            if (frame.summary.isNotBlank()) {
                                Text(
                                    text = frame.summary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (frame.tags.isNotEmpty()) {
                                Text(
                                    text = frame.tags.take(6).joinToString { "${it.label}:${"%.2f".format(it.score)}" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (frame.notes.isNotEmpty()) {
                                Text(
                                    text = frame.notes.take(3).joinToString(separator = "\n"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            if (debugInfo.reasoning.isNotEmpty()) {
                Text(
                    text = debugInfo.reasoning.take(8).joinToString(separator = "\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    uiState: MainUiState,
    supportsDedicatedPromotedSettings: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenPromotedNotificationSettings: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    val context = LocalContext.current
    val notificationManager = context.getSystemService(NotificationManager::class.java)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OverviewCard(
            title = "로컬 저장 상태",
            lines = buildList {
                add("저장 파일: ${uiState.storagePath}")
                add("manifest 레코드 수: ${uiState.manifestEntryCount}")
                uiState.manifestUpdatedAtEpochMs?.let {
                    add("마지막 저장 시각: ${formatTimestamp(it)}")
                }
                uiState.lastScanAtEpochMs?.let {
                    add("마지막 라이브러리 스캔: ${formatTimestamp(it)}")
                }
            }
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "백업 및 복원",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Storage Access Framework를 사용해 manifest.json 형식의 분류 데이터를 직접 내보내고 가져온다. 현재 가져오기는 선택한 파일로 로컬 데이터를 교체한다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("분류 데이터 내보내기")
                }
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("분류 데이터 가져오기")
                }
            }
        }

        OverviewCard(
            title = "알림 설정",
            lines = buildList {
                add(
                    if (requiresNotificationPermission(context)) {
                        "알림 권한: 필요"
                    } else {
                        "알림 권한: 허용됨"
                    }
                )
                add(
                    if (notificationManager.areNotificationsEnabled()) {
                        "앱 알림: 켜짐"
                    } else {
                        "앱 알림: 꺼짐"
                    }
                )
                if (Build.VERSION.SDK_INT >= 36) {
                    add(promotedNotificationStatus(context))
                }
                add("진행형 알림과 완료 알림 설정은 여기서 조정할 수 있다.")
            },
            action = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (requiresNotificationPermission(context)) {
                        FilledTonalButton(
                            onClick = onRequestNotificationPermission,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("알림 권한 요청")
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenNotificationSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("앱 알림 설정")
                    }
                    if (supportsDedicatedPromotedSettings) {
                        OutlinedButton(
                            onClick = onOpenPromotedNotificationSettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("고급 진행 알림 설정")
                        }
                    }
                }
            }
        )

        EngineStatusCard(uiState = uiState)

        OverviewCard(
            title = "클라우드 연동 자리",
            lines = listOf(
                "현재 상태: ${uiState.backupDisplayName}",
                "실제 Google Drive 연동은 이번 작업 범위에 포함하지 않았다.",
                "설정 화면에서 추후 연결 지점을 확인할 수 있도록 자리만 남겨 두었다."
            ),
            action = {
                OutlinedButton(
                    onClick = {},
                    enabled = false
                ) {
                    Text("Google Drive 준비 중")
                }
            }
        )
    }
}

@Composable
private fun OverviewCard(
    title: String,
    lines: List<String>,
    action: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (action != null) {
                Spacer(modifier = Modifier.height(4.dp))
                action()
            }
        }
    }
}

@Composable
private fun EngineStatusCard(uiState: MainUiState) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "분류 엔진 상태",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "현재 엔진: ${uiState.engineDisplayName}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (uiState.engineStatus.reducedMode) {
                    "Lite4 주 분류기 또는 임베더를 쓰지 못해 일부 항목은 ML Kit + 규칙 기반 축소 모드로 처리될 수 있다."
                } else {
                    "EfficientNet-Lite4와 MobileCLIP2-S0가 의미 분류를 맡고, ImageEmbedder와 ML Kit는 스타일·얼굴·OCR·문서 보조 신호를 제공한다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            uiState.engineStatus.models.forEach { model ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = buildString {
                                append("역할: ")
                                append(model.role)
                                append(" · 상태: ")
                                append(
                                    when {
                                        model.loaded -> "로드됨"
                                        model.available -> "대기 중"
                                        else -> "사용 불가"
                                    }
                                )
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!model.assetPath.isNullOrBlank()) {
                            Text(
                                text = "모델 파일: ${model.assetPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (model.version != null) {
                            Text(
                                text = "버전: ${model.version}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (model.summary.isNotBlank()) {
                            Text(
                                text = model.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MediaThumbnail(
    mediaItem: MediaItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val thumbnailRepository = remember(context) { context.appContainer.thumbnailRepository }
    val thumbnail by produceState<Bitmap?>(
        initialValue = thumbnailRepository.cached(mediaItem, ThumbnailSizePx),
        mediaItem.contentUri
    ) {
        if (value == null) {
            value = thumbnailRepository.load(mediaItem, ThumbnailSizePx)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!.asImageBitmap(),
                contentDescription = mediaItem.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = if (mediaItem.mediaType == MediaType.IMAGE) "IMG" else "VID",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        if (mediaItem.mediaType == MediaType.VIDEO) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f)
            ) {
                Text(
                    text = "VIDEO",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

private const val ThumbnailSizePx = 256
private const val PromotedPreviewColor = -0xe09115

private data class NotificationAttentionState(
    val title: String,
    val lines: List<String>,
    val showPermissionRequest: Boolean,
    val showPromotedSettings: Boolean
)

private fun requiresNotificationPermission(context: android.content.Context): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun notificationAttentionState(
    context: android.content.Context
): NotificationAttentionState? {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val channel = notificationManager.getNotificationChannel(AutoClassificationWorker.NOTIFICATION_CHANNEL_ID)

    if (requiresNotificationPermission(context)) {
        return NotificationAttentionState(
            title = "알림 권한이 꺼져 있다",
            lines = listOf(
                "삭제 후 재설치하면 Android 13 이상에서 POST_NOTIFICATIONS 권한이 다시 꺼질 수 있다.",
                "이 권한이 없으면 진행 알림도 숨겨지고, Now Bar 후보도 될 수 없다."
            ),
            showPermissionRequest = true,
            showPromotedSettings = false
        )
    }

    if (!notificationManager.areNotificationsEnabled() ||
        notificationManager.importance == NotificationManager.IMPORTANCE_NONE
    ) {
        return NotificationAttentionState(
            title = "앱 알림이 시스템에서 차단되어 있다",
            lines = listOf(
                "현재 앱 전체 알림이 꺼져 있어 진행 알림과 잠금화면 표시가 나오지 않는다.",
                "앱 알림 설정에서 알림 허용을 켠 뒤 자동분류를 새로 시작해야 한다."
            ),
            showPermissionRequest = false,
            showPromotedSettings = false
        )
    }

    if (channel != null && channel.importance <= NotificationManager.IMPORTANCE_MIN) {
        return NotificationAttentionState(
            title = "진행 알림 채널 중요도가 너무 낮다",
            lines = listOf(
                "현재 진행 알림 채널이 너무 낮아 눈에 띄는 진행형 알림으로 취급되기 어렵다.",
                "앱 알림 설정에서 'AI 자동분류 진행' 채널 중요도를 기본 이상으로 올려야 한다."
            ),
            showPermissionRequest = false,
            showPromotedSettings = false
        )
    }

    return null
}

private fun promotedNotificationStatus(context: android.content.Context): String {
    if (Build.VERSION.SDK_INT < 36) {
        return "고급 진행 알림: 이 기기 Android 버전에서는 일반 진행 알림만 사용한다."
    }

    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val channel = notificationManager.getNotificationChannel(AutoClassificationWorker.NOTIFICATION_CHANNEL_ID)
    val previewBuilder = android.app.Notification.Builder(
        context,
        AutoClassificationWorker.NOTIFICATION_CHANNEL_ID
    )
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("개인 미디어 분류기")
        .setContentText("AI 자동분류 진행 중")
        .setColor(PromotedPreviewColor)
        .setShortCriticalText("50%")
        .setStyle(
            android.app.Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress(50)
        )
        .setOngoing(true)
        .setCategory(android.app.Notification.CATEGORY_PROGRESS)
        .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
    val requestResult = PromotedNotificationSupport.requestPromotedOngoing(previewBuilder)
    val promotablePreview = previewBuilder.build()

    if (requiresNotificationPermission(context)) {
        return "고급 진행 알림: POST_NOTIFICATIONS 권한이 아직 없다. 삭제 후 재설치했다면 먼저 알림 권한을 다시 허용해야 한다."
    }

    if (!notificationManager.areNotificationsEnabled()) {
        return "고급 진행 알림: 앱 알림이 꺼져 있다. 먼저 앱 알림 설정을 켜라."
    }

    if (notificationManager.importance == NotificationManager.IMPORTANCE_NONE) {
        return "고급 진행 알림: 앱 전체 알림 중요도가 차단 상태다. 앱 알림 설정에서 허용으로 바꿔야 한다."
    }

    if (!notificationManager.canPostPromotedNotifications()) {
        return "고급 진행 알림 진단: 승격은 꺼져 있지만 일반 진행 알림과 완료 알림은 정상 사용 가능하다."
    }

    if (!supportsDedicatedPromotedSettings(context)) {
        return "고급 진행 알림: 이 One UI 빌드는 전용 설정 화면을 따로 노출하지 않는다. 일반 알림 설정만 보일 수 있다."
    }

    if (channel != null && channel.importance <= NotificationManager.IMPORTANCE_MIN) {
        return "고급 진행 알림: 현재 채널 중요도가 너무 낮다. 알림 채널을 기본 이상으로 올려야 한다."
    }

    return if (promotablePreview.hasPromotableCharacteristics()) {
        "고급 진행 알림: 앱은 promoted ongoing 요청과 필수 권한을 갖췄다. 요청 방식은 ${requestResult.method} 이다. 최종 Now Bar 노출은 삼성 정책과 기기 상태에 따라 달라질 수 있다."
    } else {
        "고급 진행 알림: 앱은 요청을 보내지만 미리보기 알림이 시스템 승격 조건 일부를 충족하지 못한다. 요청 방식은 ${requestResult.method} 이다."
    }
}

private fun openBestEffortSettings(
    context: android.content.Context,
    candidates: List<Intent>
): Boolean {
    val packageManager = context.packageManager
    candidates.forEach { intent ->
        if (intent.resolveActivity(packageManager) != null) {
            val opened = runCatching {
                context.startActivity(intent)
            }.isSuccess
            if (opened) {
                return true
            }
        }
    }
    return false
}

private fun supportsDedicatedPromotedSettings(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < 36) {
        return false
    }

    val packageManager = context.packageManager
    val intents = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            data = Uri.fromParts("package", context.packageName, null)
        },
        Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            data = Uri.fromParts("package", context.packageName, null)
        }
    )
    return intents.any { it.resolveActivity(packageManager) != null }
}

private fun formatTimestamp(epochMs: Long): String {
    return DateUtils.getRelativeTimeSpanString(
        epochMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
}

@Composable
private fun formatSize(context: android.content.Context, sizeBytes: Long): String {
    return Formatter.formatShortFileSize(context, sizeBytes)
}
