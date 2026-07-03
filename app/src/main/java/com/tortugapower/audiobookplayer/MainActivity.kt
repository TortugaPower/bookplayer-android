package com.tortugapower.audiobookplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        splashScreen.setKeepOnScreenCondition {
            !ThemeManager.isReady
        }

        enableEdgeToEdge()
        ThemeManager.initialize(this)

        // Only handle the launch intent on a fresh start. On a recreate (locale/density change, or
        // process-death restore from recents) the same ACTION_VIEW/SEND intent would otherwise be
        // replayed and re-trigger the import. onNewIntent covers warm re-deliveries while running.
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

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
        if (intent == null) return
        val uri = intent.data
        when {
            uri != null && uri.scheme == "bookplayer" -> handleDeepLink(uri)
            // "Open with" an audio file shared from another app: copy the shared URI into the import
            // flow while this activity holds the transient read grant (ImportManager shows the sheet).
            intent.action == Intent.ACTION_VIEW && uri != null &&
                (uri.scheme == "content" || uri.scheme == "file") ->
                com.tortugapower.audiobookplayer.logic.ImportManager.startImport(this, listOf(uri))
            // "Share" a single audio file to BookPlayer — the file rides in EXTRA_STREAM.
            intent.action == Intent.ACTION_SEND -> {
                val shared = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (shared != null) {
                    com.tortugapower.audiobookplayer.logic.ImportManager.startImport(this, listOf(shared))
                }
            }
            // "Share" multiple audio files to BookPlayer at once.
            intent.action == Intent.ACTION_SEND_MULTIPLE -> {
                val shared = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!shared.isNullOrEmpty()) {
                    com.tortugapower.audiobookplayer.logic.ImportManager.startImport(this, shared)
                }
            }
            intent.getBooleanExtra("OPEN_PLAYER", false) -> PlaybackManager.setShowPlayer(true)
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
                    if (showPlayer) PlaybackManager.setShowPlayer(true)
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
                    com.tortugapower.audiobookplayer.logic.SleepTimerManager.configureTimerWithSeconds(seconds)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // We don't necessarily want to release the player here if it should play in background,
        // but for now let's keep it simple. Actually, the service handles the background.
    }
}
