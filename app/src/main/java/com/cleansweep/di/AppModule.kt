package com.cleansweep.di

import android.content.Context
import android.os.Build
import androidx.work.WorkManager
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.cleansweep.domain.bus.FileModificationEventBus
import com.cleansweep.util.ProactiveIndexer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // Define a custom qualifier for the application-wide coroutine scope
    @Retention(AnnotationRetention.BINARY)
    @Qualifier
    annotation class ApplicationScope

    // --- NEW: Qualifier for the GIF ImageLoader ---
    @Retention(AnnotationRetention.BINARY)
    @Qualifier
    annotation class GifImageLoader

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        // Use SupervisorJob so that if one coroutine fails, it doesn't cancel the whole scope
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .respectCacheHeaders(false)
            .build()
    }

    // --- NEW: Provider for the GIF-specific ImageLoader ---
    @Provides
    @Singleton
    @GifImageLoader
    fun provideGifImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(ImageDecoderDecoder.Factory())
            }
            .respectCacheHeaders(false)
            .build()
    }

    @Provides
    @Singleton
    fun provideFileModificationEventBus(): FileModificationEventBus {
        return FileModificationEventBus()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideProactiveIndexer(@ApplicationContext context: Context): ProactiveIndexer {
        return ProactiveIndexer(context)
    }
}