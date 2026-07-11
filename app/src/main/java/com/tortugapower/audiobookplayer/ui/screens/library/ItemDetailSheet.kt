package com.tortugapower.audiobookplayer.ui.screens.library

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.HardcoverSettingsManager
import com.tortugapower.audiobookplayer.network.HardcoverBook
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModel
import kotlinx.coroutines.flow.first

enum class DetailSheetState {
    DETAILS, BROWSER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailSheet(
    item: LibraryItemEntity,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(item.title) }
    // Containers store a bare child count as `author` — show the localized form ("3 Chapters"), not "3".
    var author by remember {
        mutableStateOf(
            com.tortugapower.audiobookplayer.logic.LibraryContentsSync.displayDetails(context, item.type, item.author) ?: ""
        )
    }
    var artworkURL by remember { mutableStateOf(item.artworkURL) }
    
    val navController = rememberNavController()
    var linkedBook by remember { mutableStateOf<HardcoverBook?>(null) }
    var initialBookId by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        token = HardcoverSettingsManager.getToken(context).first()
    }

    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            val resource = viewModel.getExternalResource(item.uuid, "hardcover")
            if (resource != null) {
                val book = com.tortugapower.audiobookplayer.network.HardcoverService.getBook(token, resource.providerId)
                if (book != null) {
                    linkedBook = book
                    initialBookId = book.id
                }
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        NavHost(
            navController = navController,
            startDestination = "details",
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            composable(
                route = "details",
                exitTransition = {
                    slideOutOfContainer(
                        AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(400)
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(400)
                    )
                }
            ) {
                ItemDetailsContent(
                    item = item,
                    viewModel = viewModel,
                    title = title,
                    onTitleChange = { title = it },
                    author = author,
                    onAuthorChange = { author = it },
                    artworkURL = artworkURL,
                    onUpdateArtwork = { uri ->
                        viewModel.updateArtwork(context, item, uri)
                        artworkURL = uri.toString()
                    },
                    onDeleteArtwork = {
                        viewModel.deleteArtwork(item)
                        artworkURL = null
                    },
                    linkedBook = linkedBook,
                    initialBookId = initialBookId,
                    onNavigateToBrowser = {
                        navController.navigate("browser")
                    },
                    onDismiss = onDismiss
                )
            }
            composable(
                route = "browser",
                enterTransition = {
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
                HardcoverBrowser(
                    token = token,
                    initialQuery = com.tortugapower.audiobookplayer.network.HardcoverService.buildSearchString(title, author),
                    linkedBook = linkedBook,
                    onBookSelected = { book ->
                        linkedBook = book
                        if (book != null) {
                            title = book.title
                            author = book.getAuthorName()
                        }
                        navController.popBackStack()
                    },
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
