package com.cleansweep.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.cleansweep.data.db.entity.FolderDetailsCache
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDetailsDao {
    @Query("SELECT * FROM folder_details_cache")
    fun getAll(): Flow<List<FolderDetailsCache>>

    @Query("SELECT * FROM folder_details_cache WHERE path = :path")
    suspend fun getFolderByPath(path: String): FolderDetailsCache?

    @Upsert
    suspend fun upsert(folder: FolderDetailsCache)

    @Upsert
    suspend fun upsertAll(folders: List<FolderDetailsCache>)

    @Query("DELETE FROM folder_details_cache WHERE path IN (:paths)")
    suspend fun deleteByPath(paths: List<String>)

    @Query("DELETE FROM folder_details_cache")
    suspend fun clear()
}