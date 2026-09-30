package com.tortugapower.audiobookplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.TaskConcurrencyServiceHost
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.logic.ThemeManager
import androidx.lifecycle.ViewModelProvider
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModel
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModelFactory
import com.tortugapower.audiobookplayer.ui.screens.MainScreen
import com.tortugapower.audiobookplayer.ui.components.StorageFullScreen
import com.tortugapower.audiobookplayer.ui.components.openStorageSettings
import com.tortugapower.audiobookplayer.logic.StorageMonitor
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tortugapower.audiobookplayer.ui.theme.BookPlayerTheme
import com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager
import com.tortugapower.audiobookplayer.logic.PlayerUiSignals
import com.tortugapower.audiobookplayer.logic.ShortcutHelper
import com.tortugapower.audiobookplayer.logic.SleepTimerManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.tortugapower.audiobookplayer.logic.PictureInPicturePolicy
import com.tortugapower.audiobookplayer.ui.components.LocalIsInPictureInPicture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

class MainActivity : ComponentActivity() {

    /** True while the storage gate is showing instead of the app (see onCreate). */
    private var storageGateShown = false

    // --- Video playback settings (Settings → Player Controls → Video Playback) ---------------------
    // Mirrors of the two prefs, kept current by observeVideoPlayback() so the lifecycle callbacks
    // below can read them synchronously.
    private var videoBackgroundPlayback = true
    private var videoPictureInPicture = false
    /** Whether leaving the app right now should shrink into a PiP window (PictureInPicturePolicy). */
    private var pipEligible = false
    private val isInPictureInPicture = MutableStateFlow(false)

    /** The PiP window's rewind / play-pause / forward buttons come back as this broadcast. */
    private val pipControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(PictureInPicturePolicy.EXTRA_CONTROL)) {
                PictureInPicturePolicy.CONTROL_PLAY_PAUSE -> PlaybackManager.togglePlayPause()
                PictureInPicturePolicy.CONTROL_REWIND -> PlaybackManager.seekBackward()
                PictureInPicturePolicy.CONTROL_FORWARD -> PlaybackManager.seekForward()
            }
        }
    }

    /** With background playback OFF, a playing video pauses when the WHOLE app leaves the foreground
     *  (iOS: didEnterBackground). App-level, not activity-level: a file picker or the Auth Tab covering
     *  this activity is not "leaving the app", and a PiP window keeps the activity started anyway. */
    private val appBackgroundObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP &&
            PictureInPicturePolicy.shouldPauseOnBackground(
                videoBackgroundPlayback, PlaybackManager.hasVideo.value, PlaybackManager.isPlaying.value
            )
        ) {
            PlaybackManager.pause()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        // Storage critically full: the database can't be trusted to open (SQLite can't even size its
        // WAL shared memory at zero bytes free — Sentry ANDROID-BOOKPLAYER-10), so nothing below may
        // touch it. Show the storage screen instead, and start over once space is back.
        if (StorageMonitor.refresh(this).isCritical) {
            storageGateShown = true
            splashScreen.setKeepOnScreenCondition { !ThemeManager.isReady }
            enableEdgeToEdge()
            ThemeManager.initialize(this)
            setContent {
                BookPlayerTheme {
                    val storage by StorageMonitor.state.collectAsStateWithLifecycle()
                    StorageFullScreen(
                        state = storage,
                        onRetry = { if (!StorageMonitor.refresh(this).isCritical) recreate() },
                        onFreeUpSpace = { openStorageSettings(this) },
                    )
                }
            }
            return
        }

        // Create the (activity-scoped) LibraryViewModel up front — MainScreen's viewModel() call returns
        // this same instance — so the OS splash can be held until the FIRST local library load is in hand.
        // Holding the real splash (instead of swapping to an in-app replica) keeps the hand-off pixel-perfect:
        // the system renders the splash icon at its own size, which a Compose copy can't reliably match.
        // Wiring comes from the one shared constructor (LibraryViewModelFactory.default) so this block and
        // MainScreen can't drift.
        val libraryViewModel = ViewModelProvider(
            this,
            LibraryViewModelFactory.default(application),
        )[LibraryViewModel::class.java]

        splashScreen.setKeepOnScreenCondition {
            // Theme AND first local library load: the library screen renders real rows the frame the
            // splash lifts — no empty-state flash, no loading-screen replica in between.
            !ThemeManager.isReady || !libraryViewModel.isReady.value
        }

        enableEdgeToEdge()
        ThemeManager.initialize(this)
        setupDynamicShortcuts()

        // Only handle the launch intent on a fresh start. On a recreate (locale/density change, or
        // process-death restore from recents) the same ACTION_VIEW/SEND intent would otherwise be
        // replayed and re-trigger the import. onNewIntent covers warm re-deliveries while running.
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        ContextCompat.registerReceiver(
            this, pipControlReceiver, IntentFilter(PictureInPicturePolicy.ACTION_PIP),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(appBackgroundObserver)
        observeVideoPlayback()

        setContent {
            BookPlayerTheme {
                val pip by isInPictureInPicture.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalIsInPictureInPicture provides pip) {
                    MainScreen()
                }
            }
        }
    }

    // --- Picture in Picture ----------------------------------------------------------------------

    private fun pipSupported(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Keep the PiP eligibility and params current so the OS can auto-enter (S+) or we can on leave. */
    private fun observeVideoPlayback() {
        lifecycleScope.launch {
            combine(
                PlaybackSettingsManager.getVideoBackgroundPlayback(this@MainActivity),
                PlaybackSettingsManager.getVideoPictureInPicture(this@MainActivity),
                PlaybackManager.hasVideo,
                PlaybackManager.isPlaying,
                PlaybackManager.showPlayerScreen,
            ) { background, pip, hasVideo, playing, playerShown ->
                videoBackgroundPlayback = background
                videoPictureInPicture = pip
                PictureInPicturePolicy.shouldEnter(
                    supported = pipSupported(),
                    pipEnabled = pip,
                    backgroundPlaybackEnabled = background,
                    hasVideo = hasVideo,
                    isPlaying = playing,
                    playerScreenVisible = playerShown,
                )
            }.collect { eligible ->
                pipEligible = eligible
                // Re-pushed on every change so the play/pause button and aspect ratio stay current
                // while the PiP window is showing, too.
                if (eligible || isInPictureInPicture.value) applyPictureInPictureParams(eligible)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pipSupported()) {
                    setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
                }
            }
        }
    }

    private fun buildPictureInPictureParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setActions(pipActions())
        val size = PlaybackManager.player?.videoSize
        PictureInPicturePolicy.aspectRatio(size?.width ?: 0, size?.height ?: 0)?.let { (num, den) ->
            builder.setAspectRatio(Rational(num, den))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun applyPictureInPictureParams(autoEnter: Boolean) {
        if (!pipSupported()) return
        try {
            setPictureInPictureParams(buildPictureInPictureParams(autoEnter))
        } catch (e: IllegalStateException) {
            // Activity not yet attached / already finishing; the next state change re-applies.
            android.util.Log.w("MainActivity", "PiP params rejected", e)
        }
    }

    /** Pre-Android 12 there is no auto-enter: shrink into PiP ourselves when the user leaves. */
    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pipEligible && !isInPictureInPictureMode) {
            try {
                enterPictureInPictureMode(buildPictureInPictureParams(autoEnter = false))
            } catch (e: IllegalStateException) {
                android.util.Log.w("MainActivity", "Could not enter PiP", e)
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPictureInPicture.value = isInPictureInPictureMode
    }

    private fun pipActions(): List<RemoteAction> {
        fun action(iconRes: Int, titleRes: Int, requestCode: Int, control: String): RemoteAction {
            val intent = Intent(PictureInPicturePolicy.ACTION_PIP)
                .setPackage(packageName)
                .putExtra(PictureInPicturePolicy.EXTRA_CONTROL, control)
            val pending = PendingIntent.getBroadcast(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val title = getString(titleRes)
            return RemoteAction(Icon.createWithResource(this, iconRes), title, title, pending)
        }
        val playing = PlaybackManager.isPlaying.value
        return listOf(
            action(R.drawable.ic_pip_rewind, R.string.shortcut_rewind_title, 1, PictureInPicturePolicy.CONTROL_REWIND),
            if (playing) action(R.drawable.ic_pause, R.string.player_pause, 2, PictureInPicturePolicy.CONTROL_PLAY_PAUSE)
            else action(R.drawable.ic_play, R.string.player_play, 2, PictureInPicturePolicy.CONTROL_PLAY_PAUSE),
            action(R.drawable.ic_pip_forward, R.string.shortcut_forward_title, 3, PictureInPicturePolicy.CONTROL_FORWARD),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Settings after freeing space: re-measure; leave the gate if it's showing.
        val critical = StorageMonitor.refresh(this).isCritical
        if (storageGateShown && !critical) recreate()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // Imports write files and rows; while the gate is up there is nowhere to put them.
        if (StorageMonitor.isCritical) return
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
                    // A "play" affordance must never pause: if something is already playing,
                    // play() is a no-op rather than a toggle.
                    if (PlaybackManager.currentItem.value != null) {
                        if (autoplay) PlaybackManager.play()
                        if (showPlayer) PlaybackManager.setShowPlayer(true)
                    } else {
                        lifecycleScope.launch {
                            val lastUuid = PlaybackSettingsManager.getLastItemUuid(this@MainActivity).first()
                            if (lastUuid != null) {
                                PlaybackManager.playItemByPath(this@MainActivity, lastUuid, autoplay, showPlayer)
                            } else {
                                if (autoplay) PlaybackManager.play()
                                if (showPlayer) PlaybackManager.setShowPlayer(true)
                            }
                        }
                    }
                }
            }
            "download" -> {
                val downloadUrl = uri.getQueryParameter("url")
                if (downloadUrl != null) {
                    // Remove quotes if present
                    val cleanUrl = downloadUrl.replace("\"", "")
                    // startImport can't open http(s) URIs — route through the download pipeline
                    // (same staging, dedup, archive expansion, and import sheet as other sources).
                    val fileName = com.tortugapower.audiobookplayer.logic.ImportManager.fileNameFromUrl(cleanUrl)
                    com.tortugapower.audiobookplayer.logic.ImportManager.startDownload(this, cleanUrl, fileName)
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
                    SleepTimerManager.configureTimerWithSeconds(seconds)
                }
                PlaybackManager.setShowPlayer(true)
                PlayerUiSignals.requestOpenSleepTimer()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Playback itself lives in the service; only this activity's PiP/background hooks go.
        if (!storageGateShown) {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(appBackgroundObserver)
            unregisterReceiver(pipControlReceiver)
        }
    }

    private fun setupDynamicShortcuts() {
        // ShortcutManager get/set are disk-backed binder calls (and icon/label building isn't free) —
        // keep the publish off the UI thread during cold start. ShortcutManagerCompat handles version
        // gating, so no Build.VERSION check here.
        lifecycleScope.launch(Dispatchers.Default) {
            ShortcutHelper.publishDynamicShortcuts(applicationContext)
        }
    }
}
