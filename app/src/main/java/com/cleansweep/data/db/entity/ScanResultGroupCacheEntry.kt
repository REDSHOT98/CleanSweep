package com.cleansweep.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cleansweep.domain.model.DuplicateGroup
import com.cleansweep.domain.model.SimilarGroup

@Entity(tableName = "scan_result_groups")
data class ScanResultGroupCacheEntry(
    @PrimaryKey val uniqueId: String, // Unique ID for the group (e.g., pHash for similar, signature for exact)
    val groupType: String, // "EXACT" or "SIMILAR"
    @ColumnInfo(name = "unscannable_file_paths") val unscannableFilePaths: List<String> = emptyList(), // Store only for the summary entry
    val pHash: String?, // For SimilarGroup
    val signature: String?, // For DuplicateGroup
    val timestamp: Long // When this result was saved
)

// Helper functions to convert between domain models and cache entities
fun DuplicateGroup.toCacheEntry(timestamp: Long): ScanResultGroupCacheEntry {
    return ScanResultGroupCacheEntry(
        uniqueId = this.uniqueId,
        groupType = "EXACT",
        pHash = null,
        signature = this.signature,
        timestamp = timestamp
    )
}

fun SimilarGroup.toCacheEntry(timestamp: Long): ScanResultGroupCacheEntry {
    return ScanResultGroupCacheEntry(
        uniqueId = this.uniqueId,
        groupType = "SIMILAR",
        pHash = this.pHash,
        signature = null,
        timestamp = timestamp
    )
}

fun List<String>.toUnscannableFilesCacheEntry(timestamp: Long): ScanResultGroupCacheEntry {
    return ScanResultGroupCacheEntry(
        uniqueId = "UNSCANNABLE_SUMMARY", // A special unique ID for the summary entry
        groupType = "SUMMARY_UNSCANNABLE",
        unscannableFilePaths = this,
        pHash = null,
        signature = null,
        timestamp = timestamp
    )
}
