package com.cleansweep.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Represents the many-to-many relationship between a group of similar images and the individual files in that group.
 * This allows us to cache the computed groups, avoiding the expensive O(n^2) comparison on every scan.
 *
 * @property groupId A unique identifier for the group of similar images. This can be the pHash of one of the images.
 * @property filePath The path to an image file that is part of this group. This is also the foreign key to the pHash cache.
 */
@Entity(
    tableName = "similar_group_cache",
    primaryKeys = ["group_id", "file_path"],
    indices = [Index(value = ["file_path"])],
    foreignKeys = [
        ForeignKey(
            entity = PHashCache::class,
            parentColumns = ["file_path"],
            childColumns = ["file_path"],
            onDelete = ForeignKey.CASCADE // If a pHash entry is deleted, remove it from any groups.
        )
    ]
)
data class SimilarGroupCache(
    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "file_path")
    val filePath: String
)