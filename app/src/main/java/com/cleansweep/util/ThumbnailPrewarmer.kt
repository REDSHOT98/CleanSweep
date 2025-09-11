package com.cleansweep.util

import android.content.Context
import android.os.Build
import android.util.Log
import android.util.Size
import com.cleansweep.domain.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThumbnailPrewarmer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository
) {
    private val contentResolver = context.contentResolver
    private val LOG_TAG = "ThumbnailPrewarmer"

    /**
     * A robust, multi-step process to ensure newly discovered media files are fully indexed
     * in the MediaStore, including their crucial thumbnails.
     *
     * This process is designed to overcome the lazy, asynchronous nature of MediaStore scanning.
     *
     * @param filePaths A list of absolute paths for the media files to be indexed and pre-warmed.
     */
    suspend fun prewarm(filePaths: List<String>) {
        if (filePaths.isEmpty()) {
            return
        }

        withContext(Dispatchers.IO) {
            Log.d(LOG_TAG, "Starting pre-warm process for ${filePaths.size} files.")

            // Step 1: Scan files and wait for MediaStore to acknowledge them.
            // This is critical to get a content:// URI.
            val scanSuccess = mediaRepository.scanPathsAndWait(filePaths)
            if (!scanSuccess) {
                Log.e(LOG_TAG, "Pre-warm failed: scanPathsAndWait returned false.")
                return@withContext
            }
            Log.d(LOG_TAG, "Scan successful. Fetching refreshed media items.")

            // Step 2: Fetch the full MediaItem objects, which should now have content:// URIs.
            val mediaItems = mediaRepository.getMediaItemsFromPaths(filePaths)
            if (mediaItems.isEmpty()) {
                Log.w(LOG_TAG, "Pre-warm warning: No media items found after successful scan for paths: $filePaths")
                return@withContext
            }

            // Step 3: For each item with a content URI, explicitly load its thumbnail.
            // This forces the system to generate and cache it if it doesn't exist.
            var prewarmedCount = 0
            mediaItems.forEach { item ->
                if (item.uri.scheme == "content") {
                    try {
                        // The size is a standard thumbnail size. We discard the result;
                        // the act of calling this method forces the generation.
                        contentResolver.loadThumbnail(item.uri, Size(256, 256), null)
                        prewarmedCount++
                    } catch (e: Exception) {
                        // This can happen if the file is corrupt, already deleted, etc.
                        Log.w(LOG_TAG, "Failed to pre-warm thumbnail for ${item.id}", e)
                    }
                }
            }
            Log.d(LOG_TAG, "Pre-warm process complete. Successfully pre-warmed $prewarmedCount of ${mediaItems.size} items.")
        }
    }
}