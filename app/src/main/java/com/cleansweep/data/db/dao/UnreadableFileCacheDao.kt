package com.cleansweep.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.cleansweep.data.db.entity.UnreadableFileCache

@Dao
interface UnreadableFileCacheDao {
    @Upsert
    suspend fun upsertAll(files: List<UnreadableFileCache>)

    @Query("SELECT * FROM unreadable_file_cache")
    suspend fun getAll(): List<UnreadableFileCache>

    @Query("DELETE FROM unreadable_file_cache WHERE filePath IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM unreadable_file_cache")
    suspend fun clear()
}