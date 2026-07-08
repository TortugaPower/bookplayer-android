package com.tortugapower.audiobookplayer.wear.presentation

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Box
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/**
 * Entry point for the Wear OS app. Routes by [WatchMode]: a PRO account gets the standalone on-watch
 * experience; everyone else (including a signed-out watch) gets the phone remote-controller, with sign-in
 * living behind Settings there rather than as a launch wall.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearRoot() }
    }
}

@Composable
fun WearRoot(
    viewModel: WearRootViewModel = viewModel(
        factory = WearRootViewModelFactory(LocalContext.current.applicationContext as Application),
    ),
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    // Adopt the user's phone-selected theme once it syncs; default Wear palette until then.
    val watchTheme by viewModel.theme.collectAsStateWithLifecycle()
    val colors = remember(watchTheme) { watchTheme?.toWearColors() ?: Colors() }
    MaterialTheme(colors = colors) {
        // Themed root fill so the swipe-to-dismiss reveal (and cold-start) shows the theme background, not
        // the window's default black, before a destination's Scaffold repaints during the transition.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
            // Each mode owns a nav graph whose destinations bring their own Scaffold (with scroll indicators).
            when (mode) {
                WatchMode.REMOTE_CONTROLLER -> RemoteNavHost(rootViewModel = viewModel)
                WatchMode.STANDALONE -> StandaloneNavHost(rootViewModel = viewModel)
            }
        }
    }
}
