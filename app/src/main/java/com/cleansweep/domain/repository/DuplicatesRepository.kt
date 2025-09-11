package com.cleansweep.domain.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cleansweep.data.db.dao.ScanResultCacheDao
import com.cleansweep.data.db.dao.UnreadableFileCacheDao
import com.cleansweep.data.db.entity.UnreadableFileCache
import com.cleansweep.data.model.MediaItem
import com.cleansweep.domain.model.ScanResultGroup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A data class to hold the fully validated results loaded from the database.
 * @param timestamp The time the original scan was completed.
 */
data class PersistedScanResult(
    val groups: List<ScanResultGroup>,
    val unscannableFiles: List<String>,
    val timestamp: Long
)

@Singleton
class DuplicatesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val unreadableFileCacheDao: UnreadableFileCacheDao,
    private val scanResultCacheDao: ScanResultCacheDao
) {
    companion object {
        private const val PREFS_NAME = "duplicates_prefs"
        private const val KEY_HIDDEN_GROUP_IDS = "hidden_group_ids"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun getHiddenGroupIds(): Set<String> = withContext(Dispatchers.IO) {
        prefs.getStringSet(KEY_HIDDEN_GROUP_IDS, emptySet()) ?: emptySet()
    }

    suspend fun hideGroupId(groupId: String) = withContext(Dispatchers.IO) {
        val currentHiddenIds = getHiddenGroupIds().toMutableSet()
        currentHiddenIds.add(groupId)
        prefs.edit { putStringSet(KEY_HIDDEN_GROUP_IDS, currentHiddenIds) }
    }

    suspend fun getUnreadableFileCache(): List<UnreadableFileCache> = withContext(Dispatchers.IO) {
        return@withContext unreadableFileCacheDao.getAll()
    }

    suspend fun updateUnreadableFileCache(newlyUnreadable: List<File>) = withContext(Dispatchers.IO) {
        if (newlyUnreadable.isEmpty()) return@withContext

        val cacheEntries = newlyUnreadable.map {
            UnreadableFileCache(
                filePath = it.absolutePath,
                lastModified = it.lastModified(),
                size = it.length()
            )
        }
        unreadableFileCacheDao.upsertAll(cacheEntries)
    }

    suspend fun clearUnreadableFileCache() = withContext(Dispatchers.IO) {
        unreadableFileCacheDao.clear()
    }

    // --- Scan Result Caching ---

    suspend fun saveScanResults(
        groups: List<ScanResultGroup>,
        unscannableFiles: List<String>,
        timestamp: Long? = null
    ) = withContext(Dispatchers.IO) {
        scanResultCacheDao.saveScanResults(groups, unscannableFiles, timestamp)
    }

    /**
     * Performs a full validation of the cached scan results.
     * @return `true` if there is at least one valid group of duplicates, `false` otherwise.
     */
    suspend fun hasValidCachedResults(): Boolean = withContext(Dispatchers.IO) {
        val loadedData = scanResultCacheDao.loadLatestScanResults() ?: return@withContext false
        val (rawGroups, _) = loadedData // Unscannable files are ignored for this check

        for (group in rawGroups) {
            val validatedItems = group.items.filter { isMediaItemStillValid(it) }
            if (validatedItems.size > 1) {
                // Found at least one valid group, no need to check further.
                return@withContext true
            }
        }

        return@withContext false
    }

    /**
     * Loads the latest scan results from the cache and performs a robust validation to ensure
     * the files still exist and have not been modified.
     *
     * @return A [PersistedScanResult] object if valid results are found, otherwise null.
     */
    suspend fun loadLatestScanResults(): PersistedScanResult? = withContext(Dispatchers.IO) {
        val loadedData = scanResultCacheDao.loadLatestScanResults() ?: return@withContext null
        val (rawGroups, unscannableFiles, timestamp) = loadedData

        val validatedGroups = rawGroups.mapNotNull { group ->
            val validatedItems = group.items.filter { isMediaItemStillValid(it) }

            if (validatedItems.size > 1) {
                group.withUpdatedItems(validatedItems)
            } else {
                null // Discard group if it no longer has duplicates
            }
        }

        if (validatedGroups.isEmpty() && unscannableFiles.isEmpty()) {
            return@withContext null // No valid data remains after validation
        }

        return@withContext PersistedScanResult(
            groups = validatedGroups,
            unscannableFiles = unscannableFiles,
            timestamp = timestamp
        )
    }

    suspend fun clearAllScanResults() = withContext(Dispatchers.IO) {
        scanResultCacheDao.clearAllScanResults()
    }

    /**
     * Checks if a MediaItem from the cache is still valid by comparing its path,
     * last modified time, and size with the file on disk.
     */
    private fun isMediaItemStillValid(item: MediaItem): Boolean {
        return try {
            val file = File(item.id)
            // Compare timestamps at the second level to tolerate minor filesystem/mediastore discrepancies.
            val fileModifiedSeconds = file.lastModified() / 1000
            val itemModifiedSeconds = item.dateModified / 1000

            file.exists() && fileModifiedSeconds == itemModifiedSeconds && file.length() == item.size
        } catch (e: Exception) {
            false
        }
    }
}
