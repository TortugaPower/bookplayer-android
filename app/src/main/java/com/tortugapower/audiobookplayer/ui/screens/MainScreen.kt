package com.tortugapower.audiobookplayer.ui.screens

import android.app.Application
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.repository.SyncingLibraryRepository
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.ui.components.CustomBottomNavigation
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import com.tortugapower.audiobookplayer.ui.components.MiniPlayer
import com.tortugapower.audiobookplayer.ui.components.MiniPlayerBarHeight
import com.tortugapower.audiobookplayer.ui.components.Screen
import com.tortugapower.audiobookplayer.ui.screens.account.AccountDetailsScreen
import com.tortugapower.audiobookplayer.ui.screens.appicons.AppIconsScreen
import com.tortugapower.audiobookplayer.ui.screens.library.ImportSheet
import com.tortugapower.audiobookplayer.ui.screens.library.LibraryScreen
import com.tortugapower.audiobookplayer.ui.screens.player.PlayerScreen
import com.tortugapower.audiobookplayer.ui.screens.profile.ProfileScreen
import com.tortugapower.audiobookplayer.ui.screens.profile.StatisticsScreen
import com.tortugapower.audiobookplayer.ui.screens.profile.ListeningHistoryScreen
import com.tortugapower.audiobookplayer.ui.screens.settings.SettingsScreen
import com.tortugapower.audiobookplayer.ui.screens.settings.MediaServersFlow
import com.tortugapower.audiobookplayer.ui.screens.settings.HardcoverSettingsScreen
import com.tortugapower.audiobookplayer.ui.screens.settings.StorageManagementScreen
import com.tortugapower.audiobookplayer.ui.screens.settings.StorageCloudDeletedScreen
import com.tortugapower.audiobookplayer.repository.ExternalLibraryRepository
import com.tortugapower.audiobookplayer.ui.screens.synctasks.QueuedTasksScreen
import com.tortugapower.audiobookplayer.ui.screens.synctasks.TaskDetailScreen
import com.tortugapower.audiobookplayer.ui.screens.themes.ThemesScreen
import com.tortugapower.audiobookplayer.ui.screens.tipjar.TipJarScreen
import com.tortugapower.audiobookplayer.viewmodel.*

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val importViewModel: ImportViewModel = viewModel()

    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val baseLibraryRepository = remember { RoomLibraryRepository(context.applicationContext, database.libraryDao()) }
    val syncTaskRepository = remember { RoomSyncTaskRepository(database.syncTaskDao()) }
    val accountRepository = remember { RoomAccountRepository(database.accountDao()) }
    val externalServerRepository = remember { ExternalServerRepository(database.externalServerDao()) }
    val externalLibraryRepository = remember { ExternalLibraryRepository() }
    
    val libraryRepository = remember { 
        SyncingLibraryRepository(baseLibraryRepository, syncTaskRepository, accountRepository)
    }

    val playerViewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModelFactory(context.applicationContext as Application, libraryRepository)
    )

    val showPlayerScreen by PlaybackManager.showPlayerScreen.collectAsStateWithLifecycle()
    val currentPlaybackItem by PlaybackManager.currentItem.collectAsStateWithLifecycle()

    // A mid-playback 401/403 from an external server: plain error alert, no re-auth routing
    // (the user re-authenticates from Media Servers).
    val externalStreamAuthError by PlaybackManager.externalStreamAuthError.collectAsStateWithLifecycle()
    if (externalStreamAuthError) {
        AlertDialog(
            onDismissRequest = { PlaybackManager.clearExternalStreamAuthError() },
            title = { Text(stringResource(id = R.string.common_error)) },
            text = { Text(stringResource(id = R.string.playback_error_session_expired)) },
            confirmButton = {
                TextButton(onClick = { PlaybackManager.clearExternalStreamAuthError() }) {
                    Text(stringResource(id = R.string.common_ok))
                }
            }
        )
    }

    val playbackError by PlaybackManager.playbackError.collectAsStateWithLifecycle()
    playbackError?.let { errorMessage ->
        AlertDialog(
            onDismissRequest = { PlaybackManager.clearPlaybackError() },
            title = { Text(stringResource(id = R.string.common_error)) },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { PlaybackManager.clearPlaybackError() }) {
                    Text(stringResource(id = R.string.common_ok))
                }
            }
        )
    }

    val profileViewModel: ProfileViewModel = viewModel(
        factory = ProfileViewModelFactory(
            accountRepository,
            syncTaskRepository,
            database.statisticsDao(),
            database.libraryDao()
        )
    )

    val statisticsViewModel: StatisticsViewModel = viewModel(
        factory = StatisticsViewModelFactory(
            database.statisticsDao(),
            database.libraryDao()
        )
    )

    val libraryViewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(context.applicationContext as Application, libraryRepository, syncTaskRepository)
    )



    var showMediaServersFlow by remember { mutableStateOf(false) }

    // Cold-start readiness (first local library load) is handled by MainActivity holding the OS splash
    // (setKeepOnScreenCondition on libraryViewModel.isReady): the real splash stays up until the library
    // has data, so no in-app loading replica — and no icon-size jump at the hand-off — is needed here.

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // While the full player is shown over everything, strip the tab UI (incl. the floating
            // mini player) from the TalkBack tree so focus stays within the player. clearAndSetSemantics
            // already clears the whole subtree, so no separate hideFromAccessibility() is needed.
            modifier = if (showPlayerScreen) {
                Modifier.clearAndSetSemantics { }
            } else Modifier,
            containerColor = MaterialTheme.colorScheme.background,
            // Each tab destination owns its own top bar / LargeTopAppBar, so the outer
            // Scaffold hands top-inset duty to the inner Scaffolds.
            contentWindowInsets = WindowInsets.systemBars.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
            ),
            bottomBar = {
                if (currentRoute != "themes" && currentRoute != "tipjar" && currentRoute != "appicons") {
                    // Mini player is no longer docked here — it floats over content (below).
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
        ) { innerPadding ->
            // The floating mini player is shown over content when a book is loaded (and not on
            // the full-screen themes route). Screens read LocalMiniPlayerInset to reserve bottom
            // space so their scroll content clears the pill while still scrolling behind it.
            val miniPlayerVisible = currentPlaybackItem != null && currentRoute != "themes" && currentRoute != "tipjar" && currentRoute != "appicons"

            // Consume the root insets so the per-screen nested Scaffolds (tab chrome,
            // Account Details) don't re-apply the bottom navigation-bar inset on top of
            // the bottomBar we already reserve here.
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
              CompositionLocalProvider(
                  LocalMiniPlayerInset provides if (miniPlayerVisible) MiniPlayerBarHeight else 0.dp
              ) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Library.route,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    composable(Screen.Library.route) { 
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            importViewModel = importViewModel,
                            onNavigateToMediaServers = { showMediaServersFlow = true }
                        ) 
                    }
                    composable(Screen.Profile.route) { 
                        ProfileScreen(
                            viewModel = profileViewModel,
                            onNavigateToAccountDetails = { navController.navigate("accountDetails") },
                            onNavigateToQueuedTasks = { navController.navigate("queuedTasks") },
                            onNavigateToStatistics = { navController.navigate("statistics") },
                            onNavigateToHistory = { navController.navigate("listeningHistory") }
                        ) 
                    }
                    composable("statistics") {
                        StatisticsScreen(
                            viewModel = statisticsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("listeningHistory") {
                        ListeningHistoryScreen(
                            viewModel = statisticsViewModel,
                            onBack = { navController.popBackStack() }
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
                            if (targetState.destination.route in setOf("themes", "tipjar", "appicons", "hardcoverSettings", "storageManagement", "storageCloudDeleted")) {
                                slideOutOfContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Left,
                                    animationSpec = tween(400)
                                )
                            } else null
                        },
                        popEnterTransition = {
                            if (initialState.destination.route in setOf("themes", "tipjar", "appicons", "hardcoverSettings", "storageManagement", "storageCloudDeleted")) {
                                slideIntoContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Right,
                                    animationSpec = tween(400)
                                )
                            } else null
                        }
                    ) { 
                        SettingsScreen(
                            onNavigateToThemes = { navController.navigate("themes") },
                            onNavigateToAppIcons = { navController.navigate("appicons") },
                            onNavigateToTipJar = { navController.navigate("tipjar") },
                            onNavigateToMediaServers = { showMediaServersFlow = true },
                            onNavigateToHardcover = { navController.navigate("hardcoverSettings") },
                            onNavigateToStorageManagement = { navController.navigate("storageManagement") },
                            onNavigateToStorageCloudDeleted = { navController.navigate("storageCloudDeleted") }
                        ) 
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
                            fadeOut(animationSpec = tween(400))
                        },
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(400))
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

                    composable(
                        route = "tipjar",
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
                        TipJarScreen(onBack = { navController.popBackStack() })
                    }

                    composable(
                        route = "appicons",
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
                        AppIconsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(
                        route = "hardcoverSettings",
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(400)
                            )
                        },
                        exitTransition = {
                            fadeOut(animationSpec = tween(400))
                        },
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(400))
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(400)
                            )
                        }
                    ) {
                        HardcoverSettingsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(
                        route = "storageManagement",
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(400)
                            )
                        },
                        exitTransition = {
                            fadeOut(animationSpec = tween(400))
                        },
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(400))
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(400)
                            )
                        }
                    ) {
                        StorageManagementScreen(onBack = { navController.popBackStack() })
                    }

                    composable(
                        route = "storageCloudDeleted",
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(400)
                            )
                        },
                        exitTransition = {
                            fadeOut(animationSpec = tween(400))
                        },
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(400))
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(400)
                            )
                        }
                    ) {
                        StorageCloudDeletedScreen(onBack = { navController.popBackStack() })
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

                if (showMediaServersFlow) {
                    MediaServersFlow(
                        externalServerRepository = externalServerRepository,
                        externalLibraryRepository = externalLibraryRepository,
                        importViewModel = importViewModel,
                        onDismiss = { showMediaServersFlow = false }
                    )
                }
              }

// Floating mini player overlay — drawn over content, bottom-aligned (just above
                // the docked bottom nav). Hidden on the full-screen themes route.
                if (miniPlayerVisible) {
                    MiniPlayer(modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }

        PlayerScreen(viewModel = playerViewModel)
    }
}
