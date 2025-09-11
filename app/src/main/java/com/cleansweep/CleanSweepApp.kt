package com.cleansweep

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CleanSweepApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Pre-initialize system services for memory optimization and to prevent lag during first access.
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    // 25% of available memory for cache
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024) // 512MB
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false) // Don't rely on network response headers for caching
            .allowRgb565(true) // Use RGB_565 for better memory usage
            .build()
    }
}