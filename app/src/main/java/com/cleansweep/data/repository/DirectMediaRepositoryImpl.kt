package com.cleansweep.data.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.cleansweep.data.db.dao.FolderDetailsDao
import com.cleansweep.data.db.entity.FolderDetailsCache
import com.cleansweep.data.model.MediaItem
import com.cleansweep.di.AppModule
import com.cleansweep.domain.bus.AppLifecycleEventBus
import com.cleansweep.domain.bus.FolderUpdateEvent
import com.cleansweep.domain.bus.FolderUpdateEventBus
import com.cleansweep.domain.model.IndexingStatus
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.ui.screens.session.FolderDetails
import com.cleansweep.util.FileManager
import com.cleansweep.util.FileOperationsHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class DirectMediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
    private val folderDetailsDao: FolderDetailsDao,
    private val preferencesRepository: PreferencesRepository,
    @AppModule.ApplicationScope private val externalScope: CoroutineScope,
    private val fileOperationsHelper: FileOperationsHelper,
    private val folderUpdateEventBus: FolderUpdateEventBus,
    private val appLifecycleEventBus: AppLifecycleEventBus
) : MediaRepository {

    private val LOG_TAG = "DirectMediaRepo"
    private val DIMENSION_LOG_TAG = "ImageDimensionDebug"

    private var folderDetailsCache: List<FolderDetails>? = null
    private var lastFileDiscoveryCache: List<File>? = null

    @Volatile
    private var lastKnownFolderState: Set<String>? = null

    // In-session cache for the slow empty folder scan
    @Volatile
    private var emptyFolderListCache: List<Pair<String, String>>? = null

    init {
        // Listen for folder updates and apply them directly to the DB cache.
        listenForFolderUpdates()
        // Listen for app lifecycle events to invalidate session caches
        listenForAppLifecycle()
    }

    private fun listenForAppLifecycle() {
        externalScope.launch {
            appLifecycleEventBus.appResumeEvent.collect {
                Log.d("CacheDebug", "App resumed, invalidating empty folder session cache.")
                emptyFolderListCache = null
            }
        }
    }

    private fun listenForFolderUpdates() {
        externalScope.launch(Dispatchers.IO) {
            folderUpdateEventBus.events.collect { event ->
                Log.d(LOG_TAG, "Repository received folder update event: $event")
                when (event) {
                    is FolderUpdateEvent.FolderBatchUpdate -> {
                        val pathsToDelete = mutableListOf<String>()
                        val updatesToUpsert = mutableListOf<FolderDetailsCache>()

                        event.updates.forEach { (path, delta) ->
                            val folder = folderDetailsDao.getFolderByPath(path)
                            if (folder != null) {
                                val newItemCount = folder.itemCount + delta.itemCountChange
                                if (newItemCount <= 0) {
                                    pathsToDelete.add(path)
                                } else {
                                    updatesToUpsert.add(folder.copy(
                                        itemCount = newItemCount,
                                        totalSize = (folder.totalSize + delta.sizeChange).coerceAtLeast(0)
                                    ))
                                }
                            }
                        }

                        if (pathsToDelete.isNotEmpty()) {
                            folderDetailsDao.deleteByPath(pathsToDelete)
                        }
                        if (updatesToUpsert.isNotEmpty()) {
                            folderDetailsDao.upsertAll(updatesToUpsert)
                        }
                    }
                    is FolderUpdateEvent.FolderAdded -> {
                        if (folderDetailsDao.getFolderByPath(event.path) == null) {
                            val newCacheEntry = FolderDetailsCache(
                                path = event.path,
                                name = event.name,
                                itemCount = 0,
                                totalSize = 0,
                                isSystemFolder = false // Default
                            )
                            folderDetailsDao.upsert(newCacheEntry)
                        }
                        // Surgically update the empty folder cache if it exists
                        emptyFolderListCache?.let { currentCache ->
                            if (currentCache.none { it.first == event.path }) {
                                emptyFolderListCache = (currentCache + (event.path to event.name))
                                    .sortedBy { it.second.lowercase(Locale.ROOT) }
                            }
                        }
                    }
                    is FolderUpdateEvent.FullRefreshRequired -> {
                        // The event signals that a significant change happened elsewhere.
                        // We invalidate our caches. The next observer of our flows
                        // will automatically trigger a full rescan.
                        invalidateFolderCache()
                    }
                }
            }
        }
    }

    // Helper class to cache MediaStore query results
    private data class MediaStoreCache(
        val id: Long,
        val displayName: String?,
        val mimeType: String?,
        val dateAdded: Long,
        val dateModified: Long,
        val size: Long,
        val bucketId: String?,
        val bucketName: String?,
        val isVideo: Boolean,
        val width: Int,
        val height: Int,
        val orientation: Int
    )

    override suspend fun checkForChangesAndInvalidate(): Boolean = withContext(Dispatchers.IO) {
        Log.d("CacheDebug", "Checking for external changes...")
        val cachedFolders = folderDetailsDao.getAll().first()
        if (cachedFolders.isEmpty()) {
            Log.d("CacheDebug", "Cache is empty, no external check needed.")
            // No need to invalidate, an empty cache will trigger a scan on its own.
            return@withContext false
        }

        var changesFound = false
        val cachedFolderPaths = cachedFolders.map { it.path }.toSet()

        // 1. More reliable check for deleted folders by checking the file system directly.
        val physicallyDeletedPaths = cachedFolders.filter { !File(it.path).exists() }.map { it.path }
        if (physicallyDeletedPaths.isNotEmpty()) {
            Log.d("CacheDebug", "Found ${physicallyDeletedPaths.size} physically deleted folders. Removing from cache.")
            folderDetailsDao.deleteByPath(physicallyDeletedPaths)
            changesFound = true
        }

        // 2. Intelligent check for new folders, respecting app's filtering logic.
        val permanentlySortedFolders = preferencesRepository.permanentlySortedFoldersFlow.first()
        val mediaStorePaths = getMediaStoreFolderPaths().filter { path ->
            isSafeDestination(path) && path !in permanentlySortedFolders && !File(path).name.startsWith(".")
        }.toSet()

        val newFolders = mediaStorePaths - cachedFolderPaths
        if (newFolders.isNotEmpty()) {
            Log.d("CacheDebug", "Found ${newFolders.size} new external folders. Surgically adding to cache.")
            newFolders.forEach { rescanSingleFolderAndUpdateCache(it) }
            changesFound = true
        }

        Log.d("CacheDebug", "External change check complete. Changes found: $changesFound")
        return@withContext changesFound
    }

    private suspend fun getMediaStoreFolderPaths(): Set<String> = withContext(Dispatchers.IO) {
        val folderPaths = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val queryUri = MediaStore.Files.getContentUri("external")

        try {
            context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val path = cursor.getString(dataColumn)
                    // Ensure path exists and has a parent before adding
                    if (path != null) {
                        File(path).parentFile?.let { parentFile ->
                            if (parentFile.name != "To Edit") {
                                folderPaths.add(parentFile.absolutePath)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to get MediaStore folder paths", e)
        }
        return@withContext folderPaths
    }

    private fun invalidateCaches() {
        folderDetailsCache = null
        lastFileDiscoveryCache = null // Invalidate file discovery cache
        externalScope.launch {
            folderDetailsDao.clear()
        }
    }

    override fun invalidateFolderCache() {
        Log.d("CacheDebug", "Forced invalidation. Clearing all folder caches (Memory, DB, and state).")
        lastKnownFolderState = null
        emptyFolderListCache = null
        invalidateCaches()
    }

    override suspend fun getCachedFolderSnapshot(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        return@withContext folderDetailsDao.getAll().first().map { it.path to it.name }
    }

    private suspend fun getCurrentMediaStoreFolders(): Set<String> = withContext(Dispatchers.IO) {
        return@withContext scanForAllMediaFoldersRecursively()
    }

    private val supportedImageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")
    private val supportedVideoExtensions = setOf("mp4", "webm", "mkv", "3gp", "mov", "avi", "mpg", "mpeg", "wmv", "flv")

    override suspend fun cleanupGhostFolders() { /* No-op */ }

    override fun getMediaFromBuckets(bucketIds: List<String>): Flow<List<MediaItem>> = flow {
        if (bucketIds.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        Log.d(LOG_TAG, "Starting batched media fetch for buckets: $bucketIds")

        // Step 1: Pre-fetch all available MediaStore data for the target buckets into a map for fast lookups.
        val mediaStoreDataMap = getMediaStoreDataForBuckets(bucketIds)
        Log.d(LOG_TAG, "Pre-fetched ${mediaStoreDataMap.size} items from MediaStore.")

        // Step 2: Get a lightweight list of all File objects from the file system, NON-RECURSIVELY.
        val fileSystemFiles = withContext(Dispatchers.IO) {
            bucketIds.flatMap { bucketPath ->
                try {
                    File(bucketPath)
                        .listFiles { file -> file.isFile && isMediaFile(file) }
                        ?.toList() ?: emptyList()
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error reading files from bucket: $bucketPath", e)
                    emptyList<File>()
                }
            }.toSet()
        }
        Log.d(LOG_TAG, "Direct non-recursive file scan found ${fileSystemFiles.size} total files.")


        // Step 3: Combine and sort the list of File objects to establish the processing order.
        val sortedFileIndex = withContext(Dispatchers.Default) {
            val allFilePaths = fileSystemFiles.map { it.absolutePath } + mediaStoreDataMap.keys
            allFilePaths.distinct()
                .mapNotNull { path -> try { File(path) } catch (e: Exception) { null } }
                .filter { it.exists() }
                .sortedByDescending { it.lastModified() }
        }
        Log.d(LOG_TAG, "Discovered and sorted ${sortedFileIndex.size} unique files.")

        // Step 4: Process the sorted list, stream results, and track un-indexed files.
        val initialBatchSize = 5
        val subsequentBatchSize = 20
        val batch = mutableListOf<MediaItem>()
        val unindexedPathsToScan = mutableListOf<String>()
        var isFirstBatch = true

        for (file in sortedFileIndex) {
            currentCoroutineContext().ensureActive()

            val mediaItem = mediaStoreDataMap[file.absolutePath]?.let { cache ->
                createMediaItemFromMediaStore(file, cache)
            } ?: run {
                unindexedPathsToScan.add(file.absolutePath) // This file was missed by MediaStore, queue it for indexing.
                createMediaItemFromFile(file)
            }

            mediaItem?.let { batch.add(it) }

            val currentBatchSize = if (isFirstBatch) initialBatchSize else subsequentBatchSize
            if (batch.size >= currentBatchSize) {
                emit(batch.toList())
                batch.clear()
                if (isFirstBatch) isFirstBatch = false
            }
        }

        if (batch.isNotEmpty()) {
            emit(batch)
        }
        Log.d(LOG_TAG, "Finished streaming all items.")

        // Step 5: Proactively index any files that were missed by MediaStore for the next session.
        if (unindexedPathsToScan.isNotEmpty()) {
            Log.d(LOG_TAG, "Found ${unindexedPathsToScan.size} un-indexed files. Triggering background scan.")
            externalScope.launch {
                scanFolders(unindexedPathsToScan)
            }
        }
    }.flowOn(Dispatchers.Default)

    override fun getAllMediaItems(): Flow<MediaItem> = flow {
        Log.d(LOG_TAG, "Starting true streaming pipeline for all media items.")

        suspend fun processDirectory(directory: File, mediaStoreMap: Map<String, MediaStoreCache>) {
            currentCoroutineContext().ensureActive()
            if (directory.name == "To Edit" || !directory.exists() || !directory.isDirectory || !directory.canRead() || directory.name.startsWith('.') || !isSafeDestination(directory.absolutePath)) {
                return
            }

            try {
                val files = directory.listFiles()
                if (files.isNullOrEmpty()) return

                val subdirectories = mutableListOf<File>()
                for (file in files) {
                    if (file.isDirectory) {
                        subdirectories.add(file)
                    } else if (isMediaFile(file)) {
                        val mediaItem = mediaStoreMap[file.absolutePath]?.let { cache ->
                            createMediaItemFromMediaStore(file, cache)
                        } ?: createMediaItemFromFile(file)

                        mediaItem?.let { emit(it) }
                    }
                }

                // Recurse into subdirectories after processing files in the current one
                for (subDir in subdirectories) {
                    processDirectory(subDir, mediaStoreMap)
                }

            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to access directory: ${directory.path}", e)
            }
        }

        val mediaStoreDataMap = getMediaStoreDataForBuckets(null)
        Log.d(LOG_TAG, "Pre-fetched ${mediaStoreDataMap.size} total items from MediaStore.")
        Environment.getExternalStorageDirectory()?.let {
            processDirectory(it, mediaStoreDataMap)
        }

        Log.d(LOG_TAG, "Finished streaming all media items from pipelined scan.")
    }.flowOn(Dispatchers.IO)


    private suspend fun getMediaStoreDataForBuckets(bucketPaths: List<String>?): Map<String, MediaStoreCache> = withContext(Dispatchers.IO) {
        val cacheMap = mutableMapOf<String, MediaStoreCache>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME, MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.WIDTH, MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION
        )

        val selection: String?
        val selectionArgs: Array<String>?

        if (bucketPaths != null) {
            // CORRECT WAY: Use BUCKET_ID for non-recursive folder selection.
            // BUCKET_ID is a hash of the lowercase folder path.
            val calculatedBucketIds = bucketPaths.map { it.lowercase(Locale.ROOT).hashCode().toString() }
            if (calculatedBucketIds.isEmpty()) {
                return@withContext emptyMap()
            }

            // Create a WHERE clause with placeholders: "BUCKET_ID IN (?,?,?)"
            val placeholders = calculatedBucketIds.joinToString(separator = ",") { "?" }
            selection = "${MediaStore.Files.FileColumns.BUCKET_ID} IN ($placeholders)" +
                    " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"

            selectionArgs = (calculatedBucketIds +
                    arrayOf(
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                    )).toTypedArray()
        } else {
            // Original behavior for full scans (no specific buckets).
            selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )
        }

        val queryUri = MediaStore.Files.getContentUri("external")

        try {
            context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                val mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataColumn)
                    if (!path.isNullOrBlank()) {
                        cacheMap[path] = MediaStoreCache(
                            id = cursor.getLong(idColumn),
                            displayName = cursor.getString(nameColumn),
                            mimeType = cursor.getString(mimeColumn),
                            dateAdded = cursor.getLong(dateAddedColumn),
                            dateModified = cursor.getLong(dateModifiedColumn),
                            size = cursor.getLong(sizeColumn),
                            bucketId = cursor.getString(bucketIdColumn),
                            bucketName = cursor.getString(bucketNameColumn),
                            isVideo = cursor.getInt(mediaTypeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                            width = cursor.getInt(widthColumn),
                            height = cursor.getInt(heightColumn),
                            orientation = cursor.getInt(orientationColumn)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to pre-fetch MediaStore data", e)
        }
        return@withContext cacheMap
    }

    private fun createMediaItemFromMediaStore(file: File, cache: MediaStoreCache): MediaItem {
        val queryUri = MediaStore.Files.getContentUri("external")

        val needsSwap = cache.orientation == 90 || cache.orientation == 270
        val finalWidth = if (needsSwap) cache.height else cache.width
        val finalHeight = if (needsSwap) cache.width else cache.height

        return MediaItem(
            id = file.absolutePath,
            uri = ContentUris.withAppendedId(queryUri, cache.id),
            displayName = cache.displayName ?: file.name,
            mimeType = cache.mimeType ?: "application/octet-stream",
            dateAdded = cache.dateAdded * 1000, // MediaStore dates are in seconds
            dateModified = cache.dateModified * 1000,
            size = cache.size,
            bucketId = cache.bucketId ?: file.parent ?: "",
            bucketName = cache.bucketName ?: file.parentFile?.name ?: "",
            isVideo = cache.isVideo,
            width = finalWidth,
            height = finalHeight
        )
    }

    override suspend fun getAllFolderPaths(): List<String> = withContext(Dispatchers.IO) {
        val paths = observeAllFolders().first().map { it.first }
        Log.d("CacheDebug", "Returning snapshot of folder paths: ${paths.size} items.")
        return@withContext paths
    }

    override fun observeFoldersForTargetDialog(): Flow<List<Pair<String, String>>> {
        val mediaFoldersFlow = folderDetailsDao.getAll().map { cacheList ->
            cacheList.map { it.path to it.name }
        }

        val emptyFoldersFlow = flow {
            // Fast path: use in-session cache if available
            val cachedEmptyFolders = emptyFolderListCache
            if (cachedEmptyFolders != null) {
                Log.d("CacheDebug", "Emitting ${cachedEmptyFolders.size} empty folders from session cache.")
                emit(cachedEmptyFolders)
                return@flow
            }

            // Slow path: scan file system
            Log.d("CacheDebug", "Session cache miss for empty folders. Scanning file system.")
            val emptyFolders = scanForEmptyFoldersRecursively()
            emptyFolderListCache = emptyFolders
            emit(emptyFolders)
        }.flowOn(Dispatchers.IO) // Ensure scan runs off the main thread

        return combine(mediaFoldersFlow, emptyFoldersFlow) { mediaFolders, emptyFolders ->
            (mediaFolders + emptyFolders)
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase(Locale.ROOT) }
        }.flowOn(Dispatchers.Default) // Combine and sort on a computation thread
    }

    private suspend fun scanForEmptyFoldersRecursively(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val emptyFolders = mutableListOf<Pair<String, String>>()
        val queue: Queue<File> = ArrayDeque()

        Environment.getExternalStorageDirectory()?.let { queue.add(it) }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = queue.poll() ?: continue

            if (directory.name == "To Edit" || !directory.exists() || !directory.canRead() || directory.name.startsWith(".") || !isSafeDestination(directory.absolutePath)) {
                continue
            }

            try {
                val files = directory.listFiles()
                if (files.isNullOrEmpty()) {
                    emptyFolders.add(directory.absolutePath to directory.name)
                } else {
                    files.filter { it.isDirectory }.forEach { queue.add(it) }
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "scanForEmptyFoldersRecursively: Failed to access directory: ${directory.path}", e)
            }
        }
        Log.d("CacheDebug", "Found ${emptyFolders.size} empty folders from direct scan.")
        return@withContext emptyFolders
    }

    private suspend fun createMediaItemFromFile(file: File): MediaItem? = withContext(Dispatchers.IO) {
        try {
            val extension = file.extension.lowercase(Locale.ROOT)
            val isVideo = supportedVideoExtensions.contains(extension)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
            val uri = file.toUri()

            var width = 0
            var height = 0

            if (isVideo) {
                try {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(file.absolutePath)
                        val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

                        if (rotation == 90 || rotation == 270) {
                            width = videoHeight
                            height = videoWidth
                        } else {
                            width = videoWidth
                            height = videoHeight
                        }
                    }
                } catch (e: Exception) {
                    Log.e(DIMENSION_LOG_TAG, "Failed to get dimensions for video: ${file.path}", e)
                }
            } else { // Is image
                try {
                    val request = ImageRequest.Builder(context).data(file).size(Size.ORIGINAL).build()
                    imageLoader.execute(request).drawable?.let {
                        width = it.intrinsicWidth
                        height = it.intrinsicHeight
                    }
                } catch (e: Exception) {
                    Log.e(DIMENSION_LOG_TAG, "Failed to get dimensions for image: ${file.path}", e)
                }
            }

            MediaItem(
                id = file.absolutePath,
                uri = uri,
                displayName = file.name,
                mimeType = mimeType,
                dateAdded = file.lastModified(),
                dateModified = file.lastModified(),
                size = file.length(),
                bucketId = file.parent ?: "",
                bucketName = file.parentFile?.name ?: "",
                isVideo = isVideo,
                width = width,
                height = height
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to create MediaItem for file: ${file.path}", e)
            null
        }
    }

    override suspend fun createNewFolder(
        folderName: String,
        parentDirectory: String?
    ): Result<String> {
        return try {
            val baseDir = if (!parentDirectory.isNullOrBlank()) {
                File(parentDirectory)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            }

            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            val newFolder = File(baseDir, folderName)
            if (newFolder.exists()) {
                Result.success(newFolder.absolutePath)
            } else {
                if (newFolder.mkdir()) {
                    fileOperationsHelper.scanPaths(listOf(newFolder.absolutePath))
                    Result.success(newFolder.absolutePath)
                } else {
                    Result.failure(Exception("Failed to create directory at ${newFolder.absolutePath}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isMediaFile(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.ROOT)
        return supportedImageExtensions.contains(extension) || supportedVideoExtensions.contains(extension)
    }

    override suspend fun moveMediaToFolder(mediaId: String, targetFolderId: String): MediaItem? {
        return withContext(Dispatchers.IO) {
            try {
                val sourceFile = findFileById(mediaId) ?: return@withContext null
                val newFile = FileManager.moveFile(sourceFile.absolutePath, targetFolderId)
                    ?: return@withContext null

                if (newFile.exists()) {
                    fileOperationsHelper.scanPaths(listOf(sourceFile.absolutePath, newFile.absolutePath))
                    createMediaItemFromFile(newFile)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun findFileById(mediaId: String): File? {
        return try {
            val file = File(mediaId)
            if (file.exists() && file.isFile) {
                file
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun deleteMedia(items: List<MediaItem>): Boolean = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext true

        val (indexedItems, unindexedItems) = items.partition { it.uri.scheme == "content" }

        var indexedSuccess = true
        if (indexedItems.isNotEmpty()) {
            Log.d(LOG_TAG, "Deleting ${indexedItems.size} indexed media items using ContentResolver.")
            try {
                // All media items from MediaStore share this base URI
                val queryUri = MediaStore.Files.getContentUri("external")
                val ids = indexedItems.map { ContentUris.parseId(it.uri) }.toTypedArray()
                val placeholders = ids.joinToString { "?" }
                val selection = "${MediaStore.Files.FileColumns._ID} IN ($placeholders)"
                val selectionArgs = ids.map { it.toString() }.toTypedArray()

                val rowsDeleted = context.contentResolver.delete(queryUri, selection, selectionArgs)
                Log.d(LOG_TAG, "ContentResolver deleted $rowsDeleted rows.")
                if (rowsDeleted != indexedItems.size) {
                    Log.w(LOG_TAG, "MediaStore delete mismatch. Expected: ${indexedItems.size}, Actual: $rowsDeleted.")
                    indexedSuccess = false
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error deleting indexed media.", e)
                indexedSuccess = false
            }
        }

        var unindexedSuccess = true
        if (unindexedItems.isNotEmpty()) {
            Log.d(LOG_TAG, "Deleting ${unindexedItems.size} un-indexed media items using direct File API.")
            var successCount = 0
            val deletedPaths = mutableListOf<String>()
            unindexedItems.forEach { item ->
                // For unindexed items, 'id' is the absolute path
                if (FileManager.deleteFile(item.id)) {
                    deletedPaths.add(item.id)
                    successCount++
                } else {
                    Log.w(LOG_TAG, "Failed to delete un-indexed file: ${item.id}")
                }
            }
            if (deletedPaths.isNotEmpty()) {
                fileOperationsHelper.scanPaths(deletedPaths)
            }
            if (successCount != unindexedItems.size) {
                unindexedSuccess = false
            }
        }

        return@withContext indexedSuccess && unindexedSuccess
    }


    override suspend fun moveMedia(
        mediaIds: List<String>,
        targetFolders: List<String>
    ): Map<String, MediaItem> {
        return withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, MediaItem>()
            val pathsToScan = mutableListOf<String>()

            mediaIds.forEachIndexed { index, mediaId ->
                if (index < targetFolders.size) {
                    val mediaItem = moveMediaToFolder(mediaId, targetFolders[index])
                    if (mediaItem != null) {
                        result[mediaId] = mediaItem
                        pathsToScan.add(mediaId) // Original path
                        pathsToScan.add(mediaItem.id) // New path
                    }
                }
            }
            if (result.isNotEmpty()) {
                fileOperationsHelper.scanPaths(pathsToScan)
            }
            result
        }
    }

    override fun getRecentFolders(limit: Int): Flow<List<Pair<String, String>>> = flow {
        val recentFolders = mutableMapOf<String, Long>()
        val queue: Queue<File> = ArrayDeque()

        val root = Environment.getExternalStorageDirectory()
        if (root != null && root.exists()) {
            queue.add(root)
        }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = queue.poll()
            if (directory == null || !directory.canRead() || directory.name.startsWith(".") || !isSafeDestination(
                    directory.absolutePath
                )
            ) continue

            getMostRecentFileInDirectory(directory)?.let {
                recentFolders[directory.absolutePath] = it.lastModified()
            }

            try {
                directory.listFiles { file -> file.isDirectory }?.forEach { subDir ->
                    queue.add(subDir)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        val sortedFolders = recentFolders.toList()
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first to File(it.first).name }

        emit(sortedFolders)
    }.flowOn(Dispatchers.IO)

    private fun getMostRecentFileInDirectory(directory: File): File? {
        return try {
            directory.listFiles()
                ?.filter { it.isFile && isMediaFile(it) }
                ?.maxByOrNull { it.lastModified() }
        } catch (e: SecurityException) {
            null
        }
    }

    override fun getFoldersSortedByRecentMedia(): Flow<List<Pair<String, String>>> {
        return getRecentFolders(50)
    }

    override suspend fun getFolderExistence(folderIds: Set<String>): Map<String, Boolean> {
        return withContext(Dispatchers.IO) {
            folderIds.associateWith { folderId ->
                try {
                    File(folderId).exists()
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    override fun searchFolders(query: String): Flow<List<String>> {
        return observeAllFolders().map { allFolders ->
            if (query.isBlank()) {
                emptyList()
            } else {
                allFolders
                    .filter { (path, name) ->
                        name.contains(query, ignoreCase = true) || path.contains(query, ignoreCase = true)
                    }
                    .map { it.first }
            }
        }
    }

    override suspend fun getFolderNames(folderIds: Set<String>): Map<String, String> {
        return withContext(Dispatchers.IO) {
            folderIds.associateWith { path ->
                try {
                    File(path).name
                } catch (e: Exception) {
                    path
                }
            }
        }
    }

    private fun isSafeDestination(folderPath: String): Boolean {
        val externalStoragePath = Environment.getExternalStorageDirectory().absolutePath
        val androidDataPath = "$externalStoragePath/Android"
        return !folderPath.startsWith(androidDataPath, ignoreCase = true)
    }

    override suspend fun getFoldersFromPaths(folderPaths: Set<String>): List<Pair<String, String>> {
        return withContext(Dispatchers.IO) {
            folderPaths.mapNotNull { path ->
                try {
                    val file = File(path)
                    if (file.exists() && file.isDirectory) {
                        path to file.name
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun isConventionalSystemChildFolder(folder: File, standardPaths: Set<String>): Boolean {
        val conventionalNames = setOf("camera", "screenshots", "restored")
        val parentPath = folder.parent ?: return false
        return parentPath in standardPaths && folder.name.lowercase(Locale.ROOT) in conventionalNames
    }

    override suspend fun getMediaFoldersWithDetails(forceRefresh: Boolean): List<FolderDetails> =
        withContext(Dispatchers.IO) {
            if (folderDetailsCache != null && !forceRefresh && lastFileDiscoveryCache != null) {
                Log.d("CacheDebug", "L1 Cache Hit: Returning in-memory folder details.")
                return@withContext folderDetailsCache!!
            }

            if (forceRefresh) {
                Log.d("CacheDebug", "Force refresh requested. Clearing L2 DB cache.")
                folderDetailsDao.clear()
            } else {
                val dbCache = folderDetailsDao.getAll().first()
                if (dbCache.isNotEmpty()) {
                    Log.d("CacheDebug", "L2 Cache Hit: Returning folder details from database.")
                    val details = dbCache.map { it.toFolderDetails() }
                    folderDetailsCache = details
                    return@withContext details
                }
            }

            Log.d("CacheDebug", "Cache Miss: Scanning file system for folder details.")
            val (finalDetailsList, discoveredFiles) = scanFileSystemForFolderDetails()
            Log.d("CacheDebug", "Scan complete. Populating discovery cache with ${discoveredFiles.size} files.")

            lastFileDiscoveryCache = discoveredFiles
            folderDetailsCache = finalDetailsList
            folderDetailsDao.upsertAll(finalDetailsList.map { it.toFolderDetailsCache() })

            return@withContext finalDetailsList
        }

    override fun observeMediaFoldersWithDetails(): Flow<List<FolderDetails>> =
        folderDetailsDao.getAll()
            .transformLatest { cachedList ->
                // This atomic is a guard to ensure the expensive file scan only ever runs once
                // per app session, even if multiple collectors subscribe.
                val isScanNeeded = AtomicBoolean(cachedList.isEmpty())

                if (isScanNeeded.get()) {
                    Log.d("CacheDebug", "transformLatest: DB is empty, triggering scan.")
                    val (details, _) = scanFileSystemForFolderDetails()

                    if (details.isEmpty()) {
                        // Scan found nothing. This is the definitive empty state for a fresh device.
                        // Emit it so the UI can stop loading.
                        Log.d("CacheDebug", "transformLatest: Scan found no folders. Emitting definitive empty list.")
                        emit(emptyList())
                    } else {
                        // Scan found folders. Upsert them into the database.
                        // The `transformLatest` operator will see the new DB emission, cancel this
                        // current block, and re-run with the `cachedList` populated, hitting the `else`
                        // block below. We do not emit here to prevent duplicate data.
                        Log.d("CacheDebug", "transformLatest: Scan found ${details.size} folders. Upserting to DB.")
                        folderDetailsDao.upsertAll(details.map { it.toFolderDetailsCache() })
                    }
                } else {
                    // The DB has data. Transform it and emit. This is the normal path for updates.
                    Log.d("CacheDebug", "transformLatest: Emitting ${cachedList.size} folders from DB.")
                    val transformedList = cachedList.map { it.toFolderDetails() }.filter { it.itemCount > 0 }
                    emit(transformedList)
                }
            }
            .flowOn(Dispatchers.IO)

    private suspend fun scanFileSystemForFolderDetails(): Pair<List<FolderDetails>, List<File>> = withContext(Dispatchers.IO) {
        val processedPaths = preferencesRepository.processedMediaPathsFlow.first()
        val permanentlySortedFolders = preferencesRepository.permanentlySortedFoldersFlow.first()
        val allMediaFolders = scanForAllMediaFoldersRecursively()

        val folderDetailsList = mutableListOf<FolderDetails>()
        val allDiscoveredFiles = mutableListOf<File>()

        for (folderPath in allMediaFolders) {
            currentCoroutineContext().ensureActive()
            if (folderPath in permanentlySortedFolders) continue

            val folderFile = File(folderPath)
            if (!folderFile.exists() || !folderFile.isDirectory) continue

            var itemCount = 0
            var totalSize = 0L
            val filesInFolder = mutableListOf<File>()

            try {
                folderFile.listFiles()?.forEach { file ->
                    if (file.isFile && isMediaFile(file)) {
                        filesInFolder.add(file)
                        if (file.absolutePath !in processedPaths) {
                            itemCount++
                            totalSize += file.length()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Could not list files in $folderPath", e)
                continue
            }

            if (filesInFolder.isNotEmpty()) {
                allDiscoveredFiles.addAll(filesInFolder)
                if (itemCount > 0) {
                    folderDetailsList.add(
                        FolderDetails(
                            path = folderPath,
                            name = folderFile.name,
                            itemCount = itemCount,
                            totalSize = totalSize,
                            isSystemFolder = false
                        )
                    )
                }
            }
        }

        val standardSystemDirectoryPaths = getStandardSystemDirectoryPaths()
        val finalDetails = folderDetailsList.map { details ->
            val folderFile = File(details.path)
            val isSystem = details.path in standardSystemDirectoryPaths || isConventionalSystemChildFolder(
                folderFile, standardSystemDirectoryPaths
            )
            details.copy(isSystemFolder = isSystem)
        }
        return@withContext Pair(finalDetails, allDiscoveredFiles)
    }

    override suspend fun handleFolderRename(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        val oldCacheEntry = folderDetailsDao.getFolderByPath(oldPath)
        if (oldCacheEntry != null) {
            folderDetailsDao.deleteByPath(listOf(oldPath))
            val newName = File(newPath).name
            val newCacheEntry = oldCacheEntry.copy(path = newPath, name = newName)
            folderDetailsDao.upsert(newCacheEntry)
        }
        fileOperationsHelper.scanPaths(listOf(oldPath, newPath))
    }

    override suspend fun handleFolderMove(sourcePath: String, destinationPath: String) = withContext(Dispatchers.IO) {
        // 1. Remove the source folder from the cache as it's now empty and deleted.
        folderDetailsDao.deleteByPath(listOf(sourcePath))

        // 2. Rescan the destination folder to update its details.
        rescanSingleFolderAndUpdateCache(destinationPath)

        // 3. Notify MediaStore about the changes.
        fileOperationsHelper.scanPaths(listOf(sourcePath, destinationPath))
    }

    private suspend fun rescanSingleFolderAndUpdateCache(folderPath: String) {
        val folderFile = File(folderPath)
        if (!folderFile.exists() || !folderFile.isDirectory) {
            // If the folder doesn't exist anymore, ensure it's removed from cache.
            folderDetailsDao.deleteByPath(listOf(folderPath))
            return
        }

        var itemCount = 0
        var totalSize = 0L
        val processedPaths = preferencesRepository.processedMediaPathsFlow.first()

        try {
            folderFile.listFiles()?.forEach { file ->
                if (file.isFile && isMediaFile(file)) {
                    if (file.absolutePath !in processedPaths) {
                        itemCount++
                        totalSize += file.length()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Could not list files in $folderPath during single rescan", e)
        }

        if (itemCount > 0) {
            val standardSystemDirectoryPaths = getStandardSystemDirectoryPaths()
            val isSystem = folderPath in standardSystemDirectoryPaths || isConventionalSystemChildFolder(folderFile, standardSystemDirectoryPaths)
            val details = FolderDetails(
                path = folderPath,
                name = folderFile.name,
                itemCount = itemCount,
                totalSize = totalSize,
                isSystemFolder = isSystem
            )
            folderDetailsDao.upsert(details.toFolderDetailsCache())
        } else {
            // If no media is left, remove it from the cache.
            folderDetailsDao.deleteByPath(listOf(folderPath))
        }
    }


    override suspend fun isDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            File(path).isDirectory
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getSubdirectories(path: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.isDirectory) return@withContext emptyList()

            file.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                ?.map { it.absolutePath }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getStandardSystemDirectoryPaths(): Set<String> {
        return setOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_SCREENSHOTS),
        ).mapNotNull { it?.absolutePath }.toSet()
    }

    override suspend fun getFoldersWithProcessedMedia(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val processedFolders = preferencesRepository.processedMediaPathsFlow.first()
            .mapNotNull { path ->
                try {
                    File(path).parent
                } catch (e: Exception) {
                    null
                }
            }

        val permanentlySorted = preferencesRepository.permanentlySortedFoldersFlow.first()

        val allHiddenFolderPaths = (processedFolders + permanentlySorted).distinct()

        return@withContext allHiddenFolderPaths.mapNotNull { path ->
            try {
                path to File(path).name
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.second.lowercase(Locale.ROOT) }
    }

    override suspend fun getMediaCount(): Int = withContext(Dispatchers.IO) {
        var count = 0
        val queue: Queue<File> = ArrayDeque()
        Environment.getExternalStorageDirectory()?.let { queue.add(it) }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = queue.poll() ?: continue

            if (!directory.exists() || !directory.canRead() || directory.name.startsWith(".") || !isSafeDestination(directory.absolutePath)) {
                continue
            }

            try {
                directory.listFiles()?.let { files ->
                    for (file in files) {
                        if (file.isDirectory) {
                            queue.add(file)
                        } else if (isMediaFile(file)) {
                            count++
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "getMediaCount: Could not list files in ${directory.path}", e)
            }
        }
        return@withContext count
    }

    override suspend fun getUnindexedMediaPaths(): List<String> = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "Starting scan for unindexed media paths.")
        val fileSystemPaths = getAllMediaFilePaths()
        val mediaStorePaths = getMediaStoreKnownPaths()
        val unindexedPaths = fileSystemPaths - mediaStorePaths
        Log.d(LOG_TAG, "Differential check found ${unindexedPaths.size} unindexed paths.")
        return@withContext unindexedPaths.toList()
    }

    override suspend fun getMediaStoreKnownPaths(): Set<String> = withContext(Dispatchers.IO) {
        val mediaStorePaths = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val queryUri = MediaStore.Files.getContentUri("external")

        try {
            context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext()) {
                    cursor.getString(dataColumn)?.let { path ->
                        mediaStorePaths.add(path)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to query MediaStore for all paths", e)
        }
        return@withContext mediaStorePaths
    }

    override suspend fun getAllMediaFilePaths(): Set<String> = withContext(Dispatchers.IO) {
        val fileSystemPaths = mutableSetOf<String>()
        val queue: Queue<File> = ArrayDeque()
        Environment.getExternalStorageDirectory()?.let { queue.add(it) }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = queue.poll() ?: continue

            if (!directory.exists() || !directory.canRead() || directory.name.startsWith(".") || !isSafeDestination(directory.absolutePath)) {
                continue
            }

            try {
                val files = directory.listFiles()
                if (files.isNullOrEmpty()) continue

                for (file in files) {
                    if (file.isDirectory) {
                        queue.add(file)
                    } else if (isMediaFile(file)) {
                        fileSystemPaths.add(file.absolutePath)
                    }
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not access or list files in: ${directory.path}", e)
            }
        }
        return@withContext fileSystemPaths
    }


    private fun FolderDetailsCache.toFolderDetails(): FolderDetails = FolderDetails(
        path = this.path,
        name = this.name,
        itemCount = this.itemCount,
        totalSize = this.totalSize,
        isSystemFolder = this.isSystemFolder
    )

    private fun FolderDetails.toFolderDetailsCache(): FolderDetailsCache = FolderDetailsCache(
        path = this.path,
        name = this.name,
        itemCount = this.itemCount,
        totalSize = this.totalSize,
        isSystemFolder = this.isSystemFolder
    )

    override fun observeAllFolders(): Flow<List<Pair<String, String>>> {
        val isFullScanInitiated = AtomicBoolean(false)
        if (isFullScanInitiated.compareAndSet(false, true)) {
            externalScope.launch {
                Log.d("CacheDebug", "Initiating full background folder scan.")
                scanForAndCacheAllFolders()
            }
        }

        return folderDetailsDao.getAll().map { cacheList ->
            cacheList.map { it.path to it.name }
                .sortedBy { it.second.lowercase() }
        }
    }

    private suspend fun scanForAllMediaFoldersRecursively(): Set<String> = withContext(Dispatchers.IO) {
        val mediaFolders = mutableSetOf<String>()
        val queue: Queue<File> = ArrayDeque()

        Environment.getExternalStorageDirectory()?.let { queue.add(it) }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = queue.poll() ?: continue

            if (directory.name == "To Edit" || !directory.exists() || !directory.canRead() || directory.name.startsWith(".") || !isSafeDestination(directory.absolutePath)) {
                continue
            }

            try {
                val files = directory.listFiles()
                if (files.isNullOrEmpty()) continue

                var containsMedia = false
                val subdirectories = mutableListOf<File>()

                for (file in files) {
                    if (file.isDirectory) {
                        subdirectories.add(file)
                    } else if (!containsMedia && isMediaFile(file)) {
                        containsMedia = true
                    }
                }

                if (containsMedia) {
                    mediaFolders.add(directory.absolutePath)
                }

                queue.addAll(subdirectories)

            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to access directory: ${directory.path}", e)
            }
        }
        return@withContext mediaFolders
    }

    private suspend fun scanForAndCacheAllFolders() = withContext(Dispatchers.IO) {
        val allMediaFolders = scanForAllMediaFoldersRecursively()
        Log.d("CacheDebug", "Found ${allMediaFolders.size} media folders from direct scan.")

        lastKnownFolderState = allMediaFolders

        val cachedFolders = folderDetailsDao.getAll().first()
        val cachedFolderPaths = cachedFolders.map { it.path }.toSet()
        Log.d("CacheDebug", "Found ${cachedFolderPaths.size} folders in DB cache.")

        val newFolderPaths = allMediaFolders - cachedFolderPaths
        val deletedFolderPaths = cachedFolderPaths - allMediaFolders

        if (newFolderPaths.isNotEmpty()) {
            Log.d("CacheDebug", "Adding ${newFolderPaths.size} new folders to cache.")
            val newCacheEntries = newFolderPaths.map { path ->
                FolderDetailsCache(
                    path = path,
                    name = File(path).name,
                    itemCount = 0,
                    totalSize = 0L,
                    isSystemFolder = false
                )
            }
            folderDetailsDao.upsertAll(newCacheEntries)
        }

        if (deletedFolderPaths.isNotEmpty()) {
            Log.d("CacheDebug", "Deleting ${deletedFolderPaths.size} stale folders from cache.")
            folderDetailsDao.deleteByPath(deletedFolderPaths.toList())
        }

        if (newFolderPaths.isEmpty() && deletedFolderPaths.isEmpty()) {
            Log.d("CacheDebug", "Folder cache is already up-to-date.")
        }
    }

    override suspend fun scanFolders(folderPaths: List<String>) {
        fileOperationsHelper.scanPaths(folderPaths)
    }

    override suspend fun scanPathsAndWait(paths: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext true
        Log.d(LOG_TAG, "Requesting synchronous MediaScanner for paths: $paths")

        return@withContext suspendCancellableCoroutine { continuation ->
            val pathsToScan = paths.toTypedArray()
            val scanCount = AtomicInteger(paths.size)

            val listener = MediaScannerConnection.OnScanCompletedListener { path, uri ->
                Log.d(LOG_TAG, "MediaScanner finished for $path. New URI: $uri")
                if (scanCount.decrementAndGet() == 0) {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }
            }

            continuation.invokeOnCancellation {
                // The coroutine was cancelled. We don't need to do anything special here as
                // the MediaScannerConnection is fire-and-forget from the API perspective.
            }

            try {
                MediaScannerConnection.scanFile(context, pathsToScan, null, listener)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "MediaScannerConnection.scanFile threw an exception", e)
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    override suspend fun getMediaItemsFromPaths(paths: List<String>): List<MediaItem> = withContext(Dispatchers.IO) {
        val mediaItems = mutableListOf<MediaItem>()
        if (paths.isEmpty()) return@withContext emptyList()

        val mediaStoreData = getMediaStoreDataForBuckets(null)

        paths.forEach { path ->
            val file = File(path)
            if (file.exists()) {
                val cache = mediaStoreData[path]
                if (cache != null) {
                    mediaItems.add(createMediaItemFromMediaStore(file, cache))
                } else {
                    // Fallback for files that might not have made it into the cache yet
                    createMediaItemFromFile(file)?.let { mediaItems.add(it) }
                }
            }
        }

        if (mediaItems.size != paths.size) {
            Log.w(LOG_TAG, "Path-to-MediaItem conversion mismatch. In: ${paths.size}, Out: ${mediaItems.size}. Some files may not be in MediaStore or failed to convert.")
        }
        return@withContext mediaItems
    }

    override suspend fun removeFoldersFromCache(paths: Set<String>) = withContext(Dispatchers.IO) {
        if (paths.isNotEmpty()) {
            Log.d("CacheDebug", "Surgically removing ${paths.size} folders from cache: $paths")
            folderDetailsDao.deleteByPath(paths.toList())
        }
    }

    override suspend fun getIndexingStatus(): IndexingStatus = withContext(Dispatchers.IO) {
        val fileSystemPaths = getAllMediaFilePaths()
        val mediaStorePaths = getMediaStoreKnownPaths()

        val totalInFileSystem = fileSystemPaths.size
        val indexedInMediaStore = mediaStorePaths.intersect(fileSystemPaths).size

        return@withContext IndexingStatus(indexed = indexedInMediaStore, total = totalInFileSystem)
    }


    override suspend fun triggerFullMediaStoreScan(): Boolean = withContext(Dispatchers.IO) {
        val unindexedPaths = getUnindexedMediaPaths()
        if (unindexedPaths.isEmpty()) {
            Log.d(LOG_TAG, "Triggering full scan: No unindexed media found.")
            return@withContext true
        }
        Log.d(LOG_TAG, "Triggering full scan for ${unindexedPaths.size} items.")
        return@withContext scanPathsAndWait(unindexedPaths)
    }
}
