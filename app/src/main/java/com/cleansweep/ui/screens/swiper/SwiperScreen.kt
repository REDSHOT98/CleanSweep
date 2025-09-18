package com.cleansweep.ui.screens.swiper

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.FolderBarLayout
import com.cleansweep.data.repository.FolderNameLayout
import com.cleansweep.data.repository.SwipeSensitivity
import com.cleansweep.ui.components.AppDropdownMenu
import com.cleansweep.ui.components.AppMenuDivider
import com.cleansweep.ui.components.FolderSearchDialog
import com.cleansweep.ui.components.RenameFolderDialog
import com.cleansweep.ui.theme.AppTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ButtonDefaults
import com.cleansweep.ui.components.FastScrollbar
import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SwiperScreen(
    windowSizeClass: WindowSizeClass,
    bucketIds: List<String>,
    onNavigateUp: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDuplicates: () -> Unit = {},
    viewModel: SwiperViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val invertSwipe by viewModel.invertSwipe.collectAsState()
    val swipeSensitivity by viewModel.swipeSensitivity.collectAsState()
    val folderBarLayout by viewModel.folderBarLayout.collectAsState()
    val folderNameLayout by viewModel.folderNameLayout.collectAsState()
    val expandSummarySheet by viewModel.expandSummarySheet.collectAsState()
    val screenshotDeletesVideo by viewModel.screenshotDeletesVideo.collectAsState()
    val addFolderFocusTarget by viewModel.addFolderFocusTarget.collectAsState()
    val addFavoriteToTargetByDefault by viewModel.addFavoriteToTargetByDefault.collectAsState()
    val hintOnExistingFolderName by viewModel.hintOnExistingFolderName.collectAsState()
    val appContext = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isExpandedScreen = windowSizeClass.widthSizeClass > WindowWidthSizeClass.Compact
    val folderSearchState by viewModel.folderSearchManager.state.collectAsState()
    val focusRequester = remember { FocusRequester() }

    BackHandler {
        viewModel.onNavigateUp()
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collectLatest { event ->
            when (event) {
                is NavigationEvent.NavigateUp -> onNavigateUp()
            }
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(appContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
        }
    }
    LaunchedEffect(uiState.videoPlaybackSpeed) {
        exoPlayer.setPlaybackSpeed(uiState.videoPlaybackSpeed)
    }
    LaunchedEffect(uiState.isVideoMuted) {
        exoPlayer.volume = if (uiState.isVideoMuted) 0f else 1f
    }
    uiState.showRenameDialogForPath?.let { path ->
        val folderName = uiState.targetFolders.find { it.first == path }?.second
        if (folderName != null) {
            RenameFolderDialog(
                currentFolderName = folderName,
                onConfirm = { newName ->
                    viewModel.renameTargetFolder(path, newName)
                },
                onDismiss = { viewModel.dismissRenameDialog() }
            )
        }
    }
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (exoPlayer.isPlaying) {
                        viewModel.saveVideoPlaybackPosition(exoPlayer.currentPosition)
                        exoPlayer.pause()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (uiState.currentItem?.isVideo == true) {
                        exoPlayer.play()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }
    LaunchedEffect(uiState.currentItem) {
        val currentItem = uiState.currentItem
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        if (currentItem != null && currentItem.isVideo) {
            val exoMediaItem = ExoMediaItem.fromUri(currentItem.uri)
            exoPlayer.setMediaItem(exoMediaItem)
            exoPlayer.prepare()

            if (uiState.videoPlaybackPosition > 0L) {
                exoPlayer.seekTo(uiState.videoPlaybackPosition)
            }
            exoPlayer.playWhenReady = true
        }
    }
    LaunchedEffect(bucketIds) {
        viewModel.initializeMedia(bucketIds)
    }
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            viewModel.toastMessageShown()
        }
    }
    if (uiState.showConfirmExitDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelExit() },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved changes. Are you sure you want to exit?") },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmExit() }
                ) {
                    Text("Exit Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelExitAndShowSummary() }) {
                    Text("Review Changes")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("") }, // Not sure
                navigationIcon = {
                    IconButton(onClick = { viewModel.onNavigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToDuplicates) {
                        Icon(Icons.Default.ControlPointDuplicate, contentDescription = "Find Duplicates")
                    }
                    IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                            val hasAudio = exoPlayer.currentTracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                            viewModel.toggleMute(hasAudio)
                            return@onKeyEvent true
                        }
                    }
                    false
                }
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                uiState.error != null -> ErrorMessage(message = uiState.error!!, onRetry = { viewModel.initializeMedia(bucketIds) })
                uiState.currentItem != null -> {
                    if (isExpandedScreen) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            MainContent(
                                modifier = Modifier.weight(1f),
                                currentItem = uiState.currentItem!!,
                                exoPlayer = exoPlayer,
                                imageLoader = viewModel.imageLoader,
                                gifImageLoader = viewModel.gifImageLoader,
                                onSwipeLeft = viewModel::handleSwipeLeft,
                                onSwipeRight = viewModel::handleSwipeRight,
                                onLongPress = viewModel::showMediaItemMenu,
                                hideFilename = uiState.hideFilename,
                                invertSwipe = invertSwipe,
                                sensitivity = swipeSensitivity,
                                videoPlaybackSpeed = uiState.videoPlaybackSpeed,
                                onSetVideoPlaybackSpeed = viewModel::setPlaybackSpeed,
                                isVideoMuted = uiState.isVideoMuted,
                                onToggleMute = {
                                    val hasAudio = exoPlayer.currentTracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                                    viewModel.toggleMute(hasAudio)
                                },
                                onTap = {
                                    if (it.isVideo) {
                                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    }
                                },
                                isPendingConversion = uiState.isCurrentItemPendingConversion,
                                screenshotDeletesVideo = screenshotDeletesVideo,
                                folderNameLayout = folderNameLayout
                            )
                            Box(modifier = Modifier
                                .fillMaxHeight()
                                .width(340.dp)) {
                                BottomFolderBar(
                                    targetFolders = uiState.targetFolders,
                                    compactFoldersView = uiState.compactFoldersView,
                                    isFolderBarExpanded = uiState.isFolderBarExpanded,
                                    onSetExpanded = viewModel::setFolderBarExpanded,
                                    currentTheme = uiState.currentTheme,
                                    useLegacyFolderIcons = uiState.useLegacyFolderIcons,
                                    pendingChangesCount = uiState.pendingChanges.size,
                                    currentItem = uiState.currentItem,
                                    targetFavorites = uiState.targetFavorites,
                                    onSelectFolder = viewModel::moveToFolder,
                                    onLongPressFolder = viewModel::showFolderMenu,
                                    onCreateNewAlbum = viewModel::showAddTargetFolderDialog,
                                    onToggleExpansion = viewModel::toggleFolderBarExpansion,
                                    onShowSummary = viewModel::showSummarySheet,
                                    onUndo = viewModel::revertLastChange,
                                    layout = FolderBarLayout.VERTICAL,
                                    folderName = if (folderNameLayout == FolderNameLayout.BELOW) uiState.currentItem!!.bucketName else null
                                )
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            MainContent(
                                modifier = Modifier.weight(1f),
                                currentItem = uiState.currentItem!!,
                                exoPlayer = exoPlayer,
                                imageLoader = viewModel.imageLoader,
                                gifImageLoader = viewModel.gifImageLoader,
                                onSwipeLeft = viewModel::handleSwipeLeft,
                                onSwipeRight = viewModel::handleSwipeRight,
                                onLongPress = viewModel::showMediaItemMenu,
                                hideFilename = uiState.hideFilename,
                                invertSwipe = invertSwipe,
                                sensitivity = swipeSensitivity,
                                videoPlaybackSpeed = uiState.videoPlaybackSpeed,
                                onSetVideoPlaybackSpeed = viewModel::setPlaybackSpeed,
                                isVideoMuted = uiState.isVideoMuted,
                                onToggleMute = {
                                    val hasAudio = exoPlayer.currentTracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                                    viewModel.toggleMute(hasAudio)
                                },
                                onTap = {
                                    if (it.isVideo) {
                                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    }
                                },
                                isPendingConversion = uiState.isCurrentItemPendingConversion,
                                screenshotDeletesVideo = screenshotDeletesVideo,
                                folderNameLayout = folderNameLayout
                            )
                            BottomFolderBar(
                                targetFolders = uiState.targetFolders,
                                compactFoldersView = uiState.compactFoldersView,
                                isFolderBarExpanded = uiState.isFolderBarExpanded,
                                onSetExpanded = viewModel::setFolderBarExpanded,
                                currentTheme = uiState.currentTheme,
                                useLegacyFolderIcons = uiState.useLegacyFolderIcons,
                                pendingChangesCount = uiState.pendingChanges.size,
                                currentItem = uiState.currentItem,
                                targetFavorites = uiState.targetFavorites,
                                onSelectFolder = viewModel::moveToFolder,
                                onLongPressFolder = viewModel::showFolderMenu,
                                onCreateNewAlbum = viewModel::showAddTargetFolderDialog,
                                onToggleExpansion = viewModel::toggleFolderBarExpansion,
                                onShowSummary = viewModel::showSummarySheet,
                                onUndo = viewModel::revertLastChange,
                                layout = folderBarLayout,
                                folderName = if (folderNameLayout == FolderNameLayout.BELOW) uiState.currentItem!!.bucketName else null
                            )
                        }
                    }
                }
                uiState.isSortingComplete && uiState.pendingChanges.isEmpty() -> {
                    val rememberProcessedMedia by viewModel.rememberProcessedMedia.collectAsState()
                    AlreadyOrganizedDialog(
                        onSelectNewFolders = onNavigateUp,
                        showResetHistoryButton = rememberProcessedMedia,
                        onResetHistory = viewModel::resetProcessedMedia,
                        onResetSingleFolderHistory = viewModel::showForgetMediaInFolderDialog,
                        onClose = { (appContext as? Activity)?.finish() }
                    )
                }
                else -> {
                    NoMoreItemsMessage(
                        pendingChanges = uiState.pendingChanges,
                        onShowSummarySheet = viewModel::showSummarySheet
                    )
                }
            }
            if (uiState.showAddTargetFolderDialog) {
                AddTargetFolderDialog(
                    folderSearchState = folderSearchState,
                    addFolderFocusTarget = addFolderFocusTarget,
                    addFavoriteToTargetByDefault = addFavoriteToTargetByDefault,
                    hintOnExistingFolderName = hintOnExistingFolderName,
                    allFolderPaths = uiState.allFolderPathsForDialog.map { it.first },
                    currentItemPath = uiState.currentItem?.id,
                    targetFavorites = uiState.targetFavorites,
                    onDismissRequest = viewModel::dismissAddTargetFolderDialog,
                    onSearchQueryChange = viewModel.folderSearchManager::updateSearchQuery,
                    onPathSelected = viewModel::onPathSelected,
                    onSearchFocusChanged = viewModel::onSearchFocusChanged,
                    onResetFolderSelection = viewModel::resetFolderSelectionToDefault,
                    onConfirm = viewModel::confirmFolderSelection
                )
            }
            if (uiState.showForgetMediaSearchDialog) {
                var showConfirmDialog by remember { mutableStateOf(false) }
                val folderToForget = folderSearchState.browsePath
                FolderSearchDialog(
                    state = folderSearchState,
                    title = "Forget Sorted Media",
                    searchLabel = "Search for a folder to reset...",
                    confirmButtonText = "Forget",
                    autoConfirmOnSelection = false,
                    onDismiss = viewModel::dismissForgetMediaSearchDialog,
                    onQueryChanged = viewModel.folderSearchManager::updateSearchQuery,
                    onFolderSelected = viewModel::onPathSelected,
                    onConfirm = {
                        if (folderToForget != null) {
                            showConfirmDialog = true
                        }
                    }
                )
                if (showConfirmDialog && folderToForget != null) {
                    AlertDialog(
                        onDismissRequest = { showConfirmDialog = false },
                        title = { Text("Confirm Action") },
                        text = { Text("Are you sure you want to forget all sorted media history for the folder '${File(folderToForget).name}'? This folder will reappear for sorting if it still contains media.") },
                        confirmButton = {
                            Button(onClick = {
                                viewModel.forgetSortedMediaInFolder(folderToForget)
                                showConfirmDialog = false
                                viewModel.dismissForgetMediaSearchDialog()
                            }) { Text("Confirm") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirmDialog = false }) { Text("Cancel") }
                        }
                    )
                }
            }
            FolderContextMenu(
                folderMenuState = uiState.folderMenuState,
                targetFavorites = uiState.targetFavorites,
                onDismiss = viewModel::dismissFolderMenu,
                onRename = viewModel::showRenameDialog,
                onToggleFavorite = viewModel::toggleTargetFavorite,
                onRemove = viewModel::removeTargetFolder
            )
            MediaItemContextMenu(
                isVisible = uiState.showMediaItemMenu,
                menuOffset = uiState.mediaItemMenuOffset,
                currentItem = uiState.currentItem,
                isPendingConversion = uiState.isCurrentItemPendingConversion,
                exoPlayer = exoPlayer,
                onDismiss = viewModel::dismissMediaItemMenu,
                onScreenshot = viewModel::addScreenshotChange
            )
        }
        if (uiState.showSummarySheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = expandSummarySheet)
            val summaryListState = rememberLazyListState()
            val isAmoled = uiState.currentTheme == AppTheme.AMOLED
            val containerColor = if (isAmoled) {
                MaterialTheme.colorScheme.surface
            } else {
                BottomSheetDefaults.ContainerColor
            }
            val tonalElevation = if (isAmoled) 0.dp else BottomSheetDefaults.Elevation

            if (uiState.pendingChanges.isEmpty()) {
                LaunchedEffect(Unit) {
                    viewModel.dismissSummarySheet()
                }
            }

            ModalBottomSheet(
                onDismissRequest = viewModel::dismissSummarySheet,
                sheetState = sheetState,
                containerColor = containerColor,
                tonalElevation = tonalElevation,
                modifier = Modifier.fillMaxWidth()
            ) {
                SummarySheet(
                    pendingChanges = uiState.pendingChanges,
                    toDelete = uiState.toDelete,
                    toKeep = uiState.toKeep,
                    toConvert = uiState.toConvert,
                    groupedMoves = uiState.groupedMoves,
                    isApplyingChanges = uiState.isApplyingChanges,
                    folderIdNameMap = uiState.folderIdToNameMap,
                    onDismiss = viewModel::dismissSummarySheet,
                    onConfirm = { if (!uiState.isApplyingChanges) viewModel.applyChanges() },
                    onResetChanges = viewModel::resetPendingChanges,
                    onRevertChange = viewModel::revertChange,
                    viewMode = uiState.summaryViewMode,
                    onToggleViewMode = viewModel::toggleSummaryViewMode,
                    applyChangesButtonLabel = uiState.applyChangesButtonLabel,
                    sheetScrollState = summaryListState
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun MainContent(
    modifier: Modifier = Modifier,
    currentItem: MediaItem,
    exoPlayer: ExoPlayer,
    imageLoader: ImageLoader,
    gifImageLoader: ImageLoader,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onLongPress: (offset: DpOffset) -> Unit,
    hideFilename: Boolean,
    invertSwipe: Boolean,
    sensitivity: SwipeSensitivity,
    videoPlaybackSpeed: Float,
    onSetVideoPlaybackSpeed: (Float) -> Unit,
    isVideoMuted: Boolean,
    onToggleMute: () -> Unit,
    onTap: (MediaItem) -> Unit,
    isPendingConversion: Boolean,
    screenshotDeletesVideo: Boolean,
    folderNameLayout: FolderNameLayout
) {
    Column(modifier) {
        if (folderNameLayout == FolderNameLayout.ABOVE) {
            FolderNameHeader(currentItem.bucketName)
        }
        MediaItemCard(
            item = currentItem,
            exoPlayer = exoPlayer,
            imageLoader = imageLoader,
            gifImageLoader = gifImageLoader,
            onSwipeLeft = onSwipeLeft,
            onSwipeRight = onSwipeRight,
            onLongPress = onLongPress,
            modifier = Modifier.weight(1f),
            hideFilename = hideFilename,
            invertSwipe = invertSwipe,
            sensitivity = sensitivity,
            videoPlaybackSpeed = videoPlaybackSpeed,
            onSetVideoPlaybackSpeed = onSetVideoPlaybackSpeed,
            isVideoMuted = isVideoMuted,
            onToggleMute = onToggleMute,
            onTap = onTap,
            isPendingConversion = isPendingConversion,
            screenshotDeletesVideo = screenshotDeletesVideo
        )
    }
}

@Composable
private fun FolderNameHeader(folderName: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    ) {
        Text(
            text = folderName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(12.dp),
            textAlign = TextAlign.Center
        )
    }
}


@Composable
private fun FolderContextMenu(
    folderMenuState: FolderMenuState,
    targetFavorites: Set<String>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    if (folderMenuState is FolderMenuState.Visible) {
        val isFavorite = folderMenuState.folderPath in targetFavorites
        AppDropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            offset = folderMenuState.pressOffset
        ) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    onRename(folderMenuState.folderPath)
                    onDismiss()
                },
                leadingIcon = { Icon(Icons.Default.Edit, null) }
            )
            DropdownMenuItem(
                text = { Text(if (isFavorite) "Unfavorite" else "Favorite") },
                onClick = {
                    onToggleFavorite(folderMenuState.folderPath)
                    onDismiss()
                },
                leadingIcon = { Icon(Icons.Default.Star, null) }
            )
            AppMenuDivider()
            DropdownMenuItem(
                text = { Text("Remove from bar", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    onRemove(folderMenuState.folderPath)
                    onDismiss()
                },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }
            )
        }
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    val formatter = DecimalFormat("#,##0.#")
    return formatter.format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
}

@Composable
private fun MediaItemContextMenu(
    isVisible: Boolean,
    menuOffset: DpOffset,
    currentItem: MediaItem?,
    isPendingConversion: Boolean,
    exoPlayer: ExoPlayer,
    onDismiss: () -> Unit,
    onScreenshot: (Long) -> Unit
) {
    val appContext = LocalContext.current
    if (isVisible && currentItem != null) {
        AppDropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            offset = menuOffset
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            currentItem.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Size: ${formatFileSize(currentItem.size)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = {
                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Filename", currentItem.displayName)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(appContext, "Filename copied!", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            )
            AppMenuDivider()
            if (currentItem.isVideo) {
                DropdownMenuItem(
                    text = { Text("Screenshot") },
                    leadingIcon = { Icon(Icons.Default.Image, null) },
                    onClick = {
                        val timestampMicros = exoPlayer.currentPosition * 1000
                        onScreenshot(timestampMicros)
                    },
                    enabled = !isPendingConversion
                )
            }
            DropdownMenuItem(
                text = { Text("Share") },
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = appContext.contentResolver.getType(currentItem.uri) ?: "*/*"
                        putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                    }
                    appContext.startActivity(Intent.createChooser(shareIntent, "Share Media"))
                    onDismiss()
                },
                leadingIcon = { Icon(Icons.Default.Share, null) }
            )
            DropdownMenuItem(
                text = { Text("Open with") },
                onClick = {
                    val viewUri = if (currentItem.uri.scheme == "file") {
                        try {
                            val file = File(currentItem.id)
                            FileProvider.getUriForFile(appContext, "${appContext.packageName}.provider", file)
                        } catch (e: Exception) {
                            currentItem.uri // fallback
                        }
                    } else {
                        currentItem.uri
                    }

                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(viewUri, appContext.contentResolver.getType(viewUri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        appContext.startActivity(openIntent)
                    } catch (e: Exception) {
                        Toast.makeText(appContext, "No app can open this file.", Toast.LENGTH_LONG).show()
                    }
                    onDismiss()
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
            )
        }
    }
}

@Composable
private fun ControlBar(
    isExpanded: Boolean,
    showExpandButton: Boolean,
    hasPendingChanges: Boolean,
    onToggleExpansion: () -> Unit,
    onCreateNewAlbum: () -> Unit,
    onShowSummary: () -> Unit,
    onUndo: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onCreateNewAlbum,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Icon(Icons.Default.CreateNewFolder, "Add Target Folder")
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(visible = hasPendingChanges) {
                IconButton(onClick = onShowSummary) {
                    Icon(Icons.Default.Preview, "Review Changes")
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            AnimatedVisibility(visible = hasPendingChanges) {
                IconButton(onClick = onUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo Last Action")
                }
            }
        }

        if (showExpandButton) {
            val rotationAngle by animateFloatAsState(
                targetValue = if (isExpanded) 180f else 0f,
                label = "expand_icon_rotation"
            )
            IconButton(
                onClick = onToggleExpansion,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(rotationAngle)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomFolderBar(
    targetFolders: List<Pair<String, String>>,
    compactFoldersView: Boolean,
    isFolderBarExpanded: Boolean,
    onSetExpanded: (Boolean) -> Unit,
    currentTheme: AppTheme,
    useLegacyFolderIcons: Boolean,
    pendingChangesCount: Int,
    currentItem: MediaItem?,
    targetFavorites: Set<String>,
    onSelectFolder: (String) -> Unit,
    onLongPressFolder: (String, DpOffset) -> Unit,
    onCreateNewAlbum: () -> Unit,
    onToggleExpansion: () -> Unit,
    onShowSummary: () -> Unit,
    onUndo: () -> Unit,
    layout: FolderBarLayout,
    folderName: String?
) {
    BoxWithConstraints {
        val containerWidth = this.maxWidth
        val containerHeight = this.maxHeight
        val folderCount = targetFolders.size
        val chipWidth = if (compactFoldersView) 60.dp else 85.dp
        val chipHeight = if (compactFoldersView) 70.dp else 100.dp
        val chipSpacingHorizontal = 8.dp
        val chipSpacingVertical = 4.dp // From FlowRow verticalArrangement

        val gridHorizontalPadding = 16.dp * 2
        val availableWidthForGrid = containerWidth - gridHorizontalPadding
        val maxChipsPerLine = (availableWidthForGrid / (chipWidth + chipSpacingHorizontal)).toInt().coerceAtLeast(1)

        val singleRowHeight = chipHeight + (chipSpacingVertical * 2)
        val contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)

        val numChipsInOneFlowRowLine = (availableWidthForGrid / (chipWidth + chipSpacingHorizontal)).toInt().coerceAtLeast(1)
        val estimatedTotalRowsForFlowRow = (folderCount + numChipsInOneFlowRowLine - 1) / numChipsInOneFlowRowLine
        val estimatedFlowRowContentHeight = (chipHeight * estimatedTotalRowsForFlowRow) + (chipSpacingVertical * (estimatedTotalRowsForFlowRow - 1)) + (contentPadding.calculateTopPadding().value + contentPadding.calculateBottomPadding().value).dp
        val showExpandButton = folderCount > maxChipsPerLine

        LaunchedEffect(folderCount, maxChipsPerLine, layout, isFolderBarExpanded, showExpandButton) {
            if (isFolderBarExpanded && !showExpandButton) {
                onSetExpanded(false)
            }
        }

        val isAmoled = currentTheme == AppTheme.AMOLED
        val barColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        val barElevation = if (isAmoled) 0.dp else 2.dp

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = barColor,
            tonalElevation = barElevation
        ) {
            Column(modifier = Modifier.animateContentSize()) {
                if (folderName != null) {
                    FolderNameHeader(folderName)
                }

                ControlBar(
                    isExpanded = isFolderBarExpanded,
                    showExpandButton = showExpandButton,
                    hasPendingChanges = pendingChangesCount > 0,
                    onToggleExpansion = onToggleExpansion,
                    onCreateNewAlbum = onCreateNewAlbum,
                    onShowSummary = onShowSummary,
                    onUndo = onUndo
                )

                if (targetFolders.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    val maxContainerHeight = containerHeight * 0.4f

                    when (layout) {
                        FolderBarLayout.HORIZONTAL -> {
                            if (!isFolderBarExpanded) {
                                LazyRow(
                                    modifier = Modifier
                                        .height(singleRowHeight)
                                        .fillMaxWidth(),
                                    contentPadding = contentPadding,
                                    horizontalArrangement = Arrangement.spacedBy(chipSpacingHorizontal, Alignment.CenterHorizontally)
                                ) {
                                    items(items = targetFolders, key = { it.first }) { (folderId, fName) ->
                                        FolderChipWrapper(currentItem, compactFoldersView, folderId in targetFavorites, useLegacyFolderIcons, folderId, fName, onSelectFolder, onLongPressFolder)
                                    }
                                }
                            } else {
                                val flowRowWouldOverflowMaxContainerHeight = estimatedFlowRowContentHeight > maxContainerHeight
                                if (!flowRowWouldOverflowMaxContainerHeight) {
                                    FlowRow(
                                        modifier = Modifier
                                            .heightIn(min = singleRowHeight, max = maxContainerHeight)
                                            .fillMaxWidth()
                                            .padding(contentPadding),
                                        horizontalArrangement = Arrangement.spacedBy(chipSpacingHorizontal, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(chipSpacingVertical)
                                    ) {
                                        targetFolders.forEach { (folderId, fName) ->
                                            FolderChipWrapper(currentItem, compactFoldersView, folderId in targetFavorites, useLegacyFolderIcons, folderId, fName, onSelectFolder, onLongPressFolder)
                                        }
                                    }
                                } else {
                                    val maxRowsInHorizontalGrid = (maxContainerHeight / (chipHeight + chipSpacingVertical)).toInt().coerceAtLeast(1)
                                    LazyHorizontalGrid(
                                        rows = GridCells.Fixed(maxRowsInHorizontalGrid),
                                        modifier = Modifier
                                            .heightIn(min = singleRowHeight, max = maxContainerHeight)
                                            .fillMaxWidth(),
                                        contentPadding = contentPadding,
                                        horizontalArrangement = Arrangement.spacedBy(chipSpacingHorizontal, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(chipSpacingVertical)
                                    ) {
                                        items(items = targetFolders, key = { it.first }) { (folderId, fName) ->
                                            FolderChipWrapper(currentItem, compactFoldersView, folderId in targetFavorites, useLegacyFolderIcons, folderId, fName, onSelectFolder, onLongPressFolder)
                                        }
                                    }
                                }
                            }
                        }
                        FolderBarLayout.VERTICAL -> {
                            if (!isFolderBarExpanded) {
                                Box(
                                    modifier = Modifier
                                        .height(singleRowHeight)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    FlowRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(contentPadding),
                                        horizontalArrangement = Arrangement.spacedBy(chipSpacingHorizontal, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(chipSpacingVertical)
                                    ) {
                                        targetFolders.forEach { (folderId, fName) ->
                                            FolderChipWrapper(currentItem, compactFoldersView, folderId in targetFavorites, useLegacyFolderIcons, folderId, fName, onSelectFolder, onLongPressFolder)
                                        }
                                    }
                                }
                            } else {
                                val minColumnsInExpandedGrid = maxChipsPerLine.coerceAtLeast(1)
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(minColumnsInExpandedGrid),
                                    modifier = Modifier
                                        .heightIn(min = singleRowHeight, max = maxContainerHeight)
                                        .fillMaxWidth(),
                                    contentPadding = contentPadding,
                                    horizontalArrangement = Arrangement.spacedBy(chipSpacingHorizontal, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(chipSpacingVertical)
                                ) {
                                    items(items = targetFolders, key = { it.first }) { (folderId, fName) ->
                                        FolderChipWrapper(currentItem, compactFoldersView, folderId in targetFavorites, useLegacyFolderIcons, folderId, fName, onSelectFolder, onLongPressFolder)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun FolderChipWrapper(
    currentItem: MediaItem?,
    compactFoldersView: Boolean,
    isFavorite: Boolean,
    useLegacyFolderIcons: Boolean,
    folderId: String,
    folderName: String,
    onSelectFolder: (String) -> Unit,
    onLongPressFolder: (String, DpOffset) -> Unit
) {
    val isEnabled = remember(currentItem?.id, folderId) {
        val currentItemPath = currentItem?.id ?: return@remember true
        val parentDirectory = try {
            File(currentItemPath).parent
        } catch (e: Exception) {
            null
        }
        parentDirectory != folderId
    }

    val chipHeight = if (compactFoldersView) 70.dp else 100.dp
    val chipWidth = if (compactFoldersView) 60.dp else 85.dp
    val iconSize = if (compactFoldersView) 28.dp else 40.dp
    val density = LocalDensity.current
    var globalPosition by remember { mutableStateOf(Offset.Zero) }
    FolderChip(
        modifier = Modifier.onGloballyPositioned {
            globalPosition = it.localToWindow(Offset.Zero)
        },
        name = folderName,
        isFavorite = isFavorite,
        onClick = { onSelectFolder(folderId) },
        onLongClick = { pressOffset ->
            val absolutePressOffset = globalPosition + pressOffset
            val dpOffset = with(density) {
                DpOffset(absolutePressOffset.x.toDp(), absolutePressOffset.y.toDp())
            }
            onLongPressFolder(folderId, dpOffset)
        },
        chipWidth = chipWidth,
        chipHeight = chipHeight,
        iconSize = iconSize,
        useLegacyIcon = useLegacyFolderIcons,
        isCompact = compactFoldersView,
        isEnabled = isEnabled
    )
}

@Composable
private fun FolderChip(
    modifier: Modifier = Modifier,
    name: String,
    onClick: () -> Unit,
    onLongClick: (Offset) -> Unit,
    isFavorite: Boolean,
    chipWidth: Dp,
    chipHeight: Dp,
    iconSize: Dp,
    useLegacyIcon: Boolean,
    isCompact: Boolean,
    isEnabled: Boolean = true
) {
    val alpha = if (isEnabled) 1f else 0.5f
    val color = MaterialTheme.colorScheme.secondaryContainer
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    val textStyle = if (isCompact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    Column(
        modifier = modifier
            .width(chipWidth)
            .height(chipHeight)
            .graphicsLayer(alpha = alpha)
            .pointerInput(onClick, onLongClick, isEnabled) {
                detectTapGestures(
                    onLongPress = { offset -> onLongClick(offset) },
                    onTap = { if (isEnabled) onClick() }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(iconSize)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                if (useLegacyIcon) {
                    Text(
                        text = name
                            .take(1)
                            .uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = name,
                        tint = contentColor,
                        modifier = Modifier.size(iconSize * 0.6f)
                    )
                }
            }
            if (isFavorite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (4).dp, y = (4).dp)
                        .size(iconSize / 2.2f),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(color = Color.Black.copy(alpha = 0.4f))
                    }
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Favorite",
                        tint = Color.White,
                        modifier = Modifier.size(iconSize / 3.5f)
                    )
                }
            }
        }
        Text(
            text = name,
            style = textStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MediaItemCard(
    item: MediaItem,
    exoPlayer: ExoPlayer,
    imageLoader: ImageLoader,
    gifImageLoader: ImageLoader,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onLongPress: (offset: DpOffset) -> Unit,
    onTap: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    hideFilename: Boolean = false,
    invertSwipe: Boolean = false,
    sensitivity: SwipeSensitivity,
    videoPlaybackSpeed: Float,
    onSetVideoPlaybackSpeed: (Float) -> Unit,
    isVideoMuted: Boolean,
    onToggleMute: () -> Unit,
    isPendingConversion: Boolean,
    screenshotDeletesVideo: Boolean
) {
    var swipeOffsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    val horizontalThreshold = when (sensitivity) {
        SwipeSensitivity.LOW -> with(density) { 60.dp.toPx() }
        SwipeSensitivity.MEDIUM -> with(density) { 80.dp.toPx() }
        SwipeSensitivity.HIGH -> with(density) { 140.dp.toPx() }
    }

    val rotation = (swipeOffsetX / 30).coerceIn(-6f, 6f)
    val animatedOffsetX by animateFloatAsState(targetValue = swipeOffsetX, label = "offsetX")
    val animatedScale by animateFloatAsState(targetValue = scale, label = "scale")
    val animatedPanOffset by animateOffsetAsState(targetValue = panOffset, label = "panOffset")

    val leftBorderAlpha = if (swipeOffsetX < 0) (abs(swipeOffsetX) / horizontalThreshold).coerceIn(0f, 1f) else 0f
    val rightBorderAlpha = if (swipeOffsetX > 0) (swipeOffsetX / horizontalThreshold).coerceIn(0f, 1f) else 0f
    val leftColor = if (invertSwipe) Color.Green else Color.Red
    val rightColor = if (invertSwipe) Color.Red else Color.Green
    var globalPosition by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(item.id) {
        scale = 1f
        panOffset = Offset.Zero
        swipeOffsetX = 0f
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        val maxCardWidth = this.maxWidth * 0.98f
        val maxCardHeight = this.maxHeight * 0.9f
        val cardAspectRatio = if (item.height > 0 && item.width > 0) {
            item.width.toFloat() / item.height.toFloat()
        } else {
            1.0f
        }
        val widthByHeight = maxCardHeight * cardAspectRatio
        val heightByWidth = maxCardWidth / cardAspectRatio
        val (cardWidth, cardHeight) = if (widthByHeight <= maxCardWidth) widthByHeight to maxCardHeight else maxCardWidth to heightByWidth

        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .align(Alignment.Center)
                .onGloballyPositioned {
                    globalPosition = it.localToWindow(Offset.Zero)
                }
                .pointerInput(item.id) {
                    forEachGesture {
                        coroutineScope {
                            awaitPointerEventScope {
                                var wasDragging = false
                                var wasTransforming = false
                                var longPressFired = false

                                val down = awaitFirstDown(requireUnconsumed = true)
                                val longPressJob = launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    longPressFired = true
                                    if (scale > 1f) {
                                        scale = 1f
                                        panOffset = Offset.Zero
                                    } else {
                                        val dpOffset = with(density) {
                                            DpOffset(globalPosition.x.toDp() + down.position.x.toDp(), globalPosition.y.toDp() + down.position.y.toDp())
                                        }
                                        onLongPress(dpOffset)
                                    }
                                }

                                do {
                                    val event = awaitPointerEvent()
                                    val anyPressed = event.changes.any { it.pressed }

                                    if (event.changes.size > 1) {
                                        longPressJob.cancel()
                                        wasTransforming = true
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val newScale = scale * zoom

                                        if (newScale < 1f) {
                                            scale = 1f
                                            panOffset = Offset.Zero
                                        } else {
                                            scale = newScale.coerceIn(1f, 5f)
                                            if (scale > 1f) {
                                                val xMax = (cardWidth.toPx() * (scale - 1)) / 2
                                                val yMax = (cardHeight.toPx() * (scale - 1)) / 2
                                                panOffset = Offset(
                                                    x = (panOffset.x + pan.x * scale).coerceIn(-xMax, xMax),
                                                    y = (panOffset.y + pan.y * scale).coerceIn(-yMax, yMax)
                                                )
                                            }
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (!wasTransforming) {
                                        val change = event.changes.first()
                                        val dragAmount = change.positionChange()

                                        if (dragAmount.getDistance() > viewConfiguration.touchSlop) {
                                            longPressJob.cancel()
                                            if (abs(dragAmount.x) > abs(dragAmount.y) && scale <= 1f) {
                                                wasDragging = true
                                                swipeOffsetX += dragAmount.x
                                                change.consume()
                                            } else {
                                                longPressJob.cancel()
                                            }
                                        }
                                    }
                                } while (anyPressed)

                                longPressJob.cancel()

                                if (wasDragging) {
                                    when {
                                        swipeOffsetX < -horizontalThreshold -> onSwipeLeft()
                                        swipeOffsetX > horizontalThreshold -> onSwipeRight()
                                        else -> swipeOffsetX = 0f
                                    }
                                } else if (!wasTransforming && !longPressFired) {
                                    if (scale > 1f) {
                                        scale = 1f
                                        panOffset = Offset.Zero
                                    } else {
                                        onTap(item)
                                    }
                                }
                            }
                        }
                    }
                }
                .graphicsLayer {
                    translationX = if (animatedScale > 1f) animatedPanOffset.x else animatedOffsetX
                    translationY = if (animatedScale > 1f) animatedPanOffset.y else 0f
                    scaleX = animatedScale
                    scaleY = animatedScale
                    rotationZ = if (animatedScale > 1f) 0f else rotation
                    clip = false
                },
        ) {
            Card(modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.2f))) {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .clip(MaterialTheme.shapes.medium)) {
                    if (item.isVideo) {
                        VideoPlayer(
                            exoPlayer = exoPlayer,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val loader = if (item.mimeType == "image/gif") gifImageLoader else imageLoader
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(item.uri).crossfade(true).build(),
                            imageLoader = loader,
                            contentDescription = item.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.Center
                        )
                    }
                    if (leftBorderAlpha > 0f) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val intensity = (leftBorderAlpha * 0.7f).coerceAtMost(0.7f)
                            drawRect(brush = Brush.radialGradient(0.0f to leftColor.copy(alpha = intensity), 0.15f to leftColor.copy(alpha = intensity * 0.2f), 1.0f to Color.Transparent, center = androidx.compose.ui.geometry.Offset(0f, size.height), radius = size.maxDimension * (1.0f + leftBorderAlpha)))
                        }
                    }
                    if (rightBorderAlpha > 0f) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val intensity = (rightBorderAlpha * 0.6f).coerceAtMost(0.6f)
                            drawRect(brush = Brush.radialGradient(0.0f to rightColor.copy(alpha = intensity), 0.15f to rightColor.copy(alpha = intensity * 0.2f), 1.0f to Color.Transparent, center = androidx.compose.ui.geometry.Offset(size.width, size.height), radius = size.maxDimension * (1.0f + rightBorderAlpha)))
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (item.isVideo) {
                            IconButton(
                                onClick = onToggleMute,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isVideoMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (isVideoMuted) "Unmute video" else "Mute video",
                                    tint = Color.White
                                )
                            }
                            if (isPendingConversion && !screenshotDeletesVideo) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .padding(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Photo,
                                        contentDescription = "Pending conversion to image",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (item.isVideo) {
                                TextButton(
                                    onClick = {
                                        val nextSpeed = when (videoPlaybackSpeed) {
                                            1.0f -> 1.5f
                                            1.5f -> 2.0f
                                            else -> 1.0f
                                        }
                                        onSetVideoPlaybackSpeed(nextSpeed)
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                                    contentPadding = PaddingValues(4.dp),
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        "${videoPlaybackSpeed}x",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (!hideFilename) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                ) {
                                    Text(
                                        text = item.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .fillMaxWidth(),
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun VideoPlayer(
    exoPlayer: ExoPlayer,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val textureView = remember { TextureView(context) }
    AndroidView(
        factory = {
            textureView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            textureView
        },
        update = { view ->
            exoPlayer.setVideoTextureView(view)
        },
        onRelease = { view ->
            exoPlayer.clearVideoTextureView(view)
        },
        modifier = modifier
    )
}

@Composable
private fun NoMoreItemsMessage(pendingChanges: List<PendingChange>, onShowSummarySheet: () -> Unit) {
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("No more items to sort!")
        Spacer(modifier = Modifier.height(16.dp))
        if (pendingChanges.isNotEmpty()) {
            Button(onClick = onShowSummarySheet) { Text("Review Changes") }
        }
    }
}

@Composable
private fun ErrorMessage(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun AlreadyOrganizedDialog(
    onSelectNewFolders: () -> Unit,
    showResetHistoryButton: Boolean,
    onResetHistory: () -> Unit,
    onResetSingleFolderHistory: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "All media has been organized. Great job!",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onSelectNewFolders,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select Different Folders")
        }
        Button(
            onClick = onResetSingleFolderHistory,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reset Single Folder")
        }
        if (showResetHistoryButton) {
            Button(
                onClick = onResetHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset All Sorted Media")
            }
        }
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Close App")
        }
    }
}