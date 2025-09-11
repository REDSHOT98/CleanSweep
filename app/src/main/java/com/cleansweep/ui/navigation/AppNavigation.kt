package com.cleansweep.ui.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.navigation.navigation
import com.cleansweep.ui.screens.duplicates.DuplicatesScreen
import com.cleansweep.ui.screens.duplicates.GroupDetailsScreen
import com.cleansweep.ui.screens.duplicates.DuplicatesViewModel
import com.cleansweep.ui.screens.onboarding.OnboardingScreen
import com.cleansweep.ui.screens.osslicenses.OpenSourceLicensesScreen
import com.cleansweep.ui.screens.session.SessionSetupScreen
import com.cleansweep.ui.screens.session.SessionSetupViewModel
import com.cleansweep.ui.screens.settings.SettingsScreen
import com.cleansweep.ui.screens.swiper.SwiperScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// A constant for the new nested graph route.
const val DUPLICATES_GRAPH_ROUTE = "duplicates_graph"
private const val DEEP_LINK_URI_BASE = "app://com.cleansweep"

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object SessionSetup : Screen("session_setup?forceRefresh={forceRefresh}") {
        fun createRoute(forceRefresh: Boolean = false): String {
            return "session_setup?forceRefresh=$forceRefresh"
        }
    }
    object Swiper : Screen("swiper/{bucketIds}") {
        fun createRoute(bucketIds: List<String>): String {
            val encodedPaths = bucketIds.joinToString("|") { path ->
                // Using standard Base64 encoding for paths
                android.util.Base64.encodeToString(
                    path.toByteArray(StandardCharsets.UTF_8),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
            }
            return "swiper/${URLEncoder.encode(encodedPaths, StandardCharsets.UTF_8.toString())}"
        }
    }
    object Settings : Screen("settings")
    object Libraries: Screen("libraries") // New screen for licenses

    // Routes for the duplicates feature, now part of a nested graph
    object Duplicates : Screen("duplicates_overview")
    object GroupDetails : Screen("duplicates_group_details/{groupId}") {
        fun createRoute(groupId: String): String {
            // Encode the group ID to be safe for navigation
            val encodedGroupId = URLEncoder.encode(groupId, StandardCharsets.UTF_8.toString())
            return "duplicates_group_details/$encodedGroupId"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    windowSizeClass: WindowSizeClass,
    startDestination: String = Screen.Onboarding.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Screen.SessionSetup.createRoute(forceRefresh = true)) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.SessionSetup.route,
            arguments = listOf(navArgument("forceRefresh") {
                type = NavType.BoolType
                defaultValue = false
            })
        ) {
            val viewModel = hiltViewModel<SessionSetupViewModel>()
            SessionSetupScreen(
                windowSizeClass = windowSizeClass,
                onStartSession = { bucketIds ->
                    viewModel.saveSelectedBucketsPreference()
                    navController.navigate(Screen.Swiper.createRoute(bucketIds))
                },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                // Navigate to the start of the new duplicates graph
                onNavigateToDuplicates = { navController.navigate(DUPLICATES_GRAPH_ROUTE) },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Swiper.route,
            arguments = listOf(navArgument("bucketIds") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedBucketIds = backStackEntry.arguments?.getString("bucketIds") ?: ""
            val bucketIds = try {
                if (encodedBucketIds.isNotEmpty()) {
                    val decodedString = URLDecoder.decode(encodedBucketIds, StandardCharsets.UTF_8.toString())
                    decodedString.split("|").map { encodedPath ->
                        String(android.util.Base64.decode(encodedPath, android.util.Base64.URL_SAFE), StandardCharsets.UTF_8)
                    }.filter { it.isNotEmpty() }
                } else { emptyList() }
            } catch (e: Exception) { emptyList() }

            SwiperScreen(
                windowSizeClass = windowSizeClass,
                bucketIds = bucketIds,
                onNavigateUp = { navController.navigateUp() },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToDuplicates = { navController.navigate(DUPLICATES_GRAPH_ROUTE) }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateUp = { navController.navigateUp() },
                onNavigateToLibraries = { navController.navigate(Screen.Libraries.route) }
            )
        }

        composable(Screen.Libraries.route) {
            OpenSourceLicensesScreen(
                onNavigateUp = { navController.navigateUp() }
            )
        }

        // --- Duplicates Nested Navigation Graph ---
        navigation(
            startDestination = Screen.Duplicates.route,
            route = DUPLICATES_GRAPH_ROUTE
        ) {
            // Duplicates Overview Screen
            composable(
                route = Screen.Duplicates.route,
                deepLinks = listOf(
                    navDeepLink { uriPattern = "$DEEP_LINK_URI_BASE/$DUPLICATES_GRAPH_ROUTE" }
                )
            ) { navBackStackEntry ->
                val parentEntry = remember(navBackStackEntry) {
                    navController.getBackStackEntry(DUPLICATES_GRAPH_ROUTE)
                }
                val viewModel = hiltViewModel<DuplicatesViewModel>(parentEntry)

                DuplicatesScreen(
                    viewModel = viewModel,
                    onNavigateUp = { navController.navigateUp() },
                    onNavigateToGroup = { groupId ->
                        navController.navigate(Screen.GroupDetails.createRoute(groupId))
                    }
                )
            }

            // Group Details Screen
            composable(
                route = Screen.GroupDetails.route,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType })
            ) { navBackStackEntry ->
                val parentEntry = remember(navBackStackEntry) {
                    navController.getBackStackEntry(DUPLICATES_GRAPH_ROUTE)
                }
                val viewModel = hiltViewModel<DuplicatesViewModel>(parentEntry)
                val encodedGroupId = navBackStackEntry.arguments?.getString("groupId") ?: ""
                val groupId = URLDecoder.decode(encodedGroupId, StandardCharsets.UTF_8.toString())

                GroupDetailsScreen(
                    viewModel = viewModel,
                    groupId = groupId,
                    onNavigateUp = { navController.navigateUp() }
                )
            }
        }
    }
}