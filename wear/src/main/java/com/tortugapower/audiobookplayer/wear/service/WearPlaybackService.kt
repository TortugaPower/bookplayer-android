package com.tortugapower.audiobookplayer.wear.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.common.collect.ImmutableList
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.service.MediaPlaybackService
import com.tortugapower.audiobookplayer.wear.R
import com.tortugapower.audiobookplayer.wear.presentation.MainActivity

/**
 * The watch's playback service. Subclasses the shared [MediaPlaybackService] (which owns the ExoPlayer
 * build, auth data source, BookTimelinePlayer wrap, LoudnessEnhancer, and the transport-only session
 * callback) and adds only the Wear-specific pieces: the launch [PendingIntent] into the Wear
 * [MainActivity] and a minimal Now Playing button row (rewind / speed / forward — no Android Auto browse
 * tree, no add-bookmark; those are phone-only). Registered as the watch's `<service>` in the manifest.
 *
 * `@OptIn(UnstableApi)`: Media3's `CommandButton` slots / built-in icons and the `MediaLibrarySession`
 * callback type are still `@UnstableApi`; opting in here is the targeted equivalent of `:app`'s
 * `lint { abortOnError = false }` for the same phone-side usage.
 */
@OptIn(UnstableApi::class)
class WearPlaybackService : MediaPlaybackService() {

    // Let ExoPlayer own the watch's media-stream volume so the rotary crown can drive it during standalone
    // playback (the crown calls PlaybackManager.increase/decreaseDeviceVolume). Off on the phone.
    override val deviceVolumeControlEnabled: Boolean = true

    override fun onCreate() {
        super.onCreate()
        // Wear App Quality requirement ("Missing ongoing activity", 1.0.0 wear review): active playback
        // must surface as an Ongoing Activity — the indicator on the watch face and the chip in recents.
        // Both derive from the OngoingActivity attached to the playback notification below.
        setMediaNotificationProvider(OngoingMediaNotificationProvider())
    }

    /**
     * Media3's default provider builds the actual media notification (transport actions, metadata,
     * media style); this wrapper only rebuilds its result so the wear [OngoingActivity] extras can be
     * attached while something is playing. Paused/stopped notifications pass through untouched — the
     * ongoing indicator should only exist while playback is genuinely ongoing.
     */
    private inner class OngoingMediaNotificationProvider : MediaNotification.Provider {
        private val delegate = DefaultMediaNotificationProvider.Builder(this@WearPlaybackService).build()

        override fun createNotification(
            mediaSession: MediaSession,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification {
            val default = delegate.createNotification(
                mediaSession, mediaButtonPreferences, actionFactory, onNotificationChangedCallback,
            )
            val sessionPlayer = mediaSession.player
            if (!sessionPlayer.playWhenReady || sessionPlayer.mediaItemCount == 0) return default

            // Rebuild the default notification into a builder (the OngoingActivity API needs one).
            val builder = NotificationCompat.Builder(this@WearPlaybackService, default.notification)
            val title = sessionPlayer.mediaMetadata.title?.toString()
                ?: getString(R.string.wear_now_playing)
            OngoingActivity.Builder(applicationContext, default.notificationId, builder)
                .setStaticIcon(R.drawable.ic_bookplayer_glyph)
                .setTouchIntent(createSessionActivity())
                .setStatus(Status.Builder().addTemplate(title).build())
                .build()
                .apply(applicationContext)
            return MediaNotification(default.notificationId, builder.build())
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle,
        ): Boolean = false
    }

    override fun createSessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // Transport-only callback: the shared BaseLibrarySessionCallback (command withholding + rewind/forward/
    // speed custom actions + Bluetooth media-button remap). No browse tree, no bookmark on the watch.
    override fun createSessionCallback(): MediaLibrarySession.Callback =
        BaseLibrarySessionCallback()

    /**
     * The watch media notification's custom row: circular rewind / fast-forward (matching the phone) plus a
     * speed-cycle button whose glyph tracks the live rate. Rewind/forward flank the play/pause; speed sits
     * in the overflow. Uses the shared rewind/forward/speed session commands from [MediaPlaybackService].
     */
    override fun buildMediaButtonPreferences(): List<CommandButton> {
        val rewind = CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setSessionCommand(SessionCommand(APP_ACTION_REWIND, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_BACK)
            .setDisplayName(getString(R.string.wear_media_rewind))
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setSessionCommand(SessionCommand(APP_ACTION_FORWARD, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD)
            .setDisplayName(getString(R.string.wear_media_fast_forward))
            .build()
        val speed = CommandButton.Builder(speedIcon())
            .setSessionCommand(SessionCommand(APP_ACTION_CYCLE_SPEED, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .setDisplayName(getString(R.string.wear_speed))
            .build()
        return listOf(rewind, speed, forward)
    }

    /** Nearest Media3 built-in speed glyph for the live rate, so the speed button shows the current speed. */
    private fun speedIcon(): Int = PlaybackManager.playbackSpeed.value.let { speed ->
        when {
            speed < 0.65f -> CommandButton.ICON_PLAYBACK_SPEED_0_5
            speed < 0.9f -> CommandButton.ICON_PLAYBACK_SPEED_0_8
            speed < 1.1f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
            speed < 1.35f -> CommandButton.ICON_PLAYBACK_SPEED_1_2
            speed < 1.65f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
            speed < 1.9f -> CommandButton.ICON_PLAYBACK_SPEED_1_8
            else -> CommandButton.ICON_PLAYBACK_SPEED_2_0
        }
    }
}
