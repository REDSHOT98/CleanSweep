package com.cleansweep.ui.screens.swiper

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleansweep.data.repository.SummaryViewMode
import com.cleansweep.ui.components.FastScrollbar
import com.cleansweep.ui.components.SheetItemCard
import com.cleansweep.util.rememberIsUsingGestureNavigation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummarySheet(
    pendingChanges: List<PendingChange>,
    toDelete: List<PendingChange>,
    toKeep: List<PendingChange>,
    toConvert: List<PendingChange>,
    groupedMoves: List<Pair<String, List<PendingChange>>>,
    isApplyingChanges: Boolean,
    folderIdNameMap: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onResetChanges: () -> Unit,
    onRevertChange: (PendingChange) -> Unit,
    viewMode: SummaryViewMode = SummaryViewMode.LIST,
    onToggleViewMode: () -> Unit = {},
    applyChangesButtonLabel: String = "Apply Changes",
    sheetScrollState: LazyListState = rememberLazyListState()
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Confirm Changes") },
            text = { Text("Are you sure you want to apply these changes? This action cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    showConfirmDialog = false
                    onConfirm()
                }) {
                    Text(applyChangesButtonLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    SummarySheetContent(
        pendingChanges = pendingChanges,
        toDelete = toDelete,
        toKeep = toKeep,
        toConvert = toConvert,
        groupedMoves = groupedMoves,
        isApplyingChanges = isApplyingChanges,
        folderIdNameMap = folderIdNameMap,
        viewMode = viewMode,
        onToggleViewMode = onToggleViewMode,
        onDismiss = onDismiss,
        onConfirm = {
            if (pendingChanges.isNotEmpty()) {
                showConfirmDialog = true
            }
        },
        onResetChanges = onResetChanges,
        onRevertChange = onRevertChange,
        applyChangesButtonLabel = applyChangesButtonLabel,
        sheetScrollState = sheetScrollState
    )
}

@Composable
private fun RevertableSheetItemCard(
    change: PendingChange,
    viewMode: SummaryViewMode,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        SheetItemCard(
            item = change.item,
            viewMode = viewMode,
            modifier = Modifier.fillMaxWidth()
        )
        IconButton(
            onClick = onRevert,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Restore,
                contentDescription = "Revert change",
                tint = Color.White,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun SummarySheetContent(
    pendingChanges: List<PendingChange>,
    toDelete: List<PendingChange>,
    toKeep: List<PendingChange>,
    toConvert: List<PendingChange>,
    groupedMoves: List<Pair<String, List<PendingChange>>>,
    isApplyingChanges: Boolean,
    folderIdNameMap: Map<String, String>,
    viewMode: SummaryViewMode,
    onToggleViewMode: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onResetChanges: () -> Unit,
    onRevertChange: (PendingChange) -> Unit,
    applyChangesButtonLabel: String,
    sheetScrollState: LazyListState
) {
    val isUsingGestureNav = rememberIsUsingGestureNavigation()
    val bottomPadding = if (isUsingGestureNav) 16.dp else 32.dp
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    Column(modifier = Modifier
        .fillMaxWidth()
        .wrapContentHeight()
        .padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Review Changes (${pendingChanges.size})", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleViewMode) {
                Icon(
                    imageVector = when (viewMode) {
                        SummaryViewMode.LIST -> Icons.AutoMirrored.Filled.List
                        SummaryViewMode.GRID -> Icons.Default.GridView
                        SummaryViewMode.COMPACT -> Icons.Default.Apps
                    },
                    contentDescription = "Change view mode"
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.65f) // Set a max height
                .wrapContentHeight(Alignment.Top, unbounded = false) // Wrap content within the max height
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(), // Removed fillMaxSize
                state = sheetScrollState,
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                // "Move to" has the highest visual priority
                groupedMoves.forEach { (folderId, changesInGroup) ->
                    item {
                        val folderName = folderIdNameMap[folderId] ?: folderId.substringAfterLast('/')
                        CategoryHeader(title = "Move to: $folderName (${changesInGroup.size})", icon = Icons.AutoMirrored.Filled.DriveFileMove, iconTint = MaterialTheme.colorScheme.primary)
                    }
                    if (viewMode == SummaryViewMode.LIST) {
                        items(changesInGroup, key = { "move_${it.item.id}" }) { change ->
                            MediaItemRow(change = change, subtitle = null, onRevert = { onRevertChange(change) })
                        }
                    } else {
                        val columns = if (viewMode == SummaryViewMode.GRID) 4 else 8
                        items(changesInGroup.chunked(columns), key = { row -> row.joinToString { it.item.id } }) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                for (i in 0 until columns) {
                                    val change = row.getOrNull(i)
                                    if (change != null) {
                                        RevertableSheetItemCard(
                                            change = change,
                                            viewMode = viewMode,
                                            onRevert = { onRevertChange(change) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                if (toConvert.isNotEmpty()) {
                    item { CategoryHeader(title = "To Convert to Image (${toConvert.size})", icon = Icons.Default.Image, iconTint = MaterialTheme.colorScheme.tertiary) }
                    if (viewMode == SummaryViewMode.LIST) {
                        items(toConvert, key = { "convert_${it.item.id}" }) { change ->
                            MediaItemRow(change = change, subtitle = null, onRevert = { onRevertChange(change) })
                        }
                    } else {
                        val columns = if (viewMode == SummaryViewMode.GRID) 4 else 8
                        items(toConvert.chunked(columns), key = { row -> row.joinToString { it.item.id } }) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                for (i in 0 until columns) {
                                    val change = row.getOrNull(i)
                                    if (change != null) {
                                        RevertableSheetItemCard(
                                            change = change,
                                            viewMode = viewMode,
                                            onRevert = { onRevertChange(change) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                if (toDelete.isNotEmpty()) {
                    item { CategoryHeader(title = "To Delete (${toDelete.size})", icon = Icons.Default.Delete, iconTint = MaterialTheme.colorScheme.error) }
                    if (viewMode == SummaryViewMode.LIST) {
                        items(toDelete, key = { "delete_${it.item.id}" }) { change ->
                            MediaItemRow(change = change, subtitle = null, onRevert = { onRevertChange(change) })
                        }
                    } else {
                        val columns = if (viewMode == SummaryViewMode.GRID) 4 else 8
                        items(toDelete.chunked(columns), key = { row -> row.joinToString { it.item.id } }) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                for (i in 0 until columns) {
                                    val change = row.getOrNull(i)
                                    if (change != null) {
                                        RevertableSheetItemCard(
                                            change = change,
                                            viewMode = viewMode,
                                            onRevert = { onRevertChange(change) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                if (toKeep.isNotEmpty()) {
                    item { CategoryHeader(title = "To Keep (${toKeep.size})", icon = Icons.Default.Check, iconTint = MaterialTheme.colorScheme.secondary) }
                    if (viewMode == SummaryViewMode.LIST) {
                        items(toKeep, key = { "keep_${it.item.id}" }) { change ->
                            MediaItemRow(change = change, subtitle = "In: ${change.item.bucketName}", onRevert = { onRevertChange(change) })
                        }
                    } else {
                        val columns = if (viewMode == SummaryViewMode.GRID) 4 else 8
                        items(toKeep.chunked(columns), key = { row -> row.joinToString { it.item.id } }) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                for (i in 0 until columns) {
                                    val change = row.getOrNull(i)
                                    if (change != null) {
                                        RevertableSheetItemCard(
                                            change = change,
                                            viewMode = viewMode,
                                            onRevert = { onRevertChange(change) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            FastScrollbar(
                state = sheetScrollState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val cancelButtonText = if (pendingChanges.size <= 1) "Cancel" else "Cancel All"
            OutlinedButton(onClick = { onResetChanges(); onDismiss() }, modifier = Modifier.weight(1f)) { Text(cancelButtonText) }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f), enabled = !isApplyingChanges && pendingChanges.isNotEmpty()) {
                if (isApplyingChanges) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(applyChangesButtonLabel)
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String, icon: ImageVector, iconTint: Color) {
    Column {
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MediaItemRow(change: PendingChange, subtitle: String? = null, onRevert: () -> Unit) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            SheetItemCard(item = change.item, viewMode = SummaryViewMode.LIST)
            IconButton(
                onClick = onRevert,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = "Revert change",
                    tint = Color.White,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = change.item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
