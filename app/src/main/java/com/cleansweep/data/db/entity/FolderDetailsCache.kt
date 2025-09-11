package com.cleansweep.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Caches the aggregated details of a media folder to avoid re-scanning the
 * file system on every app launch.
 *
 * @param path The absolute path to the folder, serving as its unique ID.
 * @param name The display name of the folder.
 * @param itemCount The number of media items within the folder.
 * @param totalSize The total size of all media items in the folder, in bytes.
 * @param isSystemFolder A flag indicating if this is a primary system directory.
 */
@Entity(tableName = "folder_details_cache")
data class FolderDetailsCache(
    @PrimaryKey val path: String,
    val name: String,
    val itemCount: Int,
    val totalSize: Long,
    val isSystemFolder: Boolean
)