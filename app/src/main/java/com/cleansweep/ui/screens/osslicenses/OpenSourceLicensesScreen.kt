package com.cleansweep.ui.screens.osslicenses

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.LibraryDefaults
import com.mikepenz.aboutlibraries.ui.compose.libraryColors
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(onNavigateUp: () -> Unit) {
    val libraries by rememberLibraries()
    var selectedLibrary by remember { mutableStateOf<Library?>(null) }

    // This dialog will be displayed when a library is selected.
    // It uses the standard AlertDialog, which respects the app's MaterialTheme shapes.
    if (selectedLibrary != null) {
        val library = selectedLibrary!! // Safe due to the null check
        val scrollState = rememberScrollState()

        AlertDialog(
            onDismissRequest = { selectedLibrary = null },
            title = { Text(text = library.name) },
            text = {
                // The license text can be long, so we make it scrollable.
                Column(modifier = Modifier.verticalScroll(scrollState)) {
                    // A library can have multiple licenses, but we'll display the first one's content.
                    val licenseContent = library.licenses.firstOrNull()?.licenseContent
                    Text(
                        text = if (licenseContent.isNullOrBlank()) {
                            "License content not found. Please check the library's website for more information."
                        } else {
                            licenseContent
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedLibrary = null }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open-Source Licenses") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        if (libraries?.libraries.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LibrariesContainer(
                libraries = libraries,
                modifier = Modifier.padding(padding),
                colors = LibraryDefaults.libraryColors(
                    backgroundColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    badgeBackgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    badgeContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                // Intercept the click to show our themed dialog instead of the default one.
                onLibraryClick = { library ->
                    selectedLibrary = library
                }
            )
        }
    }
}