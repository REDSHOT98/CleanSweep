package com.cleansweep.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.cleansweep.ui.navigation.AppNavigation
import com.cleansweep.ui.navigation.Screen
import com.cleansweep.util.PermissionManager

/**
 * This composable contains the entire UI of the main application, AFTER all security
 * and license checks have passed. It is shared by both flavors.
 */
@Composable
fun MainApp(
    viewModel: MainViewModel,
    windowSizeClass: WindowSizeClass
) {
    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsStateWithLifecycle()
    var hasAllFilesAccess by remember { mutableStateOf(PermissionManager.hasAllFilesAccess()) }
    var justGrantedAccess by remember { mutableStateOf(false) }

    // This observer will re-check the permission when the app is resumed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val oldState = hasAllFilesAccess
                val newState = PermissionManager.hasAllFilesAccess()
                hasAllFilesAccess = newState
                if (!oldState && newState) {
                    justGrantedAccess = true // Set a one-time flag that we just got permission
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    when (isOnboardingCompleted) {
        null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        true -> {
            if (hasAllFilesAccess) {
                val startDestination = if (justGrantedAccess) {
                    Screen.SessionSetup.createRoute(forceRefresh = true)
                } else {
                    Screen.SessionSetup.createRoute(forceRefresh = false)
                }

                AppNavigation(
                    navController = rememberNavController(),
                    windowSizeClass = windowSizeClass,
                    startDestination = startDestination
                )

                // After the composition that uses the flag, reset it.
                LaunchedEffect(justGrantedAccess) {
                    if (justGrantedAccess) {
                        justGrantedAccess = false
                    }
                }
            } else {
                PermissionRequiredScreen()
            }
        }
        false -> {
            val navController = rememberNavController()
            AppNavigation(
                navController = navController,
                windowSizeClass = windowSizeClass,
                startDestination = Screen.Onboarding.route
            )
        }
    }
}

@Composable
fun PermissionRequiredScreen() {
    var showCloseDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Storage,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "All Files Access Required",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "CleanSweep needs All Files Access permission to organize your photos and videos across all folders on your device. This permission was requested during onboarding.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Please grant the permission in your device settings to continue using CleanSweep.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                try {
                    val intent = PermissionManager.createAllFilesAccessIntent(context)
                    if (intent != null) {
                        context.startActivity(intent)
                    } else {
                        showCloseDialog = true
                    }
                } catch (e: Exception) {
                    showCloseDialog = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Settings")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { showCloseDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Close App")
        }
    }

    if (showCloseDialog) {
        AlertDialog(
            onDismissRequest = { showCloseDialog = false },
            title = { Text("Close CleanSweep") },
            text = {
                Text("CleanSweep cannot function without All Files Access permission. We tried to, but it ended up being required for CleanSweep to work properly for 99.9% of users.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        (context as? androidx.activity.ComponentActivity)?.finish()
                    }
                ) {
                    Text("Close")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showCloseDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}