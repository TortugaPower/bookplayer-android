package com.tortugapower.audiobookplayer.wear.presentation

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.tortugapower.audiobookplayer.wear.R

/**
 * Entry point for the Wear OS app. Renders a tier-gated screen for each [WatchMode]: a working phone
 * sign-in handoff for [WatchMode.SIGN_IN], and placeholders for the per-mode experiences (remote
 * control, standalone playback) that arrive in later slices.
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
    val signInState by viewModel.signInState.collectAsStateWithLifecycle()
    MaterialTheme {
        // Each mode owns its Scaffold: the remote mode is a nav graph whose destinations bring their own
        // (with scroll indicators), while sign-in/standalone are simple single screens.
        when (mode) {
            WatchMode.SIGN_IN -> Scaffold(timeText = { TimeText() }) {
                SignInScreen(state = signInState, onSignIn = viewModel::signIn)
            }
            WatchMode.REMOTE_CONTROLLER -> RemoteNavHost()
            // Standalone (PRO): the full library hierarchy (folders navigable) with its own nav graph.
            WatchMode.STANDALONE -> StandaloneNavHost()
        }
    }
}

@Composable
private fun SignInScreen(state: SignInUiState, onSignIn: () -> Unit) {
    ScreenColumn {
        Text(
            text = stringResource(R.string.wear_sign_in_title),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.title3,
        )
        Spacer(Modifier.height(8.dp))
        if (state == SignInUiState.Loading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            // Visible + screen-reader feedback while the handoff is in flight (the bare spinner alone
            // carries no semantics for TalkBack — accessibility is first-class for this app).
            Text(
                text = stringResource(R.string.wear_signin_in_progress),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption1,
            )
        } else {
            Text(
                text = stringResource(R.string.wear_sign_in_body),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body2,
            )
            Spacer(Modifier.height(8.dp))
            Chip(
                onClick = onSignIn,
                label = { Text(stringResource(R.string.wear_signin_button)) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state is SignInUiState.Error) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(state.error.messageRes()),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.error,
                )
            }
        }
    }
}

private fun SignInError.messageRes(): Int = when (this) {
    SignInError.PHONE_NOT_REACHABLE -> R.string.wear_signin_error_not_reachable
    SignInError.PHONE_NOT_SIGNED_IN -> R.string.wear_signin_error_phone_not_signed_in
    SignInError.FAILED -> R.string.wear_signin_error_failed
}

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun SignInPreview() {
    MaterialTheme {
        SignInScreen(state = SignInUiState.Idle, onSignIn = {})
    }
}
