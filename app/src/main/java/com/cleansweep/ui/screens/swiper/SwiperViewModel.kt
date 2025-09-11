package com.cleansweep.ui.screens.swiper

import android.content.Context
import android.os.Parcelable
import android.util.Log
import androidx.compose.ui.unit.DpOffset
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.AddFolderFocusTarget
import com.cleansweep.data.repository.FolderBarLayout
import com.cleansweep.data.repository.FolderNameLayout
import com.cleansweep.data.repository.PreferencesRepository
import com.cleansweep.data.repository.SummaryViewMode
import com.cleansweep.data.repository.SwipeSensitivity
import com.cleansweep.di.AppModule
import com.cleansweep.domain.bus.AppLifecycleEventBus
import com.cleansweep.domain.bus.FileModificationEvent
import com.cleansweep.domain.bus.FileModificationEventBus
import com.cleansweep.domain.bus.FolderDelta
import com.cleansweep.domain.bus.FolderUpdateEvent
import com.cleansweep.domain.bus.FolderUpdateEventBus
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.ui.components.FolderSearchManager
import com.cleansweep.ui.theme.AppTheme
import com.cleansweep.util.CoilPreloader
import com.cleansweep.util.FileOperationsHelper
import com.cleansweep.util.ThumbnailPrewarmer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

sealed class NavigationEvent {
    data object NavigateUp : NavigationEvent()
}

sealed class FolderMenuState {
    data object Hidden : FolderMenuState()
    data class Visible(val folderPath: String, val pressOffset: DpOffset) : FolderMenuState()
}

@Parcelize
data class PendingChange(
    val item: MediaItem,
    val action: SwiperAction,
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable

data class SwiperUiState(
    val isLoading: Boolean = true,
    val allMediaItems: List<MediaItem> = emptyList(),
    val currentIndex: Int = 0,
    val currentItem: MediaItem? = null,
    val error: String? = null,
    val showAddTargetFolderDialog: Boolean = false,
    val showForgetMediaSearchDialog: Boolean = false,
    val showSuccessAnimation: Boolean = false,
    val targetFolders: List<Pair<String, String>> = emptyList(),
    val allFolderPathsForDialog: List<Pair<String, String>> = emptyList(),
    val targetFavorites: Set<String> = emptySet(),
    val pendingChanges: List<PendingChange> = emptyList(),
    val showSummarySheet: Boolean = false,
    val compactFoldersView: Boolean = false,
    val hideFilename: Boolean = false,
    val summaryViewMode: SummaryViewMode = SummaryViewMode.LIST,
    val applyChangesButtonLabel: String = "Apply Changes",
    val isApplyingChanges: Boolean = false,
    val toastMessage: String? = null,
    val defaultCreationPath: String = "",
    val folderIdToNameMap: Map<String, String> = emptyMap(),
    val isSortingComplete: Boolean = false,
    val isFolderBarExpanded: Boolean = false,
    val useLegacyFolderIcons: Boolean = false,
    val folderMenuState: FolderMenuState = FolderMenuState.Hidden,
    val showMediaItemMenu: Boolean = false,
    val mediaItemMenuOffset: DpOffset = DpOffset.Zero,
    val videoPlaybackPosition: Long = 0L,
    val videoPlaybackSpeed: Float = 1.0f,
    val isVideoMuted: Boolean = true,
    val showRenameDialogForPath: String? = null,
    val showConfirmExitDialog: Boolean = false,
    val currentTheme: AppTheme = AppTheme.SYSTEM,
    val isCurrentItemPendingConversion: Boolean = false,

    // Pre-processed lists for Summary Sheet performance
    val toDelete: List<PendingChange> = emptyList(),
    val toKeep: List<PendingChange> = emptyList(),
    val toConvert: List<PendingChange> = emptyList(),
    val groupedMoves: List<Pair<String, List<PendingChange>>> = emptyList()
)

@HiltViewModel
class SwiperViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val fileOperationsHelper: FileOperationsHelper,
    private val thumbnailPrewarmer: ThumbnailPrewarmer,
    private val coilPreloader: CoilPreloader,
    private val savedStateHandle: SavedStateHandle,
    val imageLoader: ImageLoader, // Standard image loader
    @AppModule.GifImageLoader val gifImageLoader: ImageLoader, // GIF-specific image loader
    private val eventBus: FileModificationEventBus,
    private val appLifecycleEventBus: AppLifecycleEventBus,
    private val folderUpdateEventBus: FolderUpdateEventBus,
    val folderSearchManager: FolderSearchManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SwiperUiState())
    val uiState: StateFlow<SwiperUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent: SharedFlow<NavigationEvent> = _navigationEvent.asSharedFlow()

    private val newlyAddedTargetFolders = MutableStateFlow<Map<String, String>>(emptyMap())
    private val sessionHiddenTargetFolders = MutableStateFlow<Set<String>>(emptySet())

    val invertSwipe: StateFlow<Boolean> = preferencesRepository.invertSwipeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val swipeSensitivity: StateFlow<SwipeSensitivity> = preferencesRepository.swipeSensitivityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SwipeSensitivity.MEDIUM)

    val rememberProcessedMedia: StateFlow<Boolean> = preferencesRepository.rememberProcessedMediaFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val folderBarLayout: StateFlow<FolderBarLayout> =
        preferencesRepository.folderBarLayoutFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderBarLayout.HORIZONTAL)

    val folderNameLayout: StateFlow<FolderNameLayout> =
        preferencesRepository.folderNameLayoutFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderNameLayout.ABOVE)

    val expandSummarySheet: StateFlow<Boolean> =
        preferencesRepository.expandSummarySheetFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val addFolderFocusTarget: StateFlow<AddFolderFocusTarget> =
        preferencesRepository.addFolderFocusTargetFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AddFolderFocusTarget.SEARCH_PATH)

    val addFavoriteToTargetByDefault: StateFlow<Boolean> =
        preferencesRepository.addFavoriteToTargetByDefaultFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val hintOnExistingFolderName: StateFlow<Boolean> =
        preferencesRepository.hintOnExistingFolderNameFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val screenshotDeletesVideo: StateFlow<Boolean> =
        preferencesRepository.screenshotDeletesVideoFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val screenshotJpegQuality: StateFlow<String> =
        preferencesRepository.screenshotJpegQualityFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "90")


    private var bucketIds: List<String> = emptyList()
    private var _invertSwipe = false
    private var processedMediaIds = emptySet<String>()
    private var sessionProcessedMediaIds = mutableSetOf<String>()
    private var _rememberProcessedMediaEnabled = true
    private var _defaultVideoSpeed = 1.0f
    private var lastUsedTargetPath: String? = null
    private val unindexedFileCounter = AtomicInteger(0)
    private var folderDialogCollectionJob: Job? = null

    companion object {
        private const val TAG = "SwiperViewModel_DEBUG"
        private const val JIT_TAG = "SwiperViewModel_JIT"
    }

    init {
        loadSettings()
        observeTargetFolders()
        observeFileDeletions()
        observeAppLifecycle()
        val savedChanges: List<PendingChange>? = savedStateHandle["pendingChanges"]
        if (savedChanges != null) {
            _uiState.update { it.copy(pendingChanges = savedChanges) }
            processPendingChangesForSummary(savedChanges)
        }
    }

    override fun onCleared() {
        logJitSummary()
        folderDialogCollectionJob?.cancel()
        super.onCleared()
    }

    private fun logJitSummary() {
        val count = unindexedFileCounter.get()
        if (count > 0) {
            Log.d(JIT_TAG, "Session Summary: Queued $count un-indexed files for MediaStore pre-warming.")
        }
    }

    private fun observeAppLifecycle() {
        viewModelScope.launch {
            appLifecycleEventBus.appResumeEvent.collect {
                validateStateAndRefreshData()
            }
        }
    }

    private suspend fun validateStateAndRefreshData() {
        // No need to call invalidate here, as the BaseActivity -> MainViewModel flow handles it.
        var toastToShow: String? = null

        val currentFavorites = preferencesRepository.targetFavoriteFoldersFlow.first()
        if (currentFavorites.isNotEmpty()) {
            val existenceMap = mediaRepository.getFolderExistence(currentFavorites)
            val missingFavorites = currentFavorites.filter { existenceMap[it] == false }
            if (missingFavorites.isNotEmpty()) {
                missingFavorites.forEach { preferencesRepository.removeTargetFavoriteFolder(it) }
                toastToShow = if (missingFavorites.size == 1) {
                    "1 target favorite was removed as it no longer exists."
                } else {
                    "${missingFavorites.size} target favorites were removed as they no longer exist."
                }
            }
        }

        val sessionFolders = newlyAddedTargetFolders.value.keys
        if (sessionFolders.isNotEmpty()) {
            val existenceMap = mediaRepository.getFolderExistence(sessionFolders)
            val missingSessionFolders = sessionFolders.filter { existenceMap[it] == false }
            if (missingSessionFolders.isNotEmpty()) {
                newlyAddedTargetFolders.update { currentMap ->
                    currentMap.filterKeys { it !in missingSessionFolders }
                }
            }
        }

        val currentState = _uiState.value
        if (currentState.pendingChanges.isNotEmpty()) {
            val validChanges = fileOperationsHelper.filterExistingFiles(currentState.pendingChanges)
            if (validChanges.size != currentState.pendingChanges.size) {
                _uiState.update { it.copy(pendingChanges = validChanges) }
                processPendingChangesForSummary(validChanges)
                savedStateHandle["pendingChanges"] = ArrayList(validChanges)
                if (toastToShow == null) {
                    toastToShow = "Refreshed to account for external file changes."
                }
            }
        }

        toastToShow?.let { showToast(it) }

        initializeMedia(bucketIds)

        if (currentState.showAddTargetFolderDialog) {
            showAddTargetFolderDialog()
        }
        if (currentState.showForgetMediaSearchDialog) {
            showForgetMediaInFolderDialog()
        }
    }

    private fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            preferencesRepository.invertSwipeFlow.collect { isInverted -> _invertSwipe = isInverted }
        }
        viewModelScope.launch {
            preferencesRepository.rememberProcessedMediaFlow.collect { remember -> _rememberProcessedMediaEnabled = remember }
        }
        viewModelScope.launch {
            preferencesRepository.processedMediaPathsFlow.collect { paths ->
                processedMediaIds = if (_rememberProcessedMediaEnabled) paths else emptySet()
            }
        }
        viewModelScope.launch {
            preferencesRepository.defaultVideoSpeedFlow.collect { speed ->
                _defaultVideoSpeed = speed
                if (_uiState.value.currentItem?.isVideo == true) {
                    _uiState.update { it.copy(videoPlaybackSpeed = speed) }
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.useLegacyFolderIconsFlow.collect {
                _uiState.update { s -> s.copy(useLegacyFolderIcons = it) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.summaryViewModeFlow.collect { _uiState.update { s -> s.copy(summaryViewMode = it) } }
        }
        viewModelScope.launch {
            preferencesRepository.compactFolderViewFlow.collect { _uiState.update { s -> s.copy(compactFoldersView = it) } }
        }
        viewModelScope.launch {
            preferencesRepository.hideFilenameFlow.collect { _uiState.update { s -> s.copy(hideFilename = it) } }
        }
        viewModelScope.launch {
            preferencesRepository.defaultAlbumCreationPathFlow.collect { _uiState.update { s -> s.copy(defaultCreationPath = it) } }
        }
        viewModelScope.launch {
            preferencesRepository.themeFlow.collectLatest { theme ->
                _uiState.update { it.copy(currentTheme = theme) }
            }
        }
        viewModelScope.launch {
            val initialExpandedState = preferencesRepository.bottomBarExpandedFlow.first()
            _uiState.update { it.copy(isFolderBarExpanded = initialExpandedState) }
        }
    }

    private fun observeTargetFolders() {
        viewModelScope.launch {
            combine(
                preferencesRepository.targetFavoriteFoldersFlow,
                newlyAddedTargetFolders,
                sessionHiddenTargetFolders
            ) { favorites, newlyAdded, sessionHidden ->
                _uiState.update { it.copy(targetFavorites = favorites) }

                val allVisiblePaths = (favorites + newlyAdded.keys).toSet() - sessionHidden
                val folderDetails = if (allVisiblePaths.isNotEmpty()) {
                    mediaRepository.getFoldersFromPaths(allVisiblePaths)
                } else {
                    emptyList()
                }

                val allDetailsMap = folderDetails.toMap() + newlyAdded
                val finalTargetFolders = allVisiblePaths.mapNotNull { path ->
                    allDetailsMap[path]?.let { name -> path to name }
                }
                _uiState.update {
                    it.copy(
                        targetFolders = finalTargetFolders,
                        folderIdToNameMap = it.folderIdToNameMap + allDetailsMap
                    )
                }
            }.collect()
        }
    }

    private fun observeFileDeletions() {
        viewModelScope.launch {
            eventBus.events.collect { event ->
                if (event is FileModificationEvent.FilesDeleted) {
                    val deletedIds = event.paths.toSet()
                    val currentState = _uiState.value
                    val newMediaList = currentState.allMediaItems.filterNot { it.id in deletedIds }
                    val newPendingChanges = currentState.pendingChanges.filterNot { it.item.id in deletedIds }
                    processPendingChangesForSummary(newPendingChanges)
                    savedStateHandle["pendingChanges"] = ArrayList(newPendingChanges)

                    _uiState.update {
                        it.copy(
                            allMediaItems = newMediaList,
                            pendingChanges = newPendingChanges
                        )
                    }

                    if (currentState.currentItem?.id in deletedIds) {
                        advanceState(isDeletion = true)
                    }
                }
            }
        }
    }

    fun initializeMedia(sourceBucketIds: List<String>) {
        if (this.bucketIds == sourceBucketIds && !_uiState.value.isLoading && _uiState.value.allMediaItems.isNotEmpty()) return
        this.bucketIds = sourceBucketIds
        unindexedFileCounter.set(0)

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, allMediaItems = emptyList(), currentIndex = 0, currentItem = null, isSortingComplete = false) }

            try {
                val initialLayout = folderBarLayout.first()
                val lastExpandedState = preferencesRepository.bottomBarExpandedFlow.first()
                _uiState.update { it.copy(isFolderBarExpanded = initialLayout == FolderBarLayout.VERTICAL || lastExpandedState) }

                val latestProcessedPaths = if (_rememberProcessedMediaEnabled) {
                    preferencesRepository.processedMediaPathsFlow.first()
                } else {
                    emptySet()
                }
                processedMediaIds = latestProcessedPaths

                var initialItemFound = false
                val allItems = mutableListOf<MediaItem>()

                mediaRepository.getMediaFromBuckets(bucketIds)
                    .onCompletion {
                        if (!initialItemFound) {
                            _uiState.update {
                                it.copy(isLoading = false, isSortingComplete = true, currentItem = null, allMediaItems = allItems.toList())
                            }
                        }
                    }
                    .collect { newBatch ->
                        val unindexedInBatch = newBatch.filter { it.uri.scheme == "file" }.map { it.id }
                        if (unindexedInBatch.isNotEmpty()) {
                            unindexedFileCounter.addAndGet(unindexedInBatch.size)
                            viewModelScope.launch(Dispatchers.IO) {
                                Log.d(JIT_TAG, "Queueing ${unindexedInBatch.size} un-indexed files for background pre-warming.")
                                thumbnailPrewarmer.prewarm(unindexedInBatch)
                            }
                        }

                        allItems.addAll(newBatch)

                        if (!initialItemFound) {
                            val allProcessedIds = sessionProcessedMediaIds + processedMediaIds
                            val firstUnprocessedIndex = allItems.indexOfFirst { it.id !in allProcessedIds }
                            if (firstUnprocessedIndex != -1) {
                                initialItemFound = true
                                _uiState.update {
                                    it.copy(
                                        allMediaItems = allItems.toList(),
                                        isLoading = false,
                                        currentIndex = firstUnprocessedIndex,
                                        currentItem = allItems[firstUnprocessedIndex],
                                        videoPlaybackPosition = 0L,
                                        videoPlaybackSpeed = _defaultVideoSpeed,
                                        isCurrentItemPendingConversion = false
                                    )
                                }
                                prewarmNextThumbnails()
                            }
                        } else {
                            _uiState.update { it.copy(allMediaItems = allItems.toList()) }
                        }
                    }

            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Failed to load media: ${e.message}") }
            }
        }
    }

    private fun processPendingChangesForSummary(changes: List<PendingChange>) {
        val toDelete = changes.filter {
            it.action is SwiperAction.Delete || it.action is SwiperAction.ScreenshotAndDelete
        }
        val toKeep = changes.filter { it.action is SwiperAction.Keep }
        val toConvert = changes.filter {
            it.action is SwiperAction.Screenshot || it.action is SwiperAction.ScreenshotAndDelete
        }

        val movedChanges = changes.filter { it.action is SwiperAction.Move }
        val groupedMoves = movedChanges
            .groupBy { (it.action as SwiperAction.Move).targetFolderPath }
            .toList()
            .sortedBy { (folderId, _) -> _uiState.value.folderIdToNameMap[folderId]?.lowercase() ?: folderId }

        _uiState.update {
            it.copy(
                toDelete = toDelete,
                toKeep = toKeep,
                toConvert = toConvert,
                groupedMoves = groupedMoves
            )
        }
    }


    private fun addPendingChange(change: PendingChange) {
        val newChanges = _uiState.value.pendingChanges + change
        _uiState.update { it.copy(pendingChanges = newChanges) }
        processPendingChangesForSummary(newChanges)
        savedStateHandle["pendingChanges"] = ArrayList(newChanges)
        coilPreloader.preload(listOf(change.item))
    }

    private fun advanceState(isDeletion: Boolean = false) {
        prewarmNextThumbnails()

        val currentState = _uiState.value
        val allProcessedIds = sessionProcessedMediaIds +
                (if (_rememberProcessedMediaEnabled) processedMediaIds else emptySet()) +
                currentState.pendingChanges.map { it.item.id }.toSet()

        val searchStartIndex = if (isDeletion) currentState.currentIndex else currentState.currentIndex + 1

        val nextIndexInDroppedList = currentState.allMediaItems.drop(searchStartIndex)
            .indexOfFirst { it.id !in allProcessedIds }

        if (nextIndexInDroppedList != -1) {
            val actualIndex = searchStartIndex + nextIndexInDroppedList
            if (actualIndex < currentState.allMediaItems.size) {
                _uiState.update {
                    it.copy(
                        currentIndex = actualIndex,
                        currentItem = currentState.allMediaItems[actualIndex],
                        isSortingComplete = false,
                        videoPlaybackPosition = 0L,
                        videoPlaybackSpeed = _defaultVideoSpeed,
                        isVideoMuted = true,
                        isCurrentItemPendingConversion = false
                    )
                }
            } else {
                _uiState.update { it.copy(currentItem = null, isSortingComplete = true, showSummarySheet = it.pendingChanges.isNotEmpty(), isCurrentItemPendingConversion = false) }
            }
        } else {
            val hasPendingChanges = currentState.pendingChanges.isNotEmpty()
            _uiState.update { it.copy(
                currentItem = null,
                isSortingComplete = true,
                showSummarySheet = hasPendingChanges,
                videoPlaybackPosition = 0L,
                isCurrentItemPendingConversion = false
            ) }
        }
    }

    fun handleSwipeLeft() {
        val currentItem = _uiState.value.currentItem ?: return
        addPendingChange(PendingChange(currentItem, if (_invertSwipe) SwiperAction.Keep(currentItem) else SwiperAction.Delete(currentItem)))
        advanceState()
    }

    fun handleSwipeRight() {
        val currentItem = _uiState.value.currentItem ?: return
        addPendingChange(PendingChange(currentItem, if (_invertSwipe) SwiperAction.Delete(currentItem) else SwiperAction.Keep(currentItem)))
        advanceState()
    }

    fun moveToFolder(folderPath: String) {
        val currentItem = _uiState.value.currentItem ?: return
        addPendingChange(PendingChange(currentItem, SwiperAction.Move(currentItem, folderPath)))
        advanceState()
    }

    fun addScreenshotChange(timestampMicros: Long) {
        viewModelScope.launch {
            val currentItem = _uiState.value.currentItem ?: return@launch
            if (!currentItem.isVideo || _uiState.value.isCurrentItemPendingConversion) return@launch

            val deleteAfter = screenshotDeletesVideo.first()
            if (deleteAfter) {
                addPendingChange(PendingChange(currentItem, SwiperAction.ScreenshotAndDelete(currentItem, timestampMicros)))
                advanceState()
            } else {
                addPendingChange(PendingChange(currentItem, SwiperAction.Screenshot(currentItem, timestampMicros)))
                _uiState.update {
                    it.copy(
                        isCurrentItemPendingConversion = true,
                        toastMessage = "Added screenshot to pending changes"
                    )
                }
            }
            dismissMediaItemMenu()
        }
    }

    fun applyChanges() {
        viewModelScope.launch {
            _uiState.update { it.copy(isApplyingChanges = true) }

            val initialChanges = _uiState.value.pendingChanges
            if (initialChanges.isEmpty()) {
                Log.d(TAG, "applyChanges: No pending changes. Completing.")
                _uiState.update { it.copy(isApplyingChanges = false) }
                return@launch
            }
            Log.d(TAG, "applyChanges: Found ${initialChanges.size} initial pending changes.")

            // --- VALIDATION STEP ---
            val validatedChanges = fileOperationsHelper.filterExistingFiles(initialChanges)
            val missingCount = initialChanges.size - validatedChanges.size

            if (missingCount > 0) {
                showToast("$missingCount files were not found and will be skipped.")
                _uiState.update { it.copy(pendingChanges = validatedChanges) }
                processPendingChangesForSummary(validatedChanges)
                savedStateHandle["pendingChanges"] = ArrayList(validatedChanges)
            }

            if (validatedChanges.isEmpty()) {
                Log.d(TAG, "applyChanges: All pending changes were for non-existent files. Aborting.")
                _uiState.update { it.copy(isApplyingChanges = false, showSummarySheet = false) }
                return@launch
            }
            // --- END VALIDATION ---

            val finalChanges = synchronizeUnindexedChanges(validatedChanges)
            if (finalChanges == null) {
                _uiState.update { it.copy(isApplyingChanges = false, error = "Failed to sync with MediaStore.") }
                return@launch
            }

            val conversionActions = finalChanges.filter {
                it.action is SwiperAction.Screenshot || it.action is SwiperAction.ScreenshotAndDelete
            }
            val conversionResults = mutableMapOf<String, MediaItem>()
            if (conversionActions.isNotEmpty()) {
                val quality = screenshotJpegQuality.first().toIntOrNull() ?: 90
                val conversionJobs = conversionActions.map { change ->
                    async {
                        val timestamp = when (val action = change.action) {
                            is SwiperAction.Screenshot -> action.timestampMicros
                            is SwiperAction.ScreenshotAndDelete -> action.timestampMicros
                            else -> -1L
                        }
                        fileOperationsHelper.convertVideoToImage(change.item, timestamp, quality).getOrNull()?.let { newItem ->
                            change.item.id to newItem
                        }
                    }
                }
                conversionResults.putAll(conversionJobs.awaitAll().filterNotNull().toMap())
            }

            val finalMoveMap = mutableMapOf<String, String>()
            val itemsToDelete = mutableListOf<MediaItem>()

            for ((itemId, changes) in finalChanges.groupBy { it.item.id }) {
                val originalItem = changes.first().item
                val wasConverted = conversionResults.containsKey(itemId)
                val newImageItem = conversionResults[itemId]

                val hasMove = changes.any { it.action is SwiperAction.Move }
                val hasDelete = changes.any { it.action is SwiperAction.Delete }
                val hasScreenshotAndDelete = changes.any { it.action is SwiperAction.ScreenshotAndDelete }

                if (hasScreenshotAndDelete) {
                    itemsToDelete.add(originalItem)
                    if (newImageItem != null) {
                        changes.find { it.action is SwiperAction.Move }?.let { moveChange ->
                            val moveAction = moveChange.action as SwiperAction.Move
                            finalMoveMap[newImageItem.id] = moveAction.targetFolderPath
                        }
                    }
                } else if (wasConverted && newImageItem != null) {
                    if (hasMove) {
                        val moveAction = changes.first { it.action is SwiperAction.Move }.action as SwiperAction.Move
                        finalMoveMap[newImageItem.id] = moveAction.targetFolderPath
                    }
                    if (hasDelete) {
                        itemsToDelete.add(originalItem)
                    }
                } else {
                    if (hasMove) {
                        val moveAction = changes.first { it.action is SwiperAction.Move }.action as SwiperAction.Move
                        finalMoveMap[originalItem.id] = moveAction.targetFolderPath
                    } else if (hasDelete) {
                        itemsToDelete.add(originalItem)
                    }
                }
            }

            var success = true
            var moveResults: Map<String, MediaItem> = emptyMap()

            if (finalMoveMap.isNotEmpty()) {
                Log.d(TAG, "Executing move for ${finalMoveMap.size} files.")
                moveResults = mediaRepository.moveMedia(finalMoveMap.keys.toList(), finalMoveMap.values.toList())
                if (moveResults.size != finalMoveMap.size) success = false
            }

            if (itemsToDelete.isNotEmpty()) {
                Log.d(TAG, "Executing delete for ${itemsToDelete.size} files.")
                val deleteSuccess = mediaRepository.deleteMedia(itemsToDelete)
                if (!deleteSuccess) success = false
            }

            completeChanges(success, validatedChanges, moveResults)
        }
    }


    private suspend fun synchronizeUnindexedChanges(changes: List<PendingChange>): List<PendingChange>? {
        val unindexedChanges = changes.filter { it.item.uri.scheme == "file" }
        if (unindexedChanges.isEmpty()) {
            return changes // No un-indexed items, return original list
        }

        val pathsToScan = unindexedChanges.map { it.item.id }
        Log.d(TAG, "synchronizeUnindexedChanges: Found ${pathsToScan.size} items with file:// URIs. Scanning.")

        val scanSuccess = mediaRepository.scanPathsAndWait(pathsToScan)
        if (!scanSuccess) {
            Log.e(TAG, "synchronizeUnindexedChanges: scanPathsAndWait FAILED.")
            return null
        }

        Log.d(TAG, "synchronizeUnindexedChanges: Scan successful. Fetching refreshed items.")
        val refreshedItemsMap = mediaRepository.getMediaItemsFromPaths(pathsToScan).associateBy { it.id }

        // Create a new list, updating with refreshed items where available
        return changes.map { originalChange ->
            refreshedItemsMap[originalChange.item.id]?.let { refreshedItem ->
                originalChange.copy(item = refreshedItem) // Update with new MediaItem
            } ?: originalChange // Keep original if it failed to index
        }
    }


    private fun completeChanges(
        success: Boolean,
        originalChanges: List<PendingChange>,
        moveResults: Map<String, MediaItem> = emptyMap()
    ) {
        if (success) {
            viewModelScope.launch {
                val folderDeltas = mutableMapOf<String, FolderDelta>()

                // Calculate net changes for each folder
                originalChanges.forEach { change ->
                    val item = change.item
                    File(item.id).parent?.let { folderPath ->
                        val currentDelta = folderDeltas.getOrDefault(folderPath, FolderDelta(0, 0L))
                        folderDeltas[folderPath] = currentDelta.copy(
                            itemCountChange = currentDelta.itemCountChange - 1,
                            sizeChange = currentDelta.sizeChange - item.size
                        )
                    }
                }
                moveResults.values.forEach { newItem ->
                    File(newItem.id).parent?.let { folderPath ->
                        val currentDelta = folderDeltas.getOrDefault(folderPath, FolderDelta(0, 0L))
                        folderDeltas[folderPath] = currentDelta.copy(
                            itemCountChange = currentDelta.itemCountChange + 1,
                            sizeChange = currentDelta.sizeChange + newItem.size
                        )
                    }
                }

                if (folderDeltas.isNotEmpty()) {
                    folderUpdateEventBus.post(FolderUpdateEvent.FolderBatchUpdate(folderDeltas))
                }

                val originalPaths = originalChanges.map { it.item.id }.toSet()
                val processedPaths = (originalPaths + moveResults.values.map { it.id }).toSet()

                sessionProcessedMediaIds.addAll(processedPaths)

                if (_rememberProcessedMediaEnabled) {
                    withContext(NonCancellable) { preferencesRepository.addProcessedMediaPaths(processedPaths) }
                }

                val emptyChanges = emptyList<PendingChange>()
                _uiState.update { it.copy(
                    pendingChanges = emptyChanges,
                    showSummarySheet = false,
                    isApplyingChanges = false,
                    toastMessage = "Changes applied successfully!"
                )}
                processPendingChangesForSummary(emptyChanges)
                savedStateHandle["pendingChanges"] = null
            }
        } else {
            _uiState.update { it.copy(
                error = "Failed to apply one or more changes.",
                showSummarySheet = true,
                isApplyingChanges = false
            )}
        }
    }


    fun showAddTargetFolderDialog() {
        folderDialogCollectionJob?.cancel()
        folderDialogCollectionJob = viewModelScope.launch {
            mediaRepository.observeFoldersForTargetDialog().collect { allFolders ->
                _uiState.update { it.copy(allFolderPathsForDialog = allFolders) }

                // Only prepare the search manager if the dialog is already open,
                // to avoid overwriting the initial cached list.
                if (_uiState.value.showAddTargetFolderDialog) {
                    val initialPath = lastUsedTargetPath ?: _uiState.value.defaultCreationPath
                    folderSearchManager.prepareWithPreFilteredList(allFolders, initialPath = initialPath)
                }
            }
        }

        // This part runs immediately, without waiting for the flow collection to start.
        viewModelScope.launch {
            val initialPath = lastUsedTargetPath ?: _uiState.value.defaultCreationPath
            val cachedFolders = mediaRepository.getCachedFolderSnapshot()
            folderSearchManager.prepareWithPreFilteredList(cachedFolders, initialPath = initialPath)
            _uiState.update { it.copy(showAddTargetFolderDialog = true) }
        }
    }

    fun showForgetMediaInFolderDialog() {
        viewModelScope.launch {
            val foldersWithHistory = mediaRepository.getFoldersWithProcessedMedia()
            if (foldersWithHistory.isEmpty()) {
                _uiState.update { it.copy(toastMessage = "No sorted media history found.") }
                return@launch
            }
            folderSearchManager.prepareWithPreFilteredList(foldersWithHistory)
            _uiState.update { it.copy(showForgetMediaSearchDialog = true) }
        }
    }

    fun onPathSelected(path: String) {
        viewModelScope.launch {
            folderSearchManager.selectPath(path)
        }
    }

    fun onSearchFocusChanged(isFocused: Boolean) {
        if (!isFocused) {
            val searchState = folderSearchManager.state.value
            if (searchState.searchQuery.isNotBlank()) {
                val results = searchState.displayedResults
                when (results.size) {
                    1 -> {
                        viewModelScope.launch {
                            folderSearchManager.selectPath(results.first())
                        }
                    }
                    else -> {
                        folderSearchManager.updateSearchQuery("")
                    }
                }
            }
        }
    }

    fun dismissAddTargetFolderDialog() {
        folderDialogCollectionJob?.cancel()
        folderSearchManager.reset()
        _uiState.update { it.copy(showAddTargetFolderDialog = false) }
    }

    fun dismissForgetMediaSearchDialog() {
        folderSearchManager.reset()
        _uiState.update { it.copy(showForgetMediaSearchDialog = false) }
    }

    fun forgetSortedMediaInFolder(folderPath: String) {
        viewModelScope.launch {
            // Remove from persistent storage
            preferencesRepository.removeProcessedMediaPathsInFolder(folderPath)
            preferencesRepository.removePermanentlySortedFolder(folderPath)

            // Also remove from session-specific history to handle partially-sorted folders
            val removedFromSession = sessionProcessedMediaIds.removeAll { path ->
                try {
                    File(path).parent == folderPath
                } catch (e: Exception) {
                    false
                }
            }
            if (removedFromSession) {
                Log.d(TAG, "Removed session-sorted media from '$folderPath'.")
            }

            _uiState.update { it.copy(toastMessage = "'${File(folderPath).name}' history has been reset.") }
            // Re-initialize media to reflect the changes immediately
            initializeMedia(bucketIds)
        }
    }


    fun confirmFolderSelection(newFolderName: String, addToFavorites: Boolean, alsoMove: Boolean) {
        val searchState = folderSearchManager.state.value

        if (newFolderName.isNotBlank()) {
            val parentPath = searchState.browsePath ?: searchState.searchQuery
            if (parentPath.isNotBlank()) {
                createAndAddTargetFolder(newFolderName, parentPath, addToFavorites, alsoMove)
            } else {
                _uiState.update { it.copy(toastMessage = "Please select a parent folder location.") }
            }
        }
        else {
            val importPath = searchState.browsePath
            if (importPath != null) {
                importTargetFolder(importPath, addToFavorites, alsoMove)
            } else {
                _uiState.update { it.copy(toastMessage = "Please select a folder to import.") }
            }
        }
    }

    fun resetFolderSelectionToDefault() {
        viewModelScope.launch {
            val defaultPath = _uiState.value.defaultCreationPath
            folderSearchManager.prepareForSearch(
                initialPath = defaultPath,
                coroutineScope = viewModelScope,
                excludedFolders = emptySet(),
                includePermanentlySorted = true
            )
        }
    }

    private fun createAndAddTargetFolder(newFolderName: String, parentPath: String, addToFavorites: Boolean, alsoMove: Boolean) {
        viewModelScope.launch {
            mediaRepository.createNewFolder(newFolderName, parentPath).onSuccess { newFolderPath ->
                lastUsedTargetPath = parentPath
                newlyAddedTargetFolders.update { it + (newFolderPath to newFolderName) }
                sessionHiddenTargetFolders.update { it - newFolderPath }
                folderUpdateEventBus.post(FolderUpdateEvent.FolderAdded(newFolderPath, newFolderName))

                if (addToFavorites) {
                    preferencesRepository.addTargetFavoriteFolder(newFolderPath)
                }
                if (alsoMove) {
                    moveToFolder(newFolderPath)
                }
                dismissAddTargetFolderDialog()
            }.onFailure { exception ->
                _uiState.update { it.copy(toastMessage = "Error: ${exception.message}") }
            }
        }
    }

    private fun importTargetFolder(path: String, addToFavorites: Boolean, alsoMove: Boolean) {
        viewModelScope.launch {
            lastUsedTargetPath = path
            val name = mediaRepository.getFolderNames(setOf(path))[path] ?: path.substringAfterLast('/')
            newlyAddedTargetFolders.update { it + (path to name) }
            sessionHiddenTargetFolders.update { it - path }
            folderUpdateEventBus.post(FolderUpdateEvent.FolderAdded(path, name))

            if (addToFavorites) {
                preferencesRepository.addTargetFavoriteFolder(path)
            }
            if (alsoMove) {
                moveToFolder(path)
            }
            dismissAddTargetFolderDialog()
        }
    }

    fun showFolderMenu(path: String, pressOffset: DpOffset) {
        _uiState.update { it.copy(folderMenuState = FolderMenuState.Visible(path, pressOffset)) }
    }

    fun dismissFolderMenu() {
        _uiState.update { it.copy(folderMenuState = FolderMenuState.Hidden) }
    }

    fun showRenameDialog(path: String) {
        dismissFolderMenu()
        _uiState.update { it.copy(showRenameDialogForPath = path) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(showRenameDialogForPath = null) }
    }

    fun renameTargetFolder(oldPath: String, newName: String) {
        viewModelScope.launch {
            fileOperationsHelper.renameFolder(oldPath, newName).onSuccess { newPath ->
                preferencesRepository.updateFolderPath(oldPath, newPath)
                if (newlyAddedTargetFolders.value.containsKey(oldPath)) {
                    newlyAddedTargetFolders.update { (it - oldPath) + (newPath to newName) }
                }
                val currentChanges = _uiState.value.pendingChanges
                val updatedChanges = currentChanges.map { change ->
                    if (change.action is SwiperAction.Move && change.action.targetFolderPath == oldPath) {
                        change.copy(action = SwiperAction.Move(change.item, newPath))
                    } else {
                        change
                    }
                }
                _uiState.update { it.copy(pendingChanges = updatedChanges) }
                processPendingChangesForSummary(updatedChanges)

                _uiState.update { it.copy(
                    toastMessage = "Folder renamed successfully.",
                    showRenameDialogForPath = null
                )}
            }.onFailure { error ->
                _uiState.update { it.copy(
                    toastMessage = "Error: ${error.message}",
                    showRenameDialogForPath = null
                )}
            }
        }
    }


    fun showMediaItemMenu(offset: DpOffset) {
        _uiState.update { it.copy(showMediaItemMenu = true, mediaItemMenuOffset = offset) }
    }

    fun dismissMediaItemMenu() {
        _uiState.update { it.copy(showMediaItemMenu = false) }
    }

    fun toggleTargetFavorite(folderPath: String) {
        viewModelScope.launch {
            if (folderPath in _uiState.value.targetFavorites) {
                val folderName = _uiState.value.folderIdToNameMap[folderPath]
                if (folderName != null) {
                    newlyAddedTargetFolders.update { it + (folderPath to folderName) }
                }
                preferencesRepository.removeTargetFavoriteFolder(folderPath)
            } else {
                sessionHiddenTargetFolders.update { it - folderPath }
                preferencesRepository.addTargetFavoriteFolder(folderPath)
            }
            dismissFolderMenu()
        }
    }

    fun removeTargetFolder(folderPath: String) {
        viewModelScope.launch {
            sessionHiddenTargetFolders.update { it + folderPath }
            newlyAddedTargetFolders.update { it - folderPath }
            dismissFolderMenu()
        }
    }

    fun onNavigateUp() {
        logJitSummary()
        if (_uiState.value.pendingChanges.isNotEmpty()) {
            _uiState.update { it.copy(showConfirmExitDialog = true) }
        } else {
            sessionHiddenTargetFolders.value = emptySet()
            viewModelScope.launch {
                _navigationEvent.emit(NavigationEvent.NavigateUp)
            }
        }
    }

    fun confirmExit() {
        viewModelScope.launch {
            logJitSummary()
            sessionHiddenTargetFolders.value = emptySet()
            _uiState.update { it.copy(showConfirmExitDialog = false) }
            _navigationEvent.emit(NavigationEvent.NavigateUp)
        }
    }

    fun cancelExit() {
        _uiState.update { it.copy(showConfirmExitDialog = false) }
    }

    fun cancelExitAndShowSummary() {
        _uiState.update { it.copy(showConfirmExitDialog = false, showSummarySheet = true) }
    }

    fun toggleFolderBarExpansion() {
        viewModelScope.launch {
            val isCurrentlyExpanded = _uiState.value.isFolderBarExpanded
            val newExpandedState = !isCurrentlyExpanded
            preferencesRepository.setBottomBarExpanded(newExpandedState)
            _uiState.update { it.copy(isFolderBarExpanded = newExpandedState) }
        }
    }

    fun setFolderBarExpanded(isExpanded: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setBottomBarExpanded(isExpanded)
            _uiState.update { it.copy(isFolderBarExpanded = isExpanded) }
        }
    }

    fun showSummarySheet() { _uiState.update { it.copy(showSummarySheet = true) } }
    fun dismissSummarySheet() { _uiState.update { it.copy(showSummarySheet = false) } }

    fun revertLastChange() {
        val lastChange = _uiState.value.pendingChanges.maxByOrNull { it.timestamp }
        if (lastChange != null) {
            revertChange(lastChange)
        }
    }

    fun revertChange(changeToRevert: PendingChange) {
        val updatedPendingChanges = _uiState.value.pendingChanges.filterNot { it.timestamp == changeToRevert.timestamp }
        savedStateHandle["pendingChanges"] = ArrayList(updatedPendingChanges)
        processPendingChangesForSummary(updatedPendingChanges)

        val currentState = _uiState.value
        val originalItemToRestoreId = changeToRevert.item.id
        val restoredItemIndex = currentState.allMediaItems.indexOfFirst { it.id == originalItemToRestoreId }
        val currentSwiperIndex = currentState.currentIndex

        // Case 1: The reverted item is the one currently being displayed.
        if (currentState.currentItem?.id == originalItemToRestoreId) {
            val isScreenshotRevert = changeToRevert.action is SwiperAction.Screenshot
            _uiState.update {
                it.copy(
                    pendingChanges = updatedPendingChanges,
                    // Only reset the conversion flag if it was a screenshot revert
                    isCurrentItemPendingConversion = if (isScreenshotRevert) false else it.isCurrentItemPendingConversion
                )
            }
        }
        // Case 2: The reverted item is a previous item in the swiper list.
        else if (restoredItemIndex != -1 && (restoredItemIndex < currentSwiperIndex || currentState.isSortingComplete)) {
            _uiState.update {
                it.copy(
                    pendingChanges = updatedPendingChanges,
                    showSummarySheet = false,
                    currentItem = it.allMediaItems[restoredItemIndex],
                    currentIndex = restoredItemIndex,
                    isSortingComplete = false,
                    error = null,
                    videoPlaybackPosition = 0L,
                    isVideoMuted = true,
                    isCurrentItemPendingConversion = false
                )
            }
        }
        // Case 3: The item can't be restored to the view (e.g., it's ahead in the queue), so just update the list.
        else {
            val shouldDismissSheet = updatedPendingChanges.isEmpty()
            _uiState.update { it.copy(pendingChanges = updatedPendingChanges, showSummarySheet = !shouldDismissSheet) }
        }
    }


    fun resetPendingChanges() {
        val emptyChanges = emptyList<PendingChange>()
        _uiState.update { it.copy(
            pendingChanges = emptyChanges,
            showSummarySheet = false,
            isCurrentItemPendingConversion = false // Reset conversion state
        ) }
        processPendingChangesForSummary(emptyChanges)
        savedStateHandle["pendingChanges"] = null

        val allProcessedIds = sessionProcessedMediaIds +
                (if (_rememberProcessedMediaEnabled) processedMediaIds else emptySet())
        val firstUnprocessedIndex = _uiState.value.allMediaItems.indexOfFirst { it.id !in allProcessedIds }

        if (firstUnprocessedIndex != -1) {
            _uiState.update {
                it.copy(
                    currentItem = it.allMediaItems[firstUnprocessedIndex],
                    currentIndex = firstUnprocessedIndex,
                    isSortingComplete = false,
                    error = null
                )
            }
        } else {
            _uiState.update { it.copy(isSortingComplete = true, currentItem = null) }
        }
    }

    fun resetProcessedMedia() {
        viewModelScope.launch {
            preferencesRepository.clearProcessedMediaPaths()
            preferencesRepository.clearPermanentlySortedFolders()
            sessionProcessedMediaIds.clear()
            _uiState.update { it.copy(toastMessage = "Sorted media history has been reset.") }
            initializeMedia(bucketIds)
        }
    }

    fun toggleSummaryViewMode() {
        viewModelScope.launch {
            val nextMode = when (_uiState.value.summaryViewMode) {
                SummaryViewMode.LIST -> SummaryViewMode.GRID
                SummaryViewMode.GRID -> SummaryViewMode.COMPACT
                SummaryViewMode.COMPACT -> SummaryViewMode.LIST
            }
            preferencesRepository.setSummaryViewMode(nextMode)
        }
    }

    fun toastMessageShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun saveVideoPlaybackPosition(position: Long) {
        if (position > 0) {
            _uiState.update { it.copy(videoPlaybackPosition = position) }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(videoPlaybackSpeed = speed) }
    }

    fun toggleMute(hasAudio: Boolean) {
        if (_uiState.value.currentItem?.isVideo != true) return

        val currentlyMuted = _uiState.value.isVideoMuted
        if (currentlyMuted) {
            if (hasAudio) {
                _uiState.update { it.copy(isVideoMuted = false) }
            } else {
                _uiState.update { it.copy(toastMessage = "Video has no audio track.") }
            }
        } else {
            _uiState.update { it.copy(isVideoMuted = true) }
        }
    }

    private fun prewarmNextThumbnails() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.allMediaItems.isEmpty()) return@launch

            val startIndex = state.currentIndex + 1
            val endIndex = (startIndex + 3).coerceAtMost(state.allMediaItems.size)

            for (i in startIndex until endIndex) {
                val item = state.allMediaItems[i]
                if (!item.isVideo) {
                    val request = ImageRequest.Builder(context).data(item.uri).build()
                    imageLoader.enqueue(request)
                }
            }
        }
    }
}

@Parcelize
sealed class SwiperAction : Parcelable {
    @Parcelize
    data class Keep(val item: MediaItem) : SwiperAction()
    @Parcelize
    data class Delete(val item: MediaItem) : SwiperAction()
    @Parcelize
    data class Move(val item: MediaItem, val targetFolderPath: String) : SwiperAction()
    @Parcelize
    data class Screenshot(val item: MediaItem, val timestampMicros: Long = -1L) : SwiperAction()
    @Parcelize
    data class ScreenshotAndDelete(val item: MediaItem, val timestampMicros: Long = -1L) : SwiperAction()
}
