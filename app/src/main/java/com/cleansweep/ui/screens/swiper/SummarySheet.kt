package com.cleansweep.ui.screens.swiper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleansweep.data.repository.SummaryViewMode
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
    val context = LocalContext.current

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
private fun RevertableSheetItemCard(change: PendingChange, viewMode: SummaryViewMode, onRevert: () -> Unit) {
    Box {
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

    Column(modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Review Changes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            state = sheetScrollState,
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            // "Move to" has the highest visual priority
            if (groupedMoves.isNotEmpty()) {
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
                        item {
                            LazyVerticalGrid(columns = GridCells.Fixed(columns), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().height((((changesInGroup.size + columns - 1) / columns) * if (viewMode == SummaryViewMode.GRID) 78 else 58).dp).heightIn(max = 300.dp)) {
                                items(changesInGroup, key = { "move_${folderId}_${it.item.id}" }) { change ->
                                    RevertableSheetItemCard(change = change, viewMode = viewMode, onRevert = { onRevertChange(change) })
                                }
                            }
                        }
                    }
                }
            }

            // "To Convert" is shown next for items that are ONLY being converted (not also moved)
            if (toConvert.isNotEmpty()) {
                item { CategoryHeader(title = "To Convert to Image (${toConvert.size})", icon = Icons.Default.Image, iconTint = MaterialTheme.colorScheme.tertiary) }
                if (viewMode == SummaryViewMode.LIST) {
                    items(toConvert, key = { "convert_${it.item.id}" }) { change ->
                        MediaItemRow(change = change, subtitle = null, onRevert = { onRevertChange(change) })
                    }
                } else {
                    val columns = if (viewMode == SummaryViewMode.GRID) 4 else 8
                    item {
                        LazyVerticalGrid(columns = GridCells.Fixed(columns), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().height((((toConvert.size + columns - 1) / columns) * if (viewMode == SummaryViewMode.GRID) 78 else 58).dp).heightIn(max = 300.dp)) {
                            items(toConvert, key = { "convert_${it.item.id}" }) { change ->
                                RevertableSheetItemCard(change = change, viewMode = viewMode, onRevert = { onRevertChange(change) })
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
                    item {
                        LazyVerticalGrid(columns = GridCells.Fixed(columns), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().height((((toDelete.size + columns - 1) / columns) * if (viewMode == SummaryViewMode.GRID) 78 else 58).dp).heightIn(max = 300.dp)) {
                            items(toDelete, key = { "delete_${it.item.id}" }) { change ->
                                RevertableSheetItemCard(change = change, viewMode = viewMode, onRevert = { onRevertChange(change) })
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
                    item {
                        LazyVerticalGrid(columns = GridCells.Fixed(columns), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().height((((toKeep.size + columns - 1) / columns) * if (viewMode == SummaryViewMode.GRID) 78 else 58).dp).heightIn(max = 300.dp)) {
                            items(toKeep, key = { "keep_${it.item.id}" }) { change ->
                                RevertableSheetItemCard(change = change, viewMode = viewMode, onRevert = { onRevertChange(change) })
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp, bottom = bottomPadding),
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
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MediaItemRow(change: PendingChange, subtitle: String? = null, onRevert: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // We use a Box to overlay the revert button on the card.
        Box {
            // The actual item card
            SheetItemCard(item = change.item, viewMode = SummaryViewMode.LIST)
            // The revert button, aligned to the top end of the box.
            IconButton(
                onClick = onRevert,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp) // Padding inside the box
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = "Revert change",
                    tint = Color.White,
                    modifier = Modifier.padding(4.dp) // Padding for the icon itself
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