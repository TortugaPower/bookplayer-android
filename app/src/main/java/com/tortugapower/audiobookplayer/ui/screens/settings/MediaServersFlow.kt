package com.tortugapower.audiobookplayer.ui.screens.settings

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
                        navController.navigate("externalLibrary/${server.id}/$encodedName")
                    }
                )
            }

            composable(
                route = "externalLibrary/{serverId}/{serverName}",
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
                val serverId = backStackEntry.arguments?.getString("serverId")?.toLong() ?: 0L
                val rawServerName = backStackEntry.arguments?.getString("serverName") ?: ""
                val serverName = android.net.Uri.decode(rawServerName)
                
                val extLibViewModel: ExternalLibraryViewModel = viewModel(
                    factory = ExternalLibraryViewModelFactory(serverId, externalServerRepository, externalLibraryRepository)
                )
                
                ExternalLibraryScreen(
                    viewModel = extLibViewModel,
                    importViewModel = importViewModel,
                    serverName = serverName,
                    onBack = { navController.popBackStack() },
                    onItemClick = { item ->
                        navController.navigate("externalItemDetail/$serverId/${item.entity.uuid}")
                    },
                    onActionStarted = onDismiss
                )
            }

            composable(
                route = "externalItemDetail/{serverId}/{itemUuid}",
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
                val serverId = backStackEntry.arguments?.getString("serverId")?.toLong() ?: 0L
                val itemUuid = backStackEntry.arguments?.getString("itemUuid") ?: ""
                
                val extLibViewModel: ExternalLibraryViewModel = viewModel(
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
                                val fileName = item.entity.originalFileName ?: "${item.entity.title}.mp3"
                                importViewModel.startDownload(context, url, fileName, item.customHeaders)
                                onDismiss()
                            }
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
