package com.cleansweep.domain.bus

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A Singleton event bus for broadcasting app-wide lifecycle events.
 * This is used to communicate between ViewModels without creating direct dependencies,
 * which is prohibited by Hilt.
 */
@Singleton
class AppLifecycleEventBus @Inject constructor() {

    private val _appResumeEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val appResumeEvent = _appResumeEvent.asSharedFlow()

    fun postAppResumed() {
        _appResumeEvent.tryEmit(Unit)
    }
}