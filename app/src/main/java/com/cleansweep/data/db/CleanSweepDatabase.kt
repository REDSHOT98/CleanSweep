package com.cleansweep.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.cleansweep.data.db.converter.Converters
import com.cleansweep.data.db.dao.FileSignatureDao
import com.cleansweep.data.db.dao.FolderDetailsDao
import com.cleansweep.data.db.dao.PHashDao
import com.cleansweep.data.db.dao.ScanResultCacheDao
import com.cleansweep.data.db.dao.SimilarGroupDao
import com.cleansweep.data.db.dao.UnreadableFileCacheDao
import com.cleansweep.data.db.entity.FileSignatureCache
import com.cleansweep.data.db.entity.FolderDetailsCache
import com.cleansweep.data.db.entity.MediaItemRefCacheEntry
import com.cleansweep.data.db.entity.PHashCache
import com.cleansweep.data.db.entity.ScanResultGroupCacheEntry
import com.cleansweep.data.db.entity.SimilarGroupCache
import com.cleansweep.data.db.entity.UnreadableFileCache

@Database(
    entities = [
        FileSignatureCache::class,
        FolderDetailsCache::class,
        PHashCache::class,
        SimilarGroupCache::class,
        ScanResultGroupCacheEntry::class,
        MediaItemRefCacheEntry::class,
        UnreadableFileCache::class
    ],
    version = 1,
    autoMigrations = [],
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CleanSweepDatabase : RoomDatabase() {
    abstract fun fileSignatureDao(): FileSignatureDao
    abstract fun folderDetailsDao(): FolderDetailsDao
    abstract fun pHashDao(): PHashDao
    abstract fun similarGroupDao(): SimilarGroupDao
    abstract fun scanResultCacheDao(): ScanResultCacheDao
    abstract fun unreadableFileCacheDao(): UnreadableFileCacheDao

    companion object {
        const val DATABASE_NAME = "cleansweep_db"
    }
}