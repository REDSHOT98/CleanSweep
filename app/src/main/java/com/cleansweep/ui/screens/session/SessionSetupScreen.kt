package com.cleansweep.ui.screens.session

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cleansweep.ui.components.AppDialog
import com.cleansweep.ui.components.AppDropdownMenu
import com.cleansweep.ui.components.AppMenuDivider
import com.cleansweep.ui.components.FolderSearchDialog
import com.cleansweep.ui.components.RenameFolderDialog
import com.cleansweep.ui.theme.AppTheme
import com.cleansweep.ui.theme.LocalAppTheme
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionSetupScreen(
    windowSizeClass: WindowSizeClass,
    onStartSession: (List<String>) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDuplicates: () -> Unit,
    viewModel: SessionSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val folderSearchState by viewModel.folderSearchManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchAutofocusEnabled by viewModel.searchAutofocusEnabled.collectAsState()
    val context = LocalContext.current
    val isExpandedScreen = windowSizeClass.widthSizeClass > WindowWidthSizeClass.Compact
    val TAG = "SessionSetupScreen"

    BackHandler(enabled = uiState.isContextualSelectionMode) {
        viewModel.exitContextualSelectionMode()
    }

    // Handle toast messages
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.toastMessageShown()
        }
    }

    // Handle rename dialog
    uiState.showRenameDialogForPath?.let { path ->
        val folder = uiState.allFolderDetails.find { it.path == path }
        if (folder != null) {
            RenameFolderDialog(
                currentFolderName = folder.name,
                onConfirm = { newName ->
                    viewModel.renameFolder(path, newName)
                },
                onDismiss = { viewModel.dismissRenameDialog() }
            )
        }
    }

    // Handle "Move Folder" dialog
    if (uiState.showMoveFolderDialogForPath != null) {
        FolderSearchDialog(
            state = folderSearchState,
            title = "Move to...",
            searchLabel = "Search or select destination",
            confirmButtonText = "Move",
            autoConfirmOnSelection = false, // Require explicit confirmation
            onDismiss = viewModel::dismissMoveFolderDialog,
            onQueryChanged = viewModel.folderSearchManager::updateSearchQuery,
            onFolderSelected = { path -> scope.launch { viewModel.folderSearchManager.selectPath(path) } },
            onConfirm = viewModel::confirmMoveFolderSelection,
            onSearch = { scope.launch { viewModel.folderSearchManager.selectSingleResultOrSelf() } },
            formatListItemTitle = { formatPathForDisplay(it) }
        )
    }

    // Handle "Mark Permanently as Sorted" confirmation dialog
    if (uiState.showMarkAsSortedConfirmation) {
        val foldersToMark = uiState.foldersToMarkAsSorted
        if (foldersToMark.isNotEmpty()) {
            val titleText: String
            val bodyText: String

            if (foldersToMark.size == 1) {
                val singleFolder = foldersToMark.first()
                val isRecursive = singleFolder.bucketId in uiState.recursivelySelectedRoots
                titleText = "Mark as Sorted?"
                bodyText = if (isRecursive) {
                    "Are you sure you want to permanently hide '${singleFolder.bucketName}' and its subfolders from this list? You can reset this in the settings."
                } else {
                    "Are you sure you want to permanently hide '${singleFolder.bucketName}' from this list? You can reset this in the settings."
                }
            } else {
                titleText = "Mark ${foldersToMark.size} Folders as Sorted?"
                bodyText = "Are you sure you want to permanently hide these ${foldersToMark.size} folders (including any selected subfolders) from this list? You can reset this in the settings."
            }

            AppDialog(
                onDismissRequest = viewModel::dismissMarkAsSortedDialog,
                showDontAskAgain = true,
                dontAskAgainChecked = uiState.dontAskAgainMarkAsSorted,
                onDontAskAgainChanged = viewModel::onDontAskAgainMarkAsSortedChanged,
                title = { Text(text = titleText, style = MaterialTheme.typography.headlineSmall) },
                text = { Text(text = bodyText, style = MaterialTheme.typography.bodyMedium) },
                buttons = {
                    TextButton(onClick = viewModel::dismissMarkAsSortedDialog) {
                        Text("Cancel")
                    }
                    Button(onClick = viewModel::confirmMarkFolderAsSorted) {
                        Text("Confirm")
                    }
                }
            )
        }
    }

    LaunchedEffect(searchAutofocusEnabled) {
        if (searchAutofocusEnabled) {
            focusRequester.requestFocus()
        }
    }

    Scaffold(
        topBar = {
            if (uiState.isContextualSelectionMode) {
                ContextualTopAppBar(
                    selectionCount = uiState.contextSelectedFolderPaths.size,
                    canFavorite = uiState.canFavoriteContextualSelection,
                    onClose = viewModel::exitContextualSelectionMode,
                    onSelectAll = viewModel::contextualSelectAll,
                    onMarkAsSorted = viewModel::markSelectedFoldersAsSorted,
                    onToggleFavorite = viewModel::toggleFavoriteForSelectedFolders
                )
            } else {
                DefaultTopAppBar(
                    uiState = uiState,
                    onNavigateToDuplicates = onNavigateToDuplicates,
                    onSortOptionChange = viewModel::changeSortOption,
                    onNavigateToSettings = onNavigateToSettings
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !uiState.isContextualSelectionMode,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val areAllSelected =
                    uiState.allFolderDetails.isNotEmpty() && uiState.selectedBuckets.size == uiState.allFolderDetails.size
                if (isExpandedScreen) {
                    // For larger screens, stack the FABs vertically at the end.
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = { if (areAllSelected) viewModel.unselectAll() else viewModel.selectAll() },
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ) {
                            Icon(
                                imageVector = if (areAllSelected) Icons.Default.CheckBoxOutlineBlank else Icons.Default.CheckBox,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (areAllSelected) "Unselect All" else "Select All")
                        }
                        ExtendedFloatingActionButton(
                            onClick = {
                                if (uiState.selectedBuckets.isNotEmpty()) {
                                    Log.d(
                                        TAG,
                                        "Starting session with ${uiState.selectedBuckets.size} folders: ${uiState.selectedBuckets}"
                                    )
                                    viewModel.saveSelectedBucketsPreference()
                                    onStartSession(uiState.selectedBuckets)
                                }
                            },
                            containerColor = if (uiState.selectedBuckets.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (uiState.selectedBuckets.isNotEmpty()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Session")
                        }
                    }
                } else {
                    // For compact screens, use the centered, two-button layout.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = { if (areAllSelected) viewModel.unselectAll() else viewModel.selectAll() },
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Icon(
                                imageVector = if (areAllSelected) Icons.Default.CheckBoxOutlineBlank else Icons.Default.CheckBox,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (areAllSelected) "Unselect All" else "Select All")
                        }
                        ExtendedFloatingActionButton(
                            onClick = {
                                if (uiState.selectedBuckets.isNotEmpty()) {
                                    Log.d(
                                        TAG,
                                        "Starting session with ${uiState.selectedBuckets.size} folders: ${uiState.selectedBuckets}"
                                    )
                                    viewModel.saveSelectedBucketsPreference()
                                    onStartSession(uiState.selectedBuckets)
                                }
                            },
                            containerColor = if (uiState.selectedBuckets.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (uiState.selectedBuckets.isNotEmpty()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Session")
                        }
                    }
                }
            }
        },
        floatingActionButtonPosition = if (isExpandedScreen) FabPosition.End else FabPosition.Center
    ) { paddingValues ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)) {
            TextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { keyboardController?.hide() }
                ),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") }
            )
            if (uiState.isInitialLoad) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Scanning device for media folders...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing || uiState.isDataStale,
                    onRefresh = viewModel::refreshFolders,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        uiState.folderCategories.forEach { category ->
                            if (category.folders.isNotEmpty()) {
                                item {
                                    Text(
                                        text = category.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                                    )
                                }
                                items(category.folders, key = { it.bucketId }) { folderInfo ->
                                    val isSelectedForSession = folderInfo.bucketId in uiState.selectedBuckets
                                    val isSelectedForContext = folderInfo.bucketId in uiState.contextSelectedFolderPaths
                                    EnhancedFolderItem(
                                        folderInfo = folderInfo,
                                        isSelected = if (uiState.isContextualSelectionMode) isSelectedForContext else isSelectedForSession,
                                        isContextualMode = uiState.isContextualSelectionMode,
                                        isFavorite = folderInfo.bucketId in uiState.favoriteFolders,
                                        isRecursiveRoot = folderInfo.bucketId in uiState.recursivelySelectedRoots,
                                        onToggle = {
                                            if (uiState.isContextualSelectionMode) {
                                                viewModel.toggleContextualSelection(folderInfo.bucketId)
                                            } else {
                                                if (isSelectedForSession) {
                                                    viewModel.unselectBucket(folderInfo.bucketId)
                                                } else {
                                                    viewModel.selectBucket(folderInfo.bucketId)
                                                }
                                            }
                                        },
                                        onLongPress = {
                                            viewModel.enterContextualSelectionMode(folderInfo.bucketId)
                                        },
                                        onToggleFavorite = { viewModel.toggleFavorite(folderInfo.bucketId) },
                                        onSelectRecursively = { viewModel.selectFolderRecursively(folderInfo.bucketId) },
                                        onDeselectRecursively = { viewModel.deselectChildren(folderInfo.bucketId) },
                                        onRename = { viewModel.showRenameDialog(folderInfo.bucketId) },
                                        onMove = { viewModel.showMoveFolderDialog(folderInfo.bucketId) },
                                        onMarkAsSorted = { viewModel.markFolderAsSorted(folderInfo) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultTopAppBar(
    uiState: SessionSetupUiState,
    onNavigateToDuplicates: () -> Unit,
    onSortOptionChange: (FolderSortOption) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    TopAppBar(
        title = { Text("Select Folders") },
        actions = {
            var showSortMenu by remember { mutableStateOf(false) }

            IconButton(onClick = onNavigateToDuplicates) {
                Icon(Icons.Default.ControlPointDuplicate, contentDescription = "Find Duplicates")
            }

            Box {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Name (A-Z)") },
                        onClick = {
                            onSortOptionChange(FolderSortOption.ALPHABETICAL_ASC)
                            showSortMenu = false
                        },
                        leadingIcon = {
                            if (uiState.currentSortOption == FolderSortOption.ALPHABETICAL_ASC) Icon(Icons.Default.Check, null)
                        })
                    DropdownMenuItem(
                        text = { Text("Name (Z-A)") },
                        onClick = {
                            onSortOptionChange(FolderSortOption.ALPHABETICAL_DESC)
                            showSortMenu = false
                        },
                        leadingIcon = {
                            if (uiState.currentSortOption == FolderSortOption.ALPHABETICAL_DESC) Icon(Icons.Default.Check, null)
                        })
                    DropdownMenuItem(
                        text = { Text("Size (Smallest first)") },
                        onClick = {
                            onSortOptionChange(FolderSortOption.SIZE_ASC)
                            showSortMenu = false
                        },
                        leadingIcon = {
                            if (uiState.currentSortOption == FolderSortOption.SIZE_ASC) Icon(Icons.Default.Check, null)
                        })
                    DropdownMenuItem(
                        text = { Text("Size (Largest first)") },
                        onClick = {
                            onSortOptionChange(FolderSortOption.SIZE_DESC)
                            showSortMenu = false
                        },
                        leadingIcon = {
                            if (uiState.currentSortOption == FolderSortOption.SIZE_DESC) Icon(Icons.Default.Check, null)
                        })
                }
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextualTopAppBar(
    selectionCount: Int,
    canFavorite: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onMarkAsSorted: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    TopAppBar(
        title = { Text("$selectionCount Selected") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close selection mode")
            }
        },
        actions = {
            if (canFavorite) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(Icons.Default.Star, contentDescription = "Add to Favorites")
                }
            }
            IconButton(onClick = onMarkAsSorted) {
                Icon(Icons.Default.CheckCircleOutline, contentDescription = "Mark as Sorted")
            }
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Default.SelectAll, contentDescription = "Select All")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EnhancedFolderItem(
    folderInfo: FolderInfo,
    isSelected: Boolean,
    isContextualMode: Boolean,
    isFavorite: Boolean,
    isRecursiveRoot: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelectRecursively: (String) -> Unit,
    onDeselectRecursively: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onMarkAsSorted: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val cardColors = when {
        isSelected -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        LocalAppTheme.current == AppTheme.AMOLED -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        else -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    }

    val secondaryTextColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        colors = cardColors,
        modifier = modifier
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onToggle,
                onLongClick = { if (!isContextualMode) onLongPress() }
            )
            .border(
                width = if (LocalAppTheme.current == AppTheme.AMOLED) 1.dp else 0.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = CardDefaults.shape
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Folder",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                if (folderInfo.isPrimarySystemFolder) {
                    Text(
                        text = "S",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (-3).dp, y = 5.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(text = folderInfo.bucketName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${folderInfo.itemCount} items · ${formatFileSize(folderInfo.totalSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor
                )
            }
            if (isFavorite) {
                Icon(imageVector = Icons.Default.Star, contentDescription = "Favorite", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (isRecursiveRoot) {
                Icon(imageVector = Icons.Default.AccountTree, contentDescription = "Includes sub-folders.", tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f), modifier = Modifier.padding(horizontal = 4.dp))
            }

            if (!isContextualMode) {
                Box {
                    IconButton(onClick = { showContextMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More Options")
                    }
                    AppDropdownMenu(
                        expanded = showContextMenu,
                        onDismissRequest = { showContextMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                onRename()
                                showContextMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Move to...") },
                            onClick = {
                                onMove()
                                showContextMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (isRecursiveRoot) "Deselect sub-folders" else "Select folder and sub-folders")
                            },
                            onClick = {
                                if (isRecursiveRoot) {
                                    onDeselectRecursively()
                                } else {
                                    onSelectRecursively(folderInfo.bucketId)
                                }
                                showContextMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.AccountTree, null) }
                        )
                        if (!folderInfo.isSystemFolder) {
                            DropdownMenuItem(
                                text = { Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites") },
                                onClick = {
                                    onToggleFavorite(folderInfo.bucketId)
                                    showContextMenu = false
                                },
                                leadingIcon = {
                                    val icon = if (isFavorite) Icons.Default.StarOutline else Icons.Default.Star
                                    Icon(icon, null)
                                }
                            )
                        }
                        AppMenuDivider()
                        DropdownMenuItem(
                            text = { Text("Mark Permanently as Sorted", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                onMarkAsSorted()
                                showContextMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.CheckCircleOutline, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

private fun formatPathForDisplay(path: String): Pair<String, String> {
    val file = File(path)
    val name = file.name
    val parentPath = file.parent?.replace("/storage/emulated/0", "") ?: ""
    val displayParent = if (parentPath.length > 30) "...${parentPath.takeLast(27)}" else parentPath
    return Pair(name, displayParent)
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    val formatter = DecimalFormat("#,##0.#")
    return formatter.format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
}