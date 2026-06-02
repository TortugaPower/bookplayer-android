package com.tortugapower.audiobookplayer.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncingLibraryRepository
import com.tortugapower.audiobookplayer.ui.components.CustomBottomNavigation
import com.tortugapower.audiobookplayer.ui.components.MiniPlayer
import com.tortugapower.audiobookplayer.ui.components.Screen
import com.tortugapower.audiobookplayer.viewmodel.*

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val importViewModel: ImportViewModel = viewModel()
    
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val baseLibraryRepository = remember { RoomLibraryRepository(database.libraryDao()) }
    val syncTaskRepository = remember { RoomSyncTaskRepository(database.syncTaskDao()) }
    val accountRepository = remember { RoomAccountRepository(database.accountDao()) }
    
    val libraryRepository = remember { 
        SyncingLibraryRepository(baseLibraryRepository, syncTaskRepository, accountRepository)
    }

    val playerViewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModelFactory(libraryRepository)
    )

    val profileViewModel: ProfileViewModel = viewModel(
        factory = ProfileViewModelFactory(accountRepository, syncTaskRepository)
    )

    val libraryViewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(libraryRepository, syncTaskRepository)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.semantics {
                if (PlaybackManager.showPlayerScreen) {
                    hideFromAccessibility()
                    // More aggressive approach for some versions of TalkBack
                    // by ensuring it's not a traversal group
                }
            }.then(
                if (PlaybackManager.showPlayerScreen) {
                    Modifier.clearAndSetSemantics { }
                } else Modifier
            ),
            containerColor = MaterialTheme.colorScheme.background,
            // Each tab destination owns its own top bar / LargeTopAppBar, so the outer
            // Scaffold hands top-inset duty to the inner Scaffolds.
            contentWindowInsets = WindowInsets.systemBars.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
            ),
            bottomBar = {
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
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    composable(Screen.Library.route) { LibraryScreen(viewModel = libraryViewModel) }
                    composable(Screen.Profile.route) { 
                        ProfileScreen(
                            viewModel = profileViewModel,
                            onNavigateToAccountDetails = { navController.navigate("accountDetails") },
                            onNavigateToQueuedTasks = { navController.navigate("queuedTasks") }
                        ) 
                    }
                    composable("accountDetails") {
                        AccountDetailsScreen(
                            viewModel = profileViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("queuedTasks") {
                        QueuedTasksScreen(
                            viewModel = profileViewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToQueue = { queueKey -> navController.navigate("taskDetail/$queueKey") }
                        )
                    }
                    composable("taskDetail/{queueKey}") { backStackEntry ->
                        val queueKey = backStackEntry.arguments?.getString("queueKey") ?: ""
                        TaskDetailScreen(
                            viewModel = profileViewModel,
                            queueKey = queueKey,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    
                    composable(
                        route = Screen.Settings.route,
                        exitTransition = {
                            if (targetState.destination.route == "themes") {
                                slideOutOfContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Left,
                                    animationSpec = tween(400)
                                )
                            } else null
                        },
                        popEnterTransition = {
                            if (initialState.destination.route == "themes") {
                                slideIntoContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Right,
                                    animationSpec = tween(400)
                                )
                            } else null
                        }
                    ) { 
                        SettingsScreen(onNavigateToThemes = { navController.navigate("themes") }) 
                    }
                    
                    composable(
                        route = "themes",
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(400)
                            )
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(400)
                            )
                        },
                        popEnterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(400)
                            )
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(400)
                            )
                        }
                    ) {
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

        PlayerScreen(viewModel = playerViewModel)
    }
}
