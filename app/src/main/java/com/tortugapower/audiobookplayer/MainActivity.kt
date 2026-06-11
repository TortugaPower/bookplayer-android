package com.tortugapower.audiobookplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.TaskConcurrencyServiceHost
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.logic.ThemeManager
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.ui.screens.MainScreen
import com.tortugapower.audiobookplayer.ui.theme.BookPlayerTheme

class MainActivity : ComponentActivity() {
    companion object {
        var currentContext: android.content.Context? = null
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        currentContext = this
        
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        splashScreen.setKeepOnScreenCondition {
            !ThemeManager.isReady
        }

        enableEdgeToEdge()
        ThemeManager.initialize(this)

        handleIntent(intent)

        setContent {
            BookPlayerTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri != null && uri.scheme == "bookplayer") {
            handleDeepLink(uri)
        } else if (intent?.getBooleanExtra("OPEN_PLAYER", false) == true) {
            PlaybackManager.showPlayerScreen = true
        }
    }

    private fun handleDeepLink(uri: android.net.Uri) {
        when (uri.host) {
            "play" -> {
                val showPlayer = uri.getQueryParameter("showPlayer")?.toBoolean() ?: true
                val autoplay = uri.getQueryParameter("autoplay")?.toBoolean() ?: true
                val identifier = uri.getQueryParameter("identifier")
                
                if (identifier != null) {
                    PlaybackManager.playItemByPath(this, identifier, autoplay, showPlayer)
                } else {
                    if (autoplay) PlaybackManager.togglePlayPause()
                    if (showPlayer) PlaybackManager.showPlayerScreen = true
                }
            }
            "download" -> {
                val downloadUrl = uri.getQueryParameter("url")
                if (downloadUrl != null) {
                    // Remove quotes if present
                    val cleanUrl = downloadUrl.replace("\"", "")
                    com.tortugapower.audiobookplayer.logic.ImportManager.startImport(this, listOf(android.net.Uri.parse(cleanUrl)))
                }
            }
            "skipRewind" -> {
                PlaybackManager.seekBackward()
            }
            "skipForward" -> {
                PlaybackManager.seekForward()
            }
            "sleep" -> {
                val seconds = uri.getQueryParameter("seconds")?.toIntOrNull()
                if (seconds != null) {
                    com.tortugapower.audiobookplayer.logic.SleepTimerManager.configureTimerWithSeconds(this, seconds)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        currentContext = this
    }

    override fun onPause() {
        super.onPause()
        if (currentContext == this) {
            currentContext = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // We don't necessarily want to release the player here if it should play in background,
        // but for now let's keep it simple. Actually, the service handles the background.
    }
}
