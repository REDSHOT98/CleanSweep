package com.cleansweep.domain.model

import com.cleansweep.data.model.MediaItem

/**
 * A sealed interface representing a group of scan results.
 * This can be either a group of exact duplicates or a group of visually similar media.
 */
sealed interface ScanResultGroup {
    val items: List<MediaItem>
    val uniqueId: String

    /**
     * Creates a new instance of the group with an updated list of items.
     * This is used after validation to filter out items that no longer exist.
     */
    fun withUpdatedItems(newItems: List<MediaItem>): ScanResultGroup
}

/**
 * Represents a group of files that are byte-for-byte identical.
 *
 * @property signature The SHA-256 hash or other signature common to all files in this group.
 * @property items The list of identical media items. These are pre-sorted by date.
 * @property sizePerFile The size of each individual file in the group.
 */
data class DuplicateGroup(
    val signature: String,
    override val items: List<MediaItem>,
    val sizePerFile: Long
) : ScanResultGroup {
    override val uniqueId: String get() = signature

    override fun withUpdatedItems(newItems: List<MediaItem>): ScanResultGroup = copy(items = newItems)
}

/**
 * Represents a group of visually similar media items (images or videos).
 *
 * @property pHash The perceptual hash common to all media items in this group.
 * @property items The list of visually similar media items.
 */
data class SimilarGroup(
    val pHash: String,
    override val items: List<MediaItem>
) : ScanResultGroup {
    override val uniqueId: String get() = pHash
    override fun withUpdatedItems(newItems: List<MediaItem>): ScanResultGroup = copy(items = newItems)
}
