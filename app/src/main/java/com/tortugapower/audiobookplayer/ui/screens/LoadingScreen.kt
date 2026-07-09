package com.tortugapower.audiobookplayer.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R

/**
 * Full-screen launch/loading placeholder shown while the app completes its first (local) data load, so the
 * library never flashes its empty state on a cold start. Visually mirrors the Android 12 splash — same
 * background (`splash_background`), white circular icon background (`ic_launcher_background`), and
 * `app_logo` — so the hand-off from the native splash is seamless. Android analog of iOS's
 * LoadingViewController shown while services are wired up.
 */
@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.splash_background)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(colorResource(R.color.ic_launcher_background)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier.size(80.dp),
            )
        }
    }
}
