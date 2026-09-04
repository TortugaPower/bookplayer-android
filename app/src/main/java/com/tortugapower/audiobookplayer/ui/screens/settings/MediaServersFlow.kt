package com.tortugapower.audiobookplayer.ui.screens.settings

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tortugapower.audiobookplayer.logic.TaskAccessPolicy
import com.tortugapower.audiobookplayer.R
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.repository.ExternalLibraryRepository
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.ui.screens.auth.AuthSheet
import com.tortugapower.audiobookplayer.ui.screens.pro.LitePaywallSheet
import com.tortugapower.audiobookplayer.ui.screens.pro.StreamAndSyncSheet
import com.tortugapower.audiobookplayer.viewmodel.ExternalLibraryViewModel
import com.tortugapower.audiobookplayer.viewmodel.ExternalLibraryViewModelFactory
import com.tortugapower.audiobookplayer.viewmodel.ExternalServerViewModel
import com.tortugapower.audiobookplayer.viewmodel.ExternalServerViewModelFactory
import com.tortugapower.audiobookplayer.viewmodel.ImportViewModel
import com.tortugapower.audiobookplayer.ui.screens.settings.connection.ConnectionFlowSheet
import com.tortugapower.audiobookplayer.viewmodel.ConnectionFlowMode
import com.tortugapower.audiobookplayer.logic.ExternalServiceUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaServersFlow(
    externalServerRepository: ExternalServerRepository,
    externalLibraryRepository: ExternalLibraryRepository,
    importViewModel: ImportViewModel,
    onDismiss: () -> Unit,
    /** Opens the add-server flow for this integration right away (the "connect your server" prompt). */
    initialAddServerType: com.tortugapower.audiobookplayer.database.entities.ExternalServiceType? = null,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val externalServerViewModel: ExternalServerViewModel = viewModel(
        factory = ExternalServerViewModelFactory(externalServerRepository)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        modifier = Modifier.fillMaxSize()
    ) {
        NavHost(
            navController = navController,
            startDestination = "mediaServers",
            modifier = Modifier.fillMaxSize()
        ) {
            composable(
                route = "mediaServers",
                exitTransition = {
                    fadeOut(animationSpec = tween(300))
                },
                popEnterTransition = {
                    fadeIn(animationSpec = tween(300))
                }
            ) {
                MediaServersScreen(
                    viewModel = externalServerViewModel,
                    externalServerRepository = externalServerRepository,
                    initialAddServerType = initialAddServerType,
                    onBack = onDismiss,
                    onServerClick = { server ->
                        val encodedName = android.net.Uri.encode(server.name)
                        navController.navigate("externalLibraryFlow/${server.id}/$encodedName")
                    }
                )
            }

            navigation(
                route = "externalLibraryFlow/{serverId}/{serverName}",
                startDestination = "library",
            ) {
                composable(
                    route = "library",
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
                ) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("externalLibraryFlow/{serverId}/{serverName}") }
                    val serverId = parentEntry.arguments?.getString("serverId")?.toLong() ?: 0L
                    val rawServerName = parentEntry.arguments?.getString("serverName") ?: ""
                    val serverName = android.net.Uri.decode(rawServerName)

                    val extLibViewModel: ExternalLibraryViewModel = viewModel(
                        viewModelStoreOwner = parentEntry,
                        factory = ExternalLibraryViewModelFactory(serverId, externalServerRepository, externalLibraryRepository)
                    )

                    var showReauthSheet by remember { mutableStateOf(false) }
                    var showDetails by remember { mutableStateOf(false) }
                    val servers by externalServerViewModel.servers.collectAsState()
                    val liveServer = servers.find { it.id == serverId }

                    ExternalLibraryScreen(
                        viewModel = extLibViewModel,
                        importViewModel = importViewModel,
                        // The saved row's current name, so a rename from the details sheet shows at once.
                        serverName = liveServer?.name ?: serverName,
                        onBack = { navController.popBackStack() },
                        onItemClick = { item ->
                            navController.navigate("itemDetail/${item.entity.uuid}")
                        },
                        onActionStarted = onDismiss,
                        onReauthRequested = { showReauthSheet = true },
                        onShowConnectionDetails = { showDetails = true }
                    )

                    if (showDetails && liveServer != null) {
                        ServerInfoSheet(
                            server = liveServer,
                            onDismiss = { showDetails = false },
                            onRename = { name -> externalServerViewModel.renameServer(liveServer, name) },
                            onLogout = {
                                // Signing out is deletion (iOS): the connection this library describes
                                // no longer exists, so the library leaves with it.
                                externalServerViewModel.deleteServer(liveServer)
                                showDetails = false
                                navController.popBackStack()
                            }
                        )
                    }

                    if (showReauthSheet) {
                        val expiredServer = liveServer
                        if (expiredServer != null) {
                            // Same flow as Add Server, prefilled from the saved row (URL editable — a
                            // server that moved host updates its row instead of forking). The saved
                            // row is written before SignedIn fires, so the reload reads the new token.
                            ConnectionFlowSheet(
                                type = expiredServer.type,
                                mode = ConnectionFlowMode.Reauth(expiredServer),
                                externalServerRepository = externalServerRepository,
                                onDismiss = { showReauthSheet = false },
                                onSignedIn = {
                                    showReauthSheet = false
                                    extLibViewModel.retryAfterReauth()
                                },
                            )
                        }
                    }
                }

                composable(
                    route = "itemDetail/{itemUuid}",
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
                ) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("externalLibraryFlow/{serverId}/{serverName}") }
                    val serverId = parentEntry.arguments?.getString("serverId")?.toLong() ?: 0L
                    val itemUuid = backStackEntry.arguments?.getString("itemUuid") ?: ""

                    val extLibViewModel: ExternalLibraryViewModel = viewModel(
                        viewModelStoreOwner = parentEntry,
                        factory = ExternalLibraryViewModelFactory(serverId, externalServerRepository, externalLibraryRepository)
                    )

                    val serverItems: List<ExternalLibraryItem> by extLibViewModel.items.collectAsState()
                    val item = serverItems.find { it.entity.uuid == itemUuid }

                    if (item != null) {
                        val accountRepository = remember {
                            RoomAccountRepository(AppDatabase.getDatabase(context).accountDao())
                        }
                        var showLiteSheet by remember { mutableStateOf(false) }
                        var showLiteAuthSheet by remember { mutableStateOf(false) }
                        var showLitePaywall by remember { mutableStateOf(false) }
                        var showNoAudioAlert by remember { mutableStateOf(false) }
                        if (showNoAudioAlert) NoAudioFilesDialog(onDismiss = { showNoAudioAlert = false })

                        // Stage the item as a "virtual" import: it lands in the shared import
                        // sheet for confirmation, and only on accept is it created in the library
                        // (streamed via an external resource — no audio download).
                        fun stageStreamImport() {
                            val server = extLibViewModel.server
                            if (server == null) {
                                // The saved server row hasn't resolved (shouldn't happen once the
                                // library is loaded) — stream directly without importing.
                                PlaybackManager.playItem(context, item.entity, headers = item.customHeaders)
                                onDismiss()
                            } else {
                                scope.launch {
                                    // iOS parity: hydrate the REAL file extension first; an item the
                                    // server reports no audio file for is never guessed at.
                                    val selection = extLibViewModel.prepareStreamImport(listOf(item)) ?: return@launch
                                    if (selection.items.isEmpty()) {
                                        showNoAudioAlert = true
                                        return@launch
                                    }
                                    importViewModel.startStreamImport(
                                        context = context,
                                        items = selection.items,
                                        providerName = server.type.name.lowercase(),
                                        hostId = ExternalServiceUtils.stableHostId(server)
                                    )
                                    onDismiss()
                                }
                            }
                        }

                        // Shared by the intro sheet's Google button and the stacked passkey sheet
                        // (mirrors the BookPlayerProSheet host pattern).
                        fun onLiteAuthenticated(hasSubscription: Boolean) {
                            showLiteAuthSheet = false
                            showLiteSheet = false
                            if (hasSubscription) stageStreamImport() else showLitePaywall = true
                        }

                        if (showLiteSheet) {
                            StreamAndSyncSheet(
                                onDismiss = { showLiteSheet = false },
                                onPasskeyClick = { showLiteAuthSheet = true },
                                onAuthenticated = ::onLiteAuthenticated,
                                onSubscribed = {
                                    showLiteSheet = false
                                    stageStreamImport()
                                }
                            )
                        }

                        if (showLiteAuthSheet) {
                            AuthSheet(
                                onDismiss = { showLiteAuthSheet = false },
                                onAuthenticated = ::onLiteAuthenticated
                            )
                        }

                        if (showLitePaywall) {
                            LitePaywallSheet(
                                onDismiss = { showLitePaywall = false },
                                onSubscribed = { stageStreamImport() }
                            )
                        }

                        ExternalItemDetailScreen(
                            item = item,
                            onBack = { navController.popBackStack() },
                            onStreamClick = {
                                scope.launch {
                                    val tier = accountRepository.getAccount()?.tier
                                    if (TaskAccessPolicy.canStreamExternalLibraries(tier)) {
                                        stageStreamImport()
                                    } else {
                                        showLiteSheet = true
                                    }
                                }
                            },
                            onDownloadClick = {
                                scope.launch {
                                    val url = extLibViewModel.getStreamUrl(item.entity)
                                    if (url.isNotBlank()) {
                                        val fileName = item.entity.originalFileName ?: "${item.entity.title}.mp3"
                                        importViewModel.startDownload(
                                            context = context,
                                            url = url,
                                            fileName = fileName,
                                            headers = item.customHeaders,
                                            providerName = extLibViewModel.server?.type?.name?.lowercase(),
                                            providerId = item.entity.uuid,
                                            hostId = extLibViewModel.server?.let { ExternalServiceUtils.stableHostId(it) }
                                        )
                                        onDismiss()
                                    }
                                }
                            }                        )
                    } else {
                        val isLoading by extLibViewModel.isLoading.collectAsState()
                        val isLastPage by extLibViewModel.isLastPage.collectAsState()

                        // The tapped item may live on a page that isn't loaded yet (e.g. this route
                        // was restored after process death, so the ViewModel restarted at page 0).
                        // Keep paging until it appears; once the last page is in and it still isn't
                        // there, fall through to the not-found state instead of spinning forever.
                        LaunchedEffect(serverItems.size, isLoading, isLastPage) {
                            if (!isLoading && !isLastPage) {
                                extLibViewModel.loadMore()
                            }
                        }

                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = {},
                                    navigationIcon = {
                                        IconButton(onClick = { navController.popBackStack() }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = stringResource(id = R.string.common_back)
                                            )
                                        }
                                    }
                                )
                            }
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier.fillMaxSize().padding(innerPadding),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLastPage) {
                                    Text(stringResource(id = R.string.external_library_no_results_found))
                                } else {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
