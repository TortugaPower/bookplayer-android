package com.tortugapower.audiobookplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.ui.components.CustomBottomNavigation
import com.tortugapower.audiobookplayer.ui.components.MiniPlayer
import com.tortugapower.audiobookplayer.ui.components.Screen
import com.tortugapower.audiobookplayer.viewmodel.ImportViewModel

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val importViewModel: ImportViewModel = viewModel()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                // Hide bottom bar when in Themes screen to match iOS look
                if (currentRoute != "themes") {
                    Column {
                        if (PlaybackManager.currentItem != null) {
                            MiniPlayer()
                        }
                        CustomBottomNavigation(
                            currentRoute = currentRoute,
                            onTabSelected = { screen ->
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Library.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Screen.Library.route) { LibraryScreen() }
                    composable(Screen.Profile.route) { ProfileScreen() }
                    composable(Screen.Settings.route) { 
                        SettingsScreen(onNavigateToThemes = { navController.navigate("themes") }) 
                    }
                    composable("themes") {
                        ThemesScreen(onBack = { navController.popBackStack() })
                    }
                }

                if (importViewModel.isImporting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                if (importViewModel.showImportSheet) {
                    ImportSheet(importViewModel)
                }
            }
        }

        // PlayerScreen handles its own visibility and animations internally now
        PlayerScreen()
    }
}
