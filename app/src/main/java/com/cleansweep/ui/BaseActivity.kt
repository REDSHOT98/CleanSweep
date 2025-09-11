package com.cleansweep.ui

import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
abstract class BaseActivity : ComponentActivity() {

    protected val mainViewModel: MainViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // Invalidate the folder cache whenever the user returns to the app.
        // This ensures the folder lists are refreshed to reflect external changes.
        mainViewModel.onAppResumed()
    }
}