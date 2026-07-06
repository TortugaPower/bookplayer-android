package com.tortugapower.audiobookplayer.wear.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.tortugapower.audiobookplayer.wear.R

/**
 * Entry point for the Wear OS app. Renders a tier-gated placeholder for each [WatchMode]; the real
 * per-mode experiences (remote control, standalone playback) and the sign-in handoff arrive in later
 * slices. See the phase-1 plan.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearRoot() }
    }
}

@Composable
fun WearRoot(viewModel: WearRootViewModel = viewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            when (mode) {
                WatchMode.SIGN_IN -> MessageScreen(
                    title = stringResource(R.string.wear_sign_in_title),
                    body = stringResource(R.string.wear_sign_in_body),
                )
                WatchMode.REMOTE_CONTROLLER -> MessageScreen(
                    title = stringResource(R.string.wear_mode_remote_title),
                    body = stringResource(R.string.wear_mode_remote_body),
                )
                WatchMode.STANDALONE -> MessageScreen(
                    title = stringResource(R.string.wear_mode_standalone_title),
                    body = stringResource(R.string.wear_mode_standalone_body),
                )
            }
        }
    }
}

@Composable
private fun MessageScreen(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.title3,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.body2,
        )
    }
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun SignInPreview() {
    MaterialTheme {
        MessageScreen(title = "Sign in on your phone", body = "Open BookPlayer on your phone to continue.")
    }
}
