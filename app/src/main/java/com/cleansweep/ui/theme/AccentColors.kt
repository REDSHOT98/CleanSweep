package com.cleansweep.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Represents a user-selectable accent color with variants for both light and dark themes.
 * @param key A unique, stable string identifier for storing the user's preference.
 * @param displayName The user-facing name of the color.
 * @param lightColor The primary accent color to use on light backgrounds.
 * @param darkColor The primary accent color to use on dark backgrounds.
 */
data class AccentColor(
    val key: String,
    val displayName: String,
    val lightColor: Color,
    val darkColor: Color
)

/**
 * A predefined list of accent colors available for the user to choose from.
 * Each color has been hand-picked to work well in both light and dark modes.
 */
val predefinedAccentColors = listOf(
    AccentColor(
        key = "DEFAULT_BLUE",
        displayName = "Default Blue",
        lightColor = Color(0xFF0066CC),
        darkColor = Color(0xFF9FCAFF)
    ),
    AccentColor(
        key = "FOREST_GREEN",
        displayName = "Forest Green",
        lightColor = Color(0xFF1E6C34),
        darkColor = Color(0xFF83D797)
    ),
    AccentColor(
        key = "SUNSET_ORANGE",
        displayName = "Sunset Orange",
        lightColor = Color(0xFF8F5100),
        darkColor = Color(0xFFFFB865)
    ),
    AccentColor(
        key = "ROYAL_PURPLE",
        displayName = "Royal Purple",
        lightColor = Color(0xFF6750A4),
        darkColor = Color(0xFFD0BCFF)
    ),
    AccentColor(
        key = "CLASSIC_TEAL",
        displayName = "Classic Teal",
        lightColor = Color(0xFF006A62),
        darkColor = Color(0xFF74D4CA)
    ),
    AccentColor(
        key = "CRIMSON_RED",
        displayName = "Crimson Red",
        lightColor = Color(0xFFB3261E),
        darkColor = Color(0xFFFFB4AB)
    ),
    AccentColor(
        key = "HOT_PINK",
        displayName = "Hot Pink",
        lightColor = Color(0xFFB90063),
        darkColor = Color(0xFFFFB1C8)
    ),
    AccentColor(
        key = "AMBER_GOLD",
        displayName = "Amber Gold",
        lightColor = Color(0xFF7D5800),
        darkColor = Color(0xFFFABD1B)
    )
)