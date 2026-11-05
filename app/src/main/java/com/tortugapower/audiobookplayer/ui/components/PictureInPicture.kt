package com.tortugapower.audiobookplayer.ui.components

import androidx.compose.runtime.compositionLocalOf

/** True while the activity is showing as a Picture-in-Picture window: the player renders only the video. */
val LocalIsInPictureInPicture = compositionLocalOf { false }
