package com.tortugapower.audiobookplayer.logic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/**
 * The device's media-stream volume as a 0..1 fraction, with one-step nudges — what the watch's rotary
 * crown drives during standalone playback (iOS `WKInterfaceVolumeControl` parity).
 *
 * Goes straight to [AudioManager]: media3 1.10 stopped honouring device-volume commands sent through a
 * `MediaController` for local playback, which is how this used to work. Adjustments are silent (no
 * `FLAG_SHOW_UI` — on Wear that pops a full-screen system slider that grabs the crown); the UI renders
 * its own indicator from [onChanged]. Changes made elsewhere (hardware buttons, system UI) are picked
 * up through the platform's volume-changed broadcast while [startObserving] is active.
 */
class DeviceVolume(context: Context, private val onChanged: (Float) -> Unit) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var receiver: BroadcastReceiver? = null

    /** Current media-stream volume as a 0..1 fraction (0 when the range is unknown). */
    val fraction: Float
        get() = fractionOf(audioManager.getStreamVolume(STREAM), minVolume(), audioManager.getStreamMaxVolume(STREAM))

    private fun minVolume(): Int = try {
        audioManager.getStreamMinVolume(STREAM)
    } catch (e: Exception) {
        0 // the platform default; a missing audio service (test doubles, odd OEM builds) must not break the indicator
    }

    fun increase() = adjust(AudioManager.ADJUST_RAISE)
    fun decrease() = adjust(AudioManager.ADJUST_LOWER)

    private fun adjust(direction: Int) {
        try {
            audioManager.adjustStreamVolume(STREAM, direction, 0)
        } catch (e: SecurityException) {
            // Do-not-disturb can refuse volume changes; the indicator simply stays where it is.
        }
        publish()
    }

    /** Re-read the volume and notify — call once the UI that shows it is on screen. */
    fun publish() = onChanged(fraction)

    /** Follow volume changes made outside the app (hardware buttons, system UI). Idempotent. */
    fun startObserving() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) == STREAM) publish()
            }
        }.also {
            appContext.registerReceiver(it, IntentFilter(VOLUME_CHANGED_ACTION))
        }
        publish()
    }

    fun stopObserving() {
        receiver?.let { appContext.unregisterReceiver(it) }
        receiver = null
    }

    companion object {
        private const val STREAM = AudioManager.STREAM_MUSIC
        // Broadcast the platform sends on every stream volume change; not in the public API surface but
        // stable since API 1 and the only signal for volume changes made outside the app.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        /** Pure 0..1 mapping of [volume] within [[minVolume], [maxVolume]] (0 when the range is empty). */
        fun fractionOf(volume: Int, minVolume: Int, maxVolume: Int): Float {
            val range = maxVolume - minVolume
            return if (range > 0) ((volume - minVolume).toFloat() / range).coerceIn(0f, 1f) else 0f
        }
    }
}
