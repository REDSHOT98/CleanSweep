/*
 * CleanSweep
 * Copyright (c) 2025 LoopOtto
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
