package com.cleansweep.di

import android.content.Context
import androidx.room.Room
import com.cleansweep.data.db.CleanSweepDatabase
import com.cleansweep.data.db.dao.FileSignatureDao
import com.cleansweep.data.db.dao.FolderDetailsDao
import com.cleansweep.data.db.dao.PHashDao
import com.cleansweep.data.db.dao.ScanResultCacheDao
import com.cleansweep.data.db.dao.SimilarGroupDao
import com.cleansweep.data.db.dao.UnreadableFileCacheDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CleanSweepDatabase {
        return Room.databaseBuilder(
            context,
            CleanSweepDatabase::class.java,
            CleanSweepDatabase.DATABASE_NAME
        ).build()
    }

    @Provides
    @Singleton
    fun provideFileSignatureDao(database: CleanSweepDatabase): FileSignatureDao {
        return database.fileSignatureDao()
    }

    @Provides
    @Singleton
    fun providePHashDao(database: CleanSweepDatabase): PHashDao {
        return database.pHashDao()
    }

    @Provides
    @Singleton
    fun provideSimilarGroupDao(database: CleanSweepDatabase): SimilarGroupDao {
        return database.similarGroupDao()
    }

    @Provides
    @Singleton
    fun provideFolderDetailsDao(database: CleanSweepDatabase): FolderDetailsDao {
        return database.folderDetailsDao()
    }

    @Provides
    @Singleton
    fun provideUnreadableFileCacheDao(database: CleanSweepDatabase): UnreadableFileCacheDao {
        return database.unreadableFileCacheDao()
    }

    @Provides
    @Singleton
    fun provideScanResultCacheDao(database: CleanSweepDatabase): ScanResultCacheDao {
        return database.scanResultCacheDao()
    }
}
