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

package com.cleansweep.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
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

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add the new scopeType column to the scan_result_groups table.
                // We default to 'FULL' because any pre-existing cached data was from a full scan by definition.
                db.execSQL("ALTER TABLE scan_result_groups ADD COLUMN scopeType TEXT NOT NULL DEFAULT 'FULL'")
            }
        }
    }
}
