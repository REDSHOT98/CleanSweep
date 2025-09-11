package com.cleansweep.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.data.repository.PreferencesRepository
import com.cleansweep.domain.bus.AppLifecycleEventBus
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.ui.theme.AppTheme
import com.cleansweep.ui.theme.predefinedAccentColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
    private val mediaRepository: MediaRepository,
    private val appLifecycleEventBus: AppLifecycleEventBus
) : ViewModel() {

    val currentTheme: StateFlow<AppTheme> = preferencesRepository.themeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppTheme.SYSTEM
        )

    val useDynamicColors: StateFlow<Boolean> = preferencesRepository.useDynamicColorsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val accentColorKey: StateFlow<String> = preferencesRepository.accentColorKeyFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = predefinedAccentColors.first().key
        )

    val isOnboardingCompleted: StateFlow<Boolean?> = preferencesRepository.isOnboardingCompletedFlow
        .map<Boolean, Boolean?> { it }
        .onStart { emit(null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    init {
        viewModelScope.launch {
            mediaRepository.cleanupGhostFolders()
        }
    }

    /**
     * Called when the app is brought to the foreground. Checks for underlying
     * file system changes and, only if changes are found, invalidates caches
     * and broadcasts a resume event.
     */
    fun onAppResumed() {
        viewModelScope.launch {
            val wasInvalidated = mediaRepository.checkForChangesAndInvalidate()
            if (wasInvalidated) {
                // Only post the event if a change was actually detected.
                appLifecycleEventBus.postAppResumed()
            }
        }
    }
}