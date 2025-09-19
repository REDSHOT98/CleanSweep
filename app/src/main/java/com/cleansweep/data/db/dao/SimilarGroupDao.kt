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

package com.cleansweep.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cleansweep.data.db.entity.SimilarGroupCache

@Dao
interface SimilarGroupDao {
    /**
     * Retrieves all cached group entries. The result is a simple list which will be
     * processed into a Map<GroupId, List<FilePath>> in the use case.
     */
    @Query("SELECT * FROM similar_group_cache")
    suspend fun getAllGroupEntries(): List<SimilarGroupCache>

    /**
     * Inserts a list of group associations. If an entry (groupId, filePath) already exists,
     * it will be replaced.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroupEntries(entries: List<SimilarGroupCache>)

    /**
     * Deletes all groups that contain any of the specified file paths.
     * This is the key to cache invalidation: if one file in a group is modified, the entire
     * group must be re-evaluated.
     */
    @Query("""
        DELETE FROM similar_group_cache 
        WHERE group_id IN (
            SELECT DISTINCT group_id FROM similar_group_cache WHERE file_path IN (:filePaths)
        )
    """)
    suspend fun deleteGroupsContainingFilePaths(filePaths: List<String>)
}
