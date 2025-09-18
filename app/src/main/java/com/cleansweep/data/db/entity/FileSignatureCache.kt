package com.cleansweep.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Caches a calculated signature of a file to avoid re-calculating it on subsequent scans.
 * A signature represents the file's content (e.g., from a partial hash or pixel hash)
 * but is not guaranteed to be a full, byte-for-byte content hash.
 *
 * @param filePath The absolute path to the file, serving as its unique ID.
 * @param lastModified The last modification timestamp of the file when it was hashed.
 * @param size The size of the file in bytes when it was hashed.
 * @param signature The pre-calculated signature of the file.
 */
@Entity(tableName = "file_signature_cache")
data class FileSignatureCache(
    @PrimaryKey val filePath: String,
    val lastModified: Long,
    val size: Long,
    val hash: String
)
