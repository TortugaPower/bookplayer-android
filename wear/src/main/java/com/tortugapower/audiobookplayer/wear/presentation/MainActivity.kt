package com.tortugapower.audiobookplayer.wear.presentation

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
    MaterialTheme {
        // Each mode owns a nav graph whose destinations bring their own Scaffold (with scroll indicators).
        when (mode) {
            WatchMode.REMOTE_CONTROLLER -> RemoteNavHost(rootViewModel = viewModel)
            WatchMode.STANDALONE -> StandaloneNavHost(rootViewModel = viewModel)
        }
    }
}
