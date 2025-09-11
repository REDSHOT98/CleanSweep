package com.cleansweep.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Caches the perceptual hash (pHash) and an optional color histogram
 * for a media file (image or video).
 */
@Entity(tableName = "phash_cache")
data class PHashCache(
    @PrimaryKey
    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "last_modified")
    val lastModified: Long,

    val size: Long,

    @ColumnInfo(name = "p_hash")
    val pHash: String,

    /**
     * This is only generated for images to provide a second factor for similarity checks.
     * It is null for videos.
     */
    val histogram: String?
)