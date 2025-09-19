/*
 * CleanSweep
 * Copyright (c) 2025 LoopOtto
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.cleansweep.ui.components

import androidx.compose.runtime.Stable
import com.cleansweep.domain.repository.MediaRepository
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@Stable
data class FolderSearchState(
    val searchQuery: String = "",
    val browsePath: String? = null,
    val displayedResults: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isPreFilteredMode: Boolean = false
)

@ViewModelScoped
class FolderSearchManager @Inject constructor(
    private val mediaRepository: MediaRepository
) {
    private val _state = MutableStateFlow(FolderSearchState())
    val state: StateFlow<FolderSearchState> = _state.asStateFlow()

    private var folderCollectionJob: Job? = null
    private var allFoldersFromRepo: List<Pair<String, String>> = emptyList()
    private var preFilteredList: List<Pair<String, String>> = emptyList()
    private val calculationContext = Dispatchers.Default

    private fun calculateResults(
        sourceList: List<Pair<String, String>>,
        query: String,
        browsePath: String?,
        isPreFiltered: Boolean
    ): List<String> {
        val results = if (query.isNotBlank()) {
            sourceList.filter { (path, name) ->
                name.contains(query, ignoreCase = true) || path.contains(query, ignoreCase = true)
            }
        } else if (!browsePath.isNullOrBlank()) {
            sourceList.filter { (path, _) ->
                File(path).parent.equals(browsePath, ignoreCase = true)
            }
        } else {
            if (isPreFiltered) {
                sourceList
            } else {
                val allPaths = sourceList.map { it.first }.toSet()
                sourceList.filter { (path, _) ->
                    val parent = File(path).parent
                    parent == null || !allPaths.contains(parent)
                }
            }
        }
        return results.map { it.first }.sorted()
    }

    suspend fun prepareForSearch(
        initialPath: String?,
        coroutineScope: CoroutineScope,
        excludedFolders: Set<String> = emptySet(),
        includePermanentlySorted: Boolean = false
    ) {
        folderCollectionJob?.cancel()
        this.preFilteredList = emptyList()

        // Fetch one or both lists based on the context.
        val cachedFolders = mediaRepository.getCachedFolderSnapshot()
        val permanentlySortedFolders = if (includePermanentlySorted) {
            mediaRepository.getFoldersWithProcessedMedia()
        } else {
            emptyList()
        }
        // Combine and deduplicate.
        val combinedFolders = (cachedFolders + permanentlySortedFolders).distinctBy { it.first }

        val availableCachedFolders = combinedFolders.filterNot { it.first in excludedFolders }
        allFoldersFromRepo = availableCachedFolders

        // Calculate initial results from the cache.
        val initialResults = calculateResults(
            sourceList = availableCachedFolders,
            query = "", // Initial search query is empty
            browsePath = initialPath,
            isPreFiltered = false
        )

        _state.value = FolderSearchState(
            browsePath = initialPath,
            isPreFilteredMode = false,
            isLoading = false,
            displayedResults = initialResults
        )

        // Then, observe for live updates in the background.
        folderCollectionJob = coroutineScope.launch {
            // This still observes the main flow, which is correct. The initial snapshot
            // gives us the full list, and subsequent updates from the main flow
            // (e.g., a new folder is created) will still be caught here.
            mediaRepository.observeAllFolders().collect { folders ->
                val availableFolders = folders.filterNot { it.first in excludedFolders }
                allFoldersFromRepo = availableFolders
                _state.update { currentState ->
                    currentState.copy(
                        displayedResults = calculateResults(
                            sourceList = availableFolders,
                            query = currentState.searchQuery,
                            browsePath = currentState.browsePath,
                            isPreFiltered = false
                        )
                    )
                }
            }
        }
    }

    fun prepareWithPreFilteredList(
        folders: List<Pair<String, String>>,
        initialPath: String? = null,
        excludedFolders: Set<String> = emptySet()
    ) {
        folderCollectionJob?.cancel()
        val availableFolders = folders.filterNot { it.first in excludedFolders }
        this.preFilteredList = availableFolders
        this.allFoldersFromRepo = emptyList()

        val initialResults = calculateResults(
            sourceList = availableFolders,
            query = "",
            browsePath = initialPath,
            isPreFiltered = true
        )

        _state.value = FolderSearchState(
            browsePath = initialPath,
            isPreFilteredMode = true,
            isLoading = false,
            displayedResults = initialResults
        )
    }

    fun updateSearchQuery(query: String) {
        val currentState = _state.value
        val sourceList = if (currentState.isPreFilteredMode) preFilteredList else allFoldersFromRepo
        _state.value = currentState.copy(
            searchQuery = query,
            displayedResults = calculateResults(sourceList, query, currentState.browsePath, currentState.isPreFilteredMode)
        )
    }

    suspend fun selectPath(path: String) {
        withContext(calculationContext) {
            val currentState = _state.value
            val sourceList = if (currentState.isPreFilteredMode) preFilteredList else allFoldersFromRepo
            _state.update {
                it.copy(
                    browsePath = path,
                    searchQuery = "",
                    displayedResults = calculateResults(sourceList, "", path, it.isPreFilteredMode)
                )
            }
        }
    }

    suspend fun selectSingleResultOrSelf() {
        val currentState = _state.value
        if (currentState.searchQuery.isNotBlank() && currentState.displayedResults.size == 1) {
            selectPath(currentState.displayedResults.first())
        }
    }

    fun reset() {
        folderCollectionJob?.cancel()
        this.preFilteredList = emptyList()
        this.allFoldersFromRepo = emptyList()
        _state.value = FolderSearchState()
    }
}
