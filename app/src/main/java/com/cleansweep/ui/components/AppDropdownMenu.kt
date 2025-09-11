package com.cleansweep.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.cleansweep.ui.theme.AppTheme
import com.cleansweep.ui.theme.ExpressiveShapes
import com.cleansweep.ui.theme.LocalAppTheme

/**
 * A wrapper for DropdownMenu that provides a central point for future styling.
 * It includes special handling for the AMOLED theme to ensure visibility by adding a border.
 *
 * @param content The menu items to display, typically [DropdownMenuItem]s and [AppMenuDivider]s.
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit
) {
    val isAmoled = LocalAppTheme.current == AppTheme.AMOLED

    val menuModifier = if (isAmoled) {
        // For AMOLED, add a visible border to distinguish the menu from the black background.
        modifier
            .clip(ExpressiveShapes.medium) // Clip first to ensure the border follows the shape.
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = ExpressiveShapes.medium
            )
    } else {
        modifier
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = menuModifier,
        offset = offset,
        properties = properties,
        content = content
    )
}

/**
 * A styled divider for use within an [AppDropdownMenu].
 */
@Composable
fun AppMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 12.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    )
}