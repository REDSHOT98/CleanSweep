package com.cleansweep.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "unreadable_file_cache")
data class UnreadableFileCache(
    @PrimaryKey
    val filePath: String,
    val lastModified: Long,
    val size: Long
)
