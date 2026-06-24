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
import com.tortugapower.audiobookplayer.network.ConnectionResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tortugapower.audiobookplayer.R
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.repository.ExternalLibraryRepository
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.viewmodel.ExternalLibraryViewModel
import com.tortugapower.audiobookplayer.viewmodel.ExternalLibraryViewModelFactory
import com.tortugapower.audiobookplayer.viewmodel.ExternalServerViewModel
import com.tortugapower.audiobookplayer.viewmodel.ExternalServerViewModelFactory
import com.tortugapower.audiobookplayer.viewmodel.ImportViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaServersFlow(
    externalServerRepository: ExternalServerRepository,
    externalLibraryRepository: ExternalLibraryRepository,
    importViewModel: ImportViewModel,
    onDismiss: () -> Unit
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

                    ExternalLibraryScreen(
                        viewModel = extLibViewModel,
                        importViewModel = importViewModel,
                        serverName = serverName,
                        onBack = { navController.popBackStack() },
                        onItemClick = { item ->
                            navController.navigate("itemDetail/${item.entity.uuid}")
                        },
                        onActionStarted = onDismiss,
                        onReauthRequested = { showReauthSheet = true }
                    )

                    if (showReauthSheet) {
                        val servers by externalServerViewModel.servers.collectAsState()
                        val expiredServer = servers.find { it.id == serverId }
                        if (expiredServer != null) {
                            var isConnecting by remember { mutableStateOf(false) }
                            var connectionError by remember { mutableStateOf<ConnectionResult.Failure?>(null) }
                            val errorDisplayMessage = connectionError?.let { error ->
                                error.messageResId?.let { resId ->
                                    stringResource(id = resId, *(error.args?.toTypedArray() ?: emptyArray()))
                                } ?: error.message
                            }

                            AddServerSheet(
                                type = expiredServer.type,
                                isConnecting = isConnecting,
                                errorMessage = errorDisplayMessage,
                                initialUrl = expiredServer.url,
                                initialUsername = expiredServer.username.orEmpty(),
                                initialHeaders = expiredServer.customHeaders,
                                lockUrl = true,
                                onDismiss = {
                                    showReauthSheet = false
                                    connectionError = null
                                },
                                onConnect = { name, url, username, password, headers ->
                                    scope.launch {
                                        isConnecting = true
                                        connectionError = null
                                        val result = externalServerViewModel.testConnection(expiredServer.type, url, username, password, headers)
                                        isConnecting = false

                                        when (result) {
                                            is ConnectionResult.Success -> {
                                                // The canonical-URL+username dedup replaces the
                                                // existing row (same id, selectedLibraryId kept).
                                                // join() so the reload below reads the new token.
                                                externalServerViewModel
                                                    .addServer(result.name ?: name, expiredServer.type, url, username, result.token, headers)
                                                    .join()
                                                showReauthSheet = false
                                                extLibViewModel.retryAfterReauth()
                                            }
                                            is ConnectionResult.Failure -> {
                                                connectionError = result
                                            }
                                        }
                                    }
                                }
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
                        ExternalItemDetailScreen(
                            item = item,
                            onBack = { navController.popBackStack() },
                            onStreamClick = {
                                PlaybackManager.playItem(context, item.entity, headers = item.customHeaders)
                                onDismiss()
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
                                            hostId = extLibViewModel.server?.id?.toString()
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
