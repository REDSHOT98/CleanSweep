package com.cleansweep.domain.usecase

import com.cleansweep.data.model.MediaItem

/**
 * Represents a group of media files that are identical.
 *
 * @param hash The common hash of all items in the group.
 * @param items The list of identical MediaItem objects.
 * @param sizePerFile The size of a single file in this group.
 */
data class DuplicateGroup(
    val hash: String,
    val items: List<MediaItem>,
    val sizePerFile: Long
)