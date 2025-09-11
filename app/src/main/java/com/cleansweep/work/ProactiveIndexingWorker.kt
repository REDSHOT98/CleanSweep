package com.cleansweep.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.util.ThumbnailPrewarmer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class ProactiveIndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    // Injected dependencies
    private val mediaRepository: MediaRepository,
    private val thumbnailPrewarmer: ThumbnailPrewarmer
) : CoroutineWorker(appContext, workerParams) {

    private val LOG_TAG = "ProactiveIndexingWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "Starting proactive indexing background work.")
        return@withContext try {
            val unindexedPaths = mediaRepository.getUnindexedMediaPaths()

            if (unindexedPaths.isNotEmpty()) {
                Log.d(LOG_TAG, "Found ${unindexedPaths.size} un-indexed media files. Starting pre-warm process.")
                // Use the robust pre-warmer instead of a simple scan request.
                thumbnailPrewarmer.prewarm(unindexedPaths)
                Log.d(LOG_TAG, "Pre-warm process initiated for un-indexed files.")
            } else {
                Log.d(LOG_TAG, "No un-indexed media found. Work complete.")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Proactive indexing failed", e)
            Result.failure()
        }
    }
}