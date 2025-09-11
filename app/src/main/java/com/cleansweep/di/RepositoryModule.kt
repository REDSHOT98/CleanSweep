package com.cleansweep.di

import com.cleansweep.data.repository.DirectMediaRepositoryImpl
import com.cleansweep.domain.repository.MediaRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMediaRepository(
        directMediaRepositoryImpl: DirectMediaRepositoryImpl
    ): MediaRepository
}