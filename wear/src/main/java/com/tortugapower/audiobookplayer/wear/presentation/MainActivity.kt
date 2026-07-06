package com.tortugapower.audiobookplayer.wear.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import com.tortugapower.audiobookplayer.wear.R

/**
 * Entry point for the Wear OS app. This slice is a build-only scaffold: it renders a placeholder so the
 * module compiles and installs on a watch. The sign-in handoff (Data Layer) and the tier-gated mode UI
 * (pro = standalone / else = phone remote) arrive in later slices; see the phase-1 plan.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
    }
}

@Composable
fun WearApp() {
    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.wear_scaffold_placeholder))
            }
        }
    }
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
fun WearAppPreview() {
    WearApp()
}
