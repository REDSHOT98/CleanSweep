package com.cleansweep.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleansweep.ui.theme.CleanSweepTheme
import com.cleansweep.util.ProactiveIndexer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseActivity() {

    @Inject
    lateinit var proactiveIndexer: ProactiveIndexer

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schedule the proactive indexing job on app startup.
        // WorkManager's unique work policy will prevent redundant runs.
        proactiveIndexer.scheduleGlobalIndex()

        setContent {
            val currentTheme by mainViewModel.currentTheme.collectAsStateWithLifecycle()
            val useDynamicColors by mainViewModel.useDynamicColors.collectAsStateWithLifecycle()
            val accentColorKey by mainViewModel.accentColorKey.collectAsStateWithLifecycle()
            val windowSizeClass = calculateWindowSizeClass(this)

            CleanSweepTheme(
                theme = currentTheme,
                useDynamicColors = useDynamicColors,
                accentColorKey = accentColorKey
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainApp(viewModel = mainViewModel, windowSizeClass = windowSizeClass)
                }
            }
        }
    }
}