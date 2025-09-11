package com.cleansweep.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cleansweep.data.db.entity.MediaItemRefCacheEntry
import com.cleansweep.data.db.entity.ScanResultGroupCacheEntry
import com.cleansweep.data.db.entity.toCacheEntry
import com.cleansweep.data.db.entity.toDuplicateGroup
import com.cleansweep.data.db.entity.toSimilarGroup
import com.cleansweep.data.db.entity.toUnscannableFilesCacheEntry
import com.cleansweep.domain.model.DuplicateGroup
import com.cleansweep.domain.model.ScanResultGroup
import com.cleansweep.domain.model.SimilarGroup

@Dao
interface ScanResultCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScanResultGroup(group: ScanResultGroupCacheEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaItemRefs(items: List<MediaItemRefCacheEntry>)

    @Query("SELECT * FROM scan_result_groups WHERE uniqueId != 'UNSCANNABLE_SUMMARY' ORDER BY timestamp DESC")
    suspend fun getAllScanResultGroups(): List<ScanResultGroupCacheEntry>

    @Query("SELECT * FROM scan_result_groups WHERE uniqueId = 'UNSCANNABLE_SUMMARY' LIMIT 1")
    suspend fun getUnscannableFilesEntry(): ScanResultGroupCacheEntry?

    @Query("SELECT * FROM media_item_refs WHERE groupId = :groupId")
    suspend fun getMediaItemRefsForGroup(groupId: String): List<MediaItemRefCacheEntry>

    @Query("DELETE FROM scan_result_groups")
    suspend fun clearAllScanResultGroups()

    @Query("DELETE FROM media_item_refs")
    suspend fun clearAllMediaItemRefs()

    @Transaction
    suspend fun clearAllScanResults() {
        clearAllScanResultGroups()
        clearAllMediaItemRefs()
    }

    @Transaction
    suspend fun saveScanResults(
        groups: List<ScanResultGroup>,
        unscannableFiles: List<String>,
        timestampOverride: Long? = null
    ) {
        // Clear previous results first
        clearAllScanResults()

        val timestamp = timestampOverride ?: System.currentTimeMillis()

        // Save actual groups
        groups.forEach { group ->
            when (group) {
                is DuplicateGroup -> {
                    upsertScanResultGroup(group.toCacheEntry(timestamp))
                    upsertMediaItemRefs(group.items.map { it.toCacheEntry(group.uniqueId) })
                }
                is SimilarGroup -> {
                    upsertScanResultGroup(group.toCacheEntry(timestamp))
                    upsertMediaItemRefs(group.items.map { it.toCacheEntry(group.uniqueId) })
                }
            }
        }

        // Save unscannable files summary
        if (unscannableFiles.isNotEmpty()) {
            val unscannableEntry = unscannableFiles.toUnscannableFilesCacheEntry(timestamp)
            upsertScanResultGroup(unscannableEntry)
        }
    }

    @Transaction
    suspend fun loadLatestScanResults(): Triple<List<ScanResultGroup>, List<String>, Long>? {
        val groupEntries = getAllScanResultGroups()
        val unscannableEntry = getUnscannableFilesEntry()
        val timestamp = groupEntries.firstOrNull()?.timestamp ?: unscannableEntry?.timestamp

        if (groupEntries.isEmpty() && unscannableEntry == null || timestamp == null) {
            return null // No results to load
        }

        val results = mutableListOf<ScanResultGroup>()
        for (groupEntry in groupEntries) {
            val mediaItemRefs = getMediaItemRefsForGroup(groupEntry.uniqueId)
            if (mediaItemRefs.size > 1) { // Only re-add groups that still have duplicates
                when (groupEntry.groupType) {
                    "EXACT" -> results.add(mediaItemRefs.toDuplicateGroup(groupEntry))
                    "SIMILAR" -> results.add(mediaItemRefs.toSimilarGroup(groupEntry))
                }
            }
        }

        val loadedUnscannableFiles = unscannableEntry?.unscannableFilePaths ?: emptyList()

        return Triple(results.toList(), loadedUnscannableFiles, timestamp)
    }
}
