package com.cleansweep.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.cleansweep.data.db.entity.FileSignatureCache

@Dao
interface FileSignatureDao {
    @Query("SELECT * FROM file_signature_cache")
    suspend fun getAllHashes(): List<FileSignatureCache>

    @Upsert
    suspend fun upsertHashes(hashes: List<FileSignatureCache>)

    @Query("DELETE FROM file_signature_cache WHERE filePath IN (:paths)")
    suspend fun deleteHashesByPath(paths: List<String>)

    @Query("DELETE FROM file_signature_cache")
    suspend fun clear()
}
