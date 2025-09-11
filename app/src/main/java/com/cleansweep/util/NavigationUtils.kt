package com.cleansweep.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Detects whether the device is using gesture navigation or button navigation
 */
@Composable
fun rememberIsUsingGestureNavigation(): Boolean {
    val density = LocalDensity.current
    val navBarInsets = WindowInsets.navigationBars.getBottom(density)

    val isGestureNav by remember(navBarInsets) {
        derivedStateOf {
            // A simple heuristic: if the navigation bar is very thin, it's gesture navigation.
            navBarInsets < with(density) { 20.dp.toPx() }
        }
    }
    return isGestureNav
}
