package com.cleansweep.data.db.entity

import android.net.Uri
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cleansweep.data.model.MediaItem
import com.cleansweep.domain.model.DuplicateGroup
import com.cleansweep.domain.model.SimilarGroup

@Entity(
    tableName = "media_item_refs",
    foreignKeys = [
        ForeignKey(
            entity = ScanResultGroupCacheEntry::class,
            parentColumns = ["uniqueId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["groupId"])]
)
data class MediaItemRefCacheEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val groupId: String,
    val mediaItemId: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateAdded: Long,
    val dateModified: Long,
    val size: Long,
    val bucketId: String,
    val bucketName: String,
    val isVideo: Boolean,
    val width: Int,
    val height: Int
)

fun MediaItem.toCacheEntry(groupId: String): MediaItemRefCacheEntry {
    return MediaItemRefCacheEntry(
        groupId = groupId,
        mediaItemId = this.id,
        uri = this.uri,
        displayName = this.displayName,
        mimeType = this.mimeType,
        dateAdded = this.dateAdded,
        dateModified = this.dateModified,
        size = this.size,
        bucketId = this.bucketId,
        bucketName = this.bucketName,
        isVideo = this.isVideo,
        width = this.width,
        height = this.height
    )
}

fun MediaItemRefCacheEntry.toMediaItem(): MediaItem {
    return MediaItem(
        id = this.mediaItemId,
        uri = this.uri,
        displayName = this.displayName,
        mimeType = this.mimeType,
        dateAdded = this.dateAdded,
        dateModified = this.dateModified,
        size = this.size,
        bucketId = this.bucketId,
        bucketName = this.bucketName,
        isVideo = this.isVideo,
        width = this.width,
        height = this.height
    )
}

fun List<MediaItemRefCacheEntry>.toDuplicateGroup(groupCacheEntry: ScanResultGroupCacheEntry): DuplicateGroup {
    val items = this.map { it.toMediaItem() }
    return DuplicateGroup(
        signature = groupCacheEntry.signature!!,
        items = items,
        sizePerFile = items.firstOrNull()?.size ?: 0L
    )
}

fun List<MediaItemRefCacheEntry>.toSimilarGroup(groupCacheEntry: ScanResultGroupCacheEntry): SimilarGroup {
    return SimilarGroup(
        pHash = groupCacheEntry.pHash!!,
        items = this.map { it.toMediaItem() }
    )
}
