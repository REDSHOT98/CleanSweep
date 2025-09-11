package com.cleansweep.ui.screens.session

import android.os.Environment
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.data.repository.FolderSelectionMode
import com.cleansweep.data.repository.PreferencesRepository
import com.cleansweep.domain.bus.FolderUpdateEvent
import com.cleansweep.domain.bus.FolderUpdateEventBus
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.ui.components.FolderSearchManager
import com.cleansweep.util.FileOperationsHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Comparator
import javax.inject.Inject

// Define folder sorting options
enum class FolderSortOption {
    ALPHABETICAL_ASC,
    ALPHABETICAL_DESC,
    SIZE_ASC,
    SIZE_DESC
}

// Define folder category
data class FolderCategory(
    val name: String,
    val folders: List<FolderInfo>
)

// Enhanced folder info
data class FolderDetails(
    val path: String, // The absolute file path, which is the unique ID
    val name: String,
    val itemCount: Int,
    val totalSize: Long,
    val isSystemFolder: Boolean,
    val isPrimarySystemFolder: Boolean = false // New: Flag for canonical system folders
)

// Temporary FolderInfo class for UI compatibility
data class FolderInfo(
    val bucketId: String,
    val bucketName: String,
    val itemCount: Int,
    val totalSize: Long,
    val isSystemFolder: Boolean,
    val isPrimarySystemFolder: Boolean // New: Flag for canonical system folders
)

data class SessionSetupUiState(
    val isInitialLoad: Boolean = true,
    val allFolderDetails: List<FolderDetails> = emptyList(),
    val folderCategories: List<FolderCategory> = emptyList(),
    val selectedBuckets: List<String> = emptyList(), // This now holds folder paths
    val isRefreshing: Boolean = false,
    val isDataStale: Boolean = false, // New flag for instant refresh feedback
    val error: String? = null,
    val currentSortOption: FolderSortOption = FolderSortOption.SIZE_DESC, // Default sort by size
    val searchQuery: String = "",
    val favoriteFolders: Set<String> = emptySet(),
    val showFavoritesInSetup: Boolean = true,
    val recursivelySelectedRoots: Set<String> = emptySet(),
    val showRenameDialogForPath: String? = null,
    val toastMessage: String? = null,
    val showMoveFolderDialogForPath: String? = null,

    // Mark as Sorted Dialog State
    val showMarkAsSortedConfirmation: Boolean = false,
    val foldersToMarkAsSorted: List<FolderInfo> = emptyList(),
    val dontAskAgainMarkAsSorted: Boolean = false,

    // Contextual Selection Mode
    val isContextualSelectionMode: Boolean = false,
    val contextSelectedFolderPaths: Set<String> = emptySet(),
    val canFavoriteContextualSelection: Boolean = false
)

@HiltViewModel
class SessionSetupViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val fileOperationsHelper: FileOperationsHelper,
    private val folderUpdateEventBus: FolderUpdateEventBus,
    val folderSearchManager: FolderSearchManager,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionSetupUiState())
    val uiState: StateFlow<SessionSetupUiState> = _uiState.asStateFlow()

    val searchAutofocusEnabled: StateFlow<Boolean> =
        preferencesRepository.searchAutofocusEnabledFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false
            )

    private var hasInitializedSelection = false

    companion object {
        private const val TAG = "SessionSetupViewModel"
        private const val MIN_REFRESH_DISPLAY_TIME_MS = 500L
    }

    init {
        observeFolderUpdates()
        val forceRefresh: Boolean = savedStateHandle.get<Boolean>("forceRefresh") ?: false
        if (forceRefresh) {
            _uiState.update { it.copy(isInitialLoad = true) }
        }
        observeAndProcessFolderDetails()
    }

    private fun observeFolderUpdates() {
        viewModelScope.launch {
            folderUpdateEventBus.events.collect { event ->
                if (event is FolderUpdateEvent.FullRefreshRequired) {
                    Log.d(TAG, "FullRefreshRequired event received. Marking data as stale.")
                    hasInitializedSelection = false // Reset selection logic
                    _uiState.update { it.copy(isDataStale = true) } // Show loading indicator immediately
                }
            }
        }
    }

    private fun observeAndProcessFolderDetails() {
        viewModelScope.launch {
            val dbFolderDetailsFlow = mediaRepository.observeMediaFoldersWithDetails()

            val favoritesFlow = preferencesRepository.sourceFavoriteFoldersFlow
            val showFavoritesFlow = preferencesRepository.showFavoritesFirstInSetupFlow
            val searchQueryFlow = _uiState.map { it.searchQuery }.distinctUntilChanged()
            val sortOptionFlow = _uiState.map { it.currentSortOption }.distinctUntilChanged()

            combine(
                dbFolderDetailsFlow,
                favoritesFlow,
                showFavoritesFlow,
                searchQueryFlow,
                sortOptionFlow
            ) { foldersToProcess, favorites, showFavorites, query, sortOption ->

                val validFolders = foldersToProcess.filter { it.itemCount > 0 }
                val enrichedFolders = enrichWithPrimarySystemFolders(validFolders)

                val searchedFolders = if (query.isBlank()) {
                    enrichedFolders
                } else {
                    enrichedFolders.filter { it.name.contains(query, ignoreCase = true) }
                }

                val favoriteFolders = searchedFolders.filter { it.path in favorites }
                val nonFavoriteFolders = searchedFolders.filter { it.path !in favorites }
                val systemFolders = nonFavoriteFolders.filter { it.isSystemFolder }
                val userFolders = nonFavoriteFolders.filter { !it.isSystemFolder }

                val categories = listOfNotNull(
                    if (showFavorites && favoriteFolders.isNotEmpty()) FolderCategory("Favorite Folders", favoriteFolders.map { it.toFolderInfo() }) else null,
                    if (systemFolders.isNotEmpty()) FolderCategory("System Folders", systemFolders.map { it.toFolderInfo() }) else null,
                    if (userFolders.isNotEmpty()) FolderCategory("User Folders", userFolders.map { it.toFolderInfo() }) else null
                )

                val sortedCategories = categories.map { category ->
                    val primarySort: Comparator<FolderInfo> = if (category.name == "System Folders") {
                        compareByDescending { it.isPrimarySystemFolder }
                    } else {
                        compareBy { 0 }
                    }

                    val secondarySort: Comparator<FolderInfo> = when (sortOption) {
                        FolderSortOption.ALPHABETICAL_ASC -> compareBy { it.bucketName.lowercase() }
                        FolderSortOption.ALPHABETICAL_DESC -> compareByDescending { it.bucketName.lowercase() }
                        FolderSortOption.SIZE_ASC -> compareBy { it.totalSize }
                        FolderSortOption.SIZE_DESC -> compareByDescending { it.totalSize }
                    }

                    category.copy(folders = category.folders.sortedWith(primarySort.then(secondarySort)))
                }
                Triple(sortedCategories, favorites, validFolders)
            }.catch { e ->
                Log.e(TAG, "Error in folder processing flow", e)
                _uiState.update { it.copy(error = "Failed to load media folders: ${e.message}") }
            }.collect { (newCategories, newFavorites, allFolders) ->
                _uiState.update { currentState ->
                    val allAvailableFolderPaths = allFolders.map { it.path }.toSet()
                    val sanitizedSelection = currentState.selectedBuckets.filter { it in allAvailableFolderPaths }

                    val isStillLoading = allFolders.isEmpty() && currentState.isInitialLoad

                    currentState.copy(
                        folderCategories = newCategories,
                        favoriteFolders = newFavorites,
                        allFolderDetails = allFolders,
                        selectedBuckets = sanitizedSelection,
                        isInitialLoad = isStillLoading,
                        isDataStale = false // Data has arrived, clear the stale flag
                    )
                }

                if (!hasInitializedSelection && allFolders.isNotEmpty()) {
                    initializeSelection(allFolders)
                    hasInitializedSelection = true
                }
            }
        }
    }


    private fun initializeSelection(allFolders: List<FolderDetails>) {
        viewModelScope.launch {
            val folderSelectionMode = preferencesRepository.folderSelectionModeFlow.first()
            val previouslySelectedBuckets = preferencesRepository.previouslySelectedBucketsFlow.first()
            val favoriteFolders = preferencesRepository.sourceFavoriteFoldersFlow.first()

            val allPaths = allFolders.map { it.path }.toSet()
            val baseSelection = when (folderSelectionMode) {
                FolderSelectionMode.ALL -> allPaths.toList()
                FolderSelectionMode.REMEMBER -> previouslySelectedBuckets.filter { it in allPaths }
                FolderSelectionMode.NONE -> emptyList()
            }
            val initialSelection = (baseSelection + favoriteFolders).distinct().filter { it in allPaths }

            _uiState.update { state ->
                state.copy(selectedBuckets = initialSelection)
            }
            Log.d(TAG, "Initial selection has been set with mode: $folderSelectionMode")
        }
    }


    fun refreshFolders() {
        viewModelScope.launch {
            if (_uiState.value.isRefreshing) {
                Log.d(TAG, "Refresh: Refresh already in progress. Ignoring request.")
                return@launch
            }
            Log.d(TAG, "Refreshing folders...")
            val startTime = System.currentTimeMillis()
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                // This manually-triggered refresh still performs a full scan.
                // The underlying flow will update automatically once the DB is repopulated.
                mediaRepository.getMediaFoldersWithDetails(forceRefresh = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing folders", e)
                _uiState.update {
                    it.copy(error = "Failed to refresh media folders: ${e.message}")
                }
            } finally {
                withContext(NonCancellable) {
                    val elapsedTime = System.currentTimeMillis() - startTime
                    val remainingTime = MIN_REFRESH_DISPLAY_TIME_MS - elapsedTime
                    if (remainingTime > 0) {
                        Log.d(TAG, "Refresh finished in ${elapsedTime}ms. Delaying for ${remainingTime}ms.")
                        delay(remainingTime)
                    }
                    Log.d(TAG, "Refresh flow finished. Resetting isRefreshing flag.")
                    _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    private fun enrichWithPrimarySystemFolders(folders: List<FolderDetails>): List<FolderDetails> {
        val primarySystemRootPaths = setOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS)
        ).mapNotNull { it?.absolutePath }.toSet()

        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

        val primarySystemSubFolderPaths = setOf(
            File(dcimDir, "Camera").absolutePath,
            File(picturesDir, "Screenshots").absolutePath
        )

        val allPrimaryPaths = primarySystemRootPaths + primarySystemSubFolderPaths

        return folders.map { folder ->
            folder.copy(isPrimarySystemFolder = folder.path in allPrimaryPaths)
        }
    }

    private fun FolderDetails.toFolderInfo() = FolderInfo(
        bucketId = this.path,
        bucketName = this.name,
        itemCount = this.itemCount,
        totalSize = this.totalSize,
        isSystemFolder = this.isSystemFolder,
        isPrimarySystemFolder = this.isPrimarySystemFolder
    )

    fun changeSortOption(sortOption: FolderSortOption) {
        _uiState.update { it.copy(currentSortOption = sortOption) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectBucket(bucketId: String) {
        _uiState.update { state ->
            state.copy(
                selectedBuckets = (state.selectedBuckets + bucketId).distinct()
            )
        }
    }

    fun selectFolderRecursively(parentFolderPath: String) {
        _uiState.update { state ->
            val allChildPaths = state.allFolderDetails
                .filter { it.path.startsWith(parentFolderPath) }
                .map { it.path }
            val newSelection = (state.selectedBuckets + allChildPaths).distinct()
            val newRoots = state.recursivelySelectedRoots + parentFolderPath
            state.copy(selectedBuckets = newSelection, recursivelySelectedRoots = newRoots)
        }
    }

    fun deselectChildren(parentFolderPath: String) {
        _uiState.update { state ->
            val childPathsToDeselect = state.allFolderDetails
                .filter { it.path.startsWith(parentFolderPath) && it.path != parentFolderPath }
                .map { it.path }
                .toSet()

            val newSelection = state.selectedBuckets.toSet() - childPathsToDeselect
            val newRoots = state.recursivelySelectedRoots - parentFolderPath

            state.copy(
                selectedBuckets = newSelection.toList(),
                recursivelySelectedRoots = newRoots
            )
        }
    }

    fun unselectBucket(bucketId: String) {
        _uiState.update { state ->
            val currentSelection = state.selectedBuckets.toMutableSet()
            val currentRoots = state.recursivelySelectedRoots.toMutableSet()

            if (bucketId in currentRoots) {
                val allChildPaths = state.allFolderDetails
                    .filter { it.path.startsWith(bucketId) }
                    .map { it.path }
                currentSelection.removeAll(allChildPaths.toSet())
                currentRoots.remove(bucketId)
            } else {
                currentSelection.remove(bucketId)
            }

            state.copy(
                selectedBuckets = currentSelection.toList(),
                recursivelySelectedRoots = currentRoots
            )
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(
                selectedBuckets = state.folderCategories.flatMap { it.folders }.map { it.bucketId }
            )
        }
    }

    fun unselectAll() {
        _uiState.update { state ->
            state.copy(
                selectedBuckets = emptyList(),
                recursivelySelectedRoots = emptySet()
            )
        }
    }

    fun saveSelectedBucketsPreference() {
        viewModelScope.launch {
            val currentlySelected = _uiState.value.selectedBuckets
            preferencesRepository.savePreviouslySelectedBuckets(currentlySelected)
        }
    }

    fun toggleFavorite(folderId: String) {
        val folderInfo = _uiState.value.allFolderDetails.find { it.path == folderId }
        if (folderInfo?.isSystemFolder == true) {
            return
        }

        viewModelScope.launch {
            val currentFavorites = _uiState.value.favoriteFolders
            if (folderId in currentFavorites) {
                preferencesRepository.removeSourceFavoriteFolder(folderId)
            } else {
                preferencesRepository.addSourceFavoriteFolder(folderId)
            }
        }
    }

    fun showRenameDialog(path: String) {
        _uiState.update { it.copy(showRenameDialogForPath = path) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(showRenameDialogForPath = null) }
    }

    fun renameFolder(oldPath: String, newName: String) {
        viewModelScope.launch {
            fileOperationsHelper.renameFolder(oldPath, newName).onSuccess { newPath ->
                mediaRepository.handleFolderRename(oldPath, newPath)
                preferencesRepository.updateFolderPath(oldPath, newPath)
                _uiState.update { it.copy(
                    toastMessage = "Folder renamed successfully",
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

    fun markFolderAsSorted(folder: FolderInfo) {
        viewModelScope.launch {
            val shouldShowDialog = preferencesRepository.showConfirmMarkAsSortedFlow.first()
            if (shouldShowDialog) {
                _uiState.update { it.copy(
                    showMarkAsSortedConfirmation = true,
                    foldersToMarkAsSorted = listOf(folder)
                ) }
            } else {
                performMarkFoldersAsSorted(setOf(folder.bucketId))
            }
        }
    }

    fun confirmMarkFolderAsSorted() {
        viewModelScope.launch {
            val folderPaths = _uiState.value.foldersToMarkAsSorted.map { it.bucketId }.toSet()
            if (folderPaths.isEmpty()) return@launch

            if (_uiState.value.dontAskAgainMarkAsSorted) {
                preferencesRepository.setShowConfirmMarkAsSorted(false)
            }
            performMarkFoldersAsSorted(folderPaths)
        }
    }

    private fun performMarkFoldersAsSorted(folderPathsToMark: Set<String>) {
        viewModelScope.launch {
            val currentState = _uiState.value
            val allFolderDetails = currentState.allFolderDetails
            val recursiveRootsInSelection = currentState.recursivelySelectedRoots.intersect(folderPathsToMark)

            val finalPathsToHide = folderPathsToMark.toMutableSet()

            if (recursiveRootsInSelection.isNotEmpty()) {
                recursiveRootsInSelection.forEach { rootPath ->
                    val childPaths = allFolderDetails
                        .filter { it.path.startsWith(rootPath) }
                        .map { it.path }
                    finalPathsToHide.addAll(childPaths)
                }
            }

            finalPathsToHide.forEach { preferencesRepository.addPermanentlySortedFolder(it) }
            mediaRepository.removeFoldersFromCache(finalPathsToHide)

            _uiState.update {
                val updatedSelection = it.selectedBuckets.filterNot { path -> path in finalPathsToHide }
                it.copy(
                    selectedBuckets = updatedSelection,
                    toastMessage = if (folderPathsToMark.size > 1) "${folderPathsToMark.size} folders hidden." else "Folder hidden.",
                    showMarkAsSortedConfirmation = false,
                    foldersToMarkAsSorted = emptyList(),
                    dontAskAgainMarkAsSorted = false
                )
            }
        }
    }


    fun onDontAskAgainMarkAsSortedChanged(isChecked: Boolean) {
        _uiState.update { it.copy(dontAskAgainMarkAsSorted = isChecked) }
    }

    fun dismissMarkAsSortedDialog() {
        _uiState.update { it.copy(
            showMarkAsSortedConfirmation = false,
            foldersToMarkAsSorted = emptyList(),
            dontAskAgainMarkAsSorted = false
        ) }
    }

    fun showMoveFolderDialog(sourcePath: String) {
        _uiState.update { it.copy(showMoveFolderDialogForPath = sourcePath) }
        viewModelScope.launch {
            folderSearchManager.prepareForSearch(
                initialPath = null,
                coroutineScope = viewModelScope,
                excludedFolders = setOf(sourcePath)
            )
        }
    }

    fun dismissMoveFolderDialog() {
        _uiState.update { it.copy(showMoveFolderDialogForPath = null) }
        folderSearchManager.reset()
    }

    fun confirmMoveFolderSelection() {
        viewModelScope.launch {
            val sourcePath = _uiState.value.showMoveFolderDialogForPath ?: return@launch
            val destinationPath = folderSearchManager.state.value.browsePath ?: return@launch

            dismissMoveFolderDialog() // Hide dialog immediately

            val result = fileOperationsHelper.moveFolderContents(sourcePath, destinationPath)
            result.onSuccess { (movedCount, failedCount) ->
                mediaRepository.handleFolderMove(sourcePath, destinationPath)
                val message = "Moved $movedCount files." + if (failedCount > 0) " $failedCount failed." else ""
                _uiState.update { it.copy(toastMessage = message) }
            }.onFailure { error ->
                _uiState.update { it.copy(toastMessage = "Error: ${error.message}") }
            }
        }
    }

    // --- CONTEXTUAL SELECTION MODE ---

    private fun canFavoriteSelection(selectedPaths: Set<String>, allFolders: List<FolderDetails>): Boolean {
        return allFolders.any { it.path in selectedPaths && !it.isSystemFolder }
    }

    fun enterContextualSelectionMode(folderPath: String) {
        _uiState.update { state ->
            val initialSelection = if (folderPath in state.selectedBuckets) {
                state.selectedBuckets.toSet()
            } else {
                setOf(folderPath)
            }
            state.copy(
                isContextualSelectionMode = true,
                contextSelectedFolderPaths = initialSelection,
                canFavoriteContextualSelection = canFavoriteSelection(initialSelection, state.allFolderDetails)
            )
        }
    }

    fun exitContextualSelectionMode() {
        _uiState.update {
            it.copy(
                isContextualSelectionMode = false,
                contextSelectedFolderPaths = emptySet(),
                canFavoriteContextualSelection = false
            )
        }
    }

    fun toggleContextualSelection(folderPath: String) {
        _uiState.update { state ->
            val currentSelection = state.contextSelectedFolderPaths
            val newSelection = if (folderPath in currentSelection) {
                currentSelection - folderPath
            } else {
                currentSelection + folderPath
            }

            if (newSelection.isEmpty()) {
                state.copy(
                    isContextualSelectionMode = false,
                    contextSelectedFolderPaths = emptySet(),
                    canFavoriteContextualSelection = false
                )
            } else {
                state.copy(
                    contextSelectedFolderPaths = newSelection,
                    canFavoriteContextualSelection = canFavoriteSelection(newSelection, state.allFolderDetails)
                )
            }
        }
    }

    fun contextualSelectAll() {
        _uiState.update { state ->
            val allVisiblePaths = state.folderCategories.flatMap { it.folders }.map { it.bucketId }.toSet()
            state.copy(
                contextSelectedFolderPaths = allVisiblePaths,
                canFavoriteContextualSelection = canFavoriteSelection(allVisiblePaths, state.allFolderDetails)
            )
        }
    }

    fun markSelectedFoldersAsSorted() {
        viewModelScope.launch {
            val selectedPaths = _uiState.value.contextSelectedFolderPaths
            val foldersToMark = _uiState.value.allFolderDetails
                .filter { it.path in selectedPaths }
                .map { it.toFolderInfo() }

            if (foldersToMark.isEmpty()) {
                exitContextualSelectionMode()
                return@launch
            }

            val shouldShowDialog = preferencesRepository.showConfirmMarkAsSortedFlow.first()
            if (shouldShowDialog) {
                _uiState.update { it.copy(
                    showMarkAsSortedConfirmation = true,
                    foldersToMarkAsSorted = foldersToMark
                ) }
            } else {
                performMarkFoldersAsSorted(selectedPaths)
            }
            exitContextualSelectionMode()
        }
    }

    fun toggleFavoriteForSelectedFolders() {
        viewModelScope.launch {
            val selectedPaths = _uiState.value.contextSelectedFolderPaths
            val currentFavorites = _uiState.value.favoriteFolders
            val allDetails = _uiState.value.allFolderDetails

            var favoritesAdded = 0
            var favoritesRemoved = 0

            selectedPaths.forEach { path ->
                val folder = allDetails.find { it.path == path }
                if (folder != null && !folder.isSystemFolder) {
                    if (path in currentFavorites) {
                        preferencesRepository.removeSourceFavoriteFolder(path)
                        favoritesRemoved++
                    } else {
                        preferencesRepository.addSourceFavoriteFolder(path)
                        favoritesAdded++
                    }
                }
            }

            val message = when {
                favoritesAdded > 0 && favoritesRemoved > 0 -> "$favoritesAdded folders favorited, $favoritesRemoved unfavorited."
                favoritesAdded > 0 -> "$favoritesAdded folders added to favorites."
                favoritesRemoved > 0 -> "$favoritesRemoved folders removed from favorites."
                else -> "No changes applied. System folders cannot be favorited."
            }
            _uiState.update { it.copy(toastMessage = message) }
            exitContextualSelectionMode()
        }
    }

    // --- END CONTEXTUAL SELECTION MODE ---


    fun toastMessageShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}