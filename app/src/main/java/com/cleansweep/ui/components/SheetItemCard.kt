package com.cleansweep.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.SummaryViewMode

@Composable
fun SheetItemCard(
    item: MediaItem,
    viewMode: SummaryViewMode,
    modifier: Modifier = Modifier
) {
    val size = when (viewMode) {
        SummaryViewMode.GRID -> 80.dp
        SummaryViewMode.COMPACT -> 60.dp
        SummaryViewMode.LIST -> 48.dp
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(size)
            .padding(2.dp)
    ) {
        Card(
            modifier = Modifier
                .size(size * if (viewMode == SummaryViewMode.LIST) 1f else 0.78f)
                .padding(0.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.uri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // No filename display in grid or compact view modes
        // Only LIST mode shows filenames (handled by parent composable)
    }
}