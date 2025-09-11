package com.cleansweep.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.cleansweep.data.db.entity.PHashCache

@Dao
interface PHashDao {
    @Query("SELECT * FROM phash_cache")
    suspend fun getAllHashes(): List<PHashCache>

    @Upsert
    suspend fun upsertHashes(hashes: List<PHashCache>)

    @Query("DELETE FROM phash_cache WHERE file_path IN (:paths)")
    suspend fun deleteHashesByPath(paths: List<String>)

    @Query("DELETE FROM phash_cache")
    suspend fun clearAll()
}