package com.cleansweep.domain.repository

import com.cleansweep.data.model.MediaItem
import com.cleansweep.domain.model.IndexingStatus
import com.cleansweep.ui.screens.session.FolderDetails
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    fun getMediaFromBuckets(bucketIds: List<String>): Flow<List<MediaItem>>
    fun getAllMediaItems(): Flow<MediaItem>
    suspend fun getMediaCount(): Int
    suspend fun getMediaFoldersWithDetails(forceRefresh: Boolean = false): List<FolderDetails>
    fun observeMediaFoldersWithDetails(): Flow<List<FolderDetails>>
    suspend fun createNewFolder(folderName: String, parentDirectory: String?): Result<String>
    suspend fun moveMediaToFolder(mediaId: String, targetFolderId: String): MediaItem?
    suspend fun deleteMedia(items: List<MediaItem>): Boolean
    suspend fun moveMedia(mediaIds: List<String>, targetFolders: List<String>): Map<String, MediaItem>
    fun getRecentFolders(limit: Int): Flow<List<Pair<String, String>>>
    suspend fun getFolderExistence(folderIds: Set<String>): Map<String, Boolean>
    fun searchFolders(query: String): Flow<List<String>>
    suspend fun getFolderNames(folderIds: Set<String>): Map<String, String>
    suspend fun getFoldersFromPaths(folderPaths: Set<String>): List<Pair<String, String>>
    suspend fun getSubdirectories(path: String): List<String>
    suspend fun isDirectory(path: String): Boolean
    fun observeAllFolders(): Flow<List<Pair<String, String>>>
    suspend fun cleanupGhostFolders()
    fun getFoldersSortedByRecentMedia(): Flow<List<Pair<String, String>>>
    suspend fun getFoldersWithProcessedMedia(): List<Pair<String, String>>
    suspend fun getAllFolderPaths(): List<String>
    fun observeFoldersForTargetDialog(): Flow<List<Pair<String, String>>>
    suspend fun getCachedFolderSnapshot(): List<Pair<String, String>>
    suspend fun checkForChangesAndInvalidate(): Boolean
    fun invalidateFolderCache()
    suspend fun getUnindexedMediaPaths(): List<String>
    suspend fun scanFolders(folderPaths: List<String>)
    suspend fun scanPathsAndWait(paths: List<String>): Boolean
    suspend fun getMediaItemsFromPaths(paths: List<String>): List<MediaItem>
    suspend fun removeFoldersFromCache(paths: Set<String>)
    suspend fun getIndexingStatus(): IndexingStatus
    suspend fun triggerFullMediaStoreScan(): Boolean
    suspend fun handleFolderRename(oldPath: String, newPath: String)
    suspend fun handleFolderMove(sourcePath: String, destinationPath: String)
    suspend fun getAllMediaFilePaths(): Set<String>
    suspend fun getMediaStoreKnownPaths(): Set<String>
}
