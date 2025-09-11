package com.cleansweep.ui.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.cleansweep.ui.components.AppDialog
import com.cleansweep.util.PermissionManager
import kotlinx.coroutines.launch

data class OnboardingPage(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val primaryColor: Color = Color.Unspecified
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val pages = listOf(
        OnboardingPage(
            title = "Welcome to CleanSweep",
            description = "The fastest way to organize your photos and videos. Clean up your gallery in minutes, not hours.",
            icon = Icons.Default.PhotoLibrary
        ),
        OnboardingPage(
            title = "Swipe to Sort",
            description = "Swipe right to keep your media in the current folder. It's that simple!",
            icon = Icons.Default.ThumbUp
        ),
        OnboardingPage(
            title = "Quick Deletion",
            description = "Swipe left to move unwanted photos and videos to trash. Don't worry - they're safely stored until you're ready to delete them.",
            icon = Icons.Default.Delete
        ),
        OnboardingPage(
            title = "Smart Organization",
            description = "Tap folder icons below the media to quickly move photos and videos to different albums. Organize by events, people, or categories instantly.",
            icon = Icons.Default.FolderOpen
        ),
        OnboardingPage(
            title = "Duplicate Management",
            description = "Automatically detect and manage duplicate photos and videos. Free up storage space by removing identical and similar files.",
            icon = Icons.Default.ContentCopy
        ),
        OnboardingPage(
            title = "Lightning Fast",
            description = "Our gesture-based interface lets you process hundreds of files in minutes. No more endless scrolling through galleries!",
            icon = Icons.Default.Speed
        )
    )

    // Check if we have All Files Access (for Android 11+)
    var hasAllFilesAccess by remember { mutableStateOf(PermissionManager.hasAllFilesAccess()) }
    var showPermissionScreen by remember { mutableStateOf(false) }
    var showCloseDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Storage permission launcher for MANAGE_EXTERNAL_STORAGE - only initialize if we have a ComponentActivity
    val storagePermissionLauncher = remember {
        try {
            val activity = context as? ComponentActivity
            if (activity != null) {
                PermissionManager.registerAllFilesAccessLauncher(activity) { granted ->
                    hasAllFilesAccess = granted
                    if (!granted) {
                        showPermissionScreen = true
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            println("Error creating permission launcher: ${e.message}")
            null
        }
    }

    // Update the state when returning from settings
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val newPermissionState = PermissionManager.hasAllFilesAccess()
                if (newPermissionState != hasAllFilesAccess) {
                    hasAllFilesAccess = newPermissionState
                    if (newPermissionState) {
                        // Permission was granted, dismiss permission screen
                        showPermissionScreen = false
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pages.size - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // Header with skip button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp)
                .height(48.dp), // Fixed height for consistent positioning
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CleanSweep",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Always reserve space for skip button to maintain consistent layout
            if (!isLastPage) {
                TextButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pages.size - 1)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        "Skip",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                // Use same sized box to maintain layout consistency
                Box(
                    modifier = Modifier
                        .size(width = 68.dp, height = 48.dp)
                )
            }
        }

        // Pager content
        if (showPermissionScreen) {
            PermissionRequiredScreen(
                onGrant = {
                    storagePermissionLauncher?.let { launcher ->
                        PermissionManager.requestAllFilesAccess(context, launcher)
                    } ?: run {
                        // Fallback: try to open settings directly
                        try {
                            val intent = PermissionManager.createAllFilesAccessIntent(context)
                            if (intent != null) {
                                context.startActivity(intent)
                            }
                        } catch (e: Exception) {
                            // If all fails, keep showing permission screen
                        }
                    }
                },
                onClose = {
                    showCloseDialog = true
                    // Don't hide permission screen here - keep it visible behind dialog
                }
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                OnboardingPageContent(
                    page = pages[page],
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Page indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(pages.size) { index ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isSelected) 12.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                )
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            horizontalArrangement = if (isLastPage) Arrangement.Center else Arrangement.SpaceBetween
        ) {
            if (!isLastPage) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            if (pagerState.currentPage > 0) {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        }
                    },
                    enabled = pagerState.currentPage > 0,
                    modifier = Modifier
                        .height(56.dp)
                        .weight(1f)
                        .padding(end = 8.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        "Previous",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier
                        .height(56.dp)
                        .weight(1f)
                        .padding(start = 8.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        "Next",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                // Last page - check permissions
                if (!hasAllFilesAccess) {
                    Button(
                        onClick = {
                            storagePermissionLauncher?.let { launcher ->
                                PermissionManager.requestAllFilesAccess(context, launcher)
                            } ?: run {
                                // Fallback if launcher couldn't be created
                                showPermissionScreen = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Text(
                            "Grant All Files Access",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.completeOnboarding()
                            onComplete()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Text(
                            "Get Started",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    if (showCloseDialog) {
        AppDialog(
            onDismissRequest = { showCloseDialog = false },
            title = { Text("Close CleanSweep", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "CleanSweep cannot function without All Files Access permission. We tried to but it ended up being required for CleanSweep to work properly for 99.9% of users.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                TextButton(onClick = {
                    showCloseDialog = false
                    showPermissionScreen = true
                }) { Text("Cancel") }
                Button(onClick = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                        .launch {
                            (context as? ComponentActivity)?.finish()
                        }
                }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon
        Card(
            modifier = Modifier.size(120.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Title
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description
        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.2
        )
    }
}

@Composable
private fun PermissionRequiredScreen(
    onGrant: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                text = "CleanSweep needs All Files Access permission to organize your photos and videos across all folders on your device.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Please grant the permission in your device settings to use CleanSweep.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Settings")
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close App")
            }
        }
    }
}