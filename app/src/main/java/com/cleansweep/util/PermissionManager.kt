package com.cleansweep.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

object PermissionManager {

    /**
     * Checks if the app has All Files Access permission.
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // On older versions, the legacy storage permission is enough.
            true // We assume legacy permissions are handled elsewhere
        }
    }

    /**
     * Creates an intent to request All Files Access permission.
     * This takes the user to the system settings page.
     */
    fun createAllFilesAccessIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        } else {
            null // Not needed on older versions
        }
    }

    /**
     * Registers an activity result launcher for the All Files Access permission.
     * Call this in onCreate() of your activity.
     */
    fun registerAllFilesAccessLauncher(
        activity: ComponentActivity,
        onResult: (Boolean) -> Unit
    ): ActivityResultLauncher<Intent> {
        return activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            // Check if permission was granted (regardless of result code)
            val granted = hasAllFilesAccess()
            onResult(granted)
        }
    }

    /**
     * Launches the All Files Access permission request.
     */
    fun requestAllFilesAccess(
        context: Context,
        launcher: ActivityResultLauncher<Intent>
    ) {
        val intent = createAllFilesAccessIntent(context)
        if (intent != null) {
            launcher.launch(intent)
        }
    }
}