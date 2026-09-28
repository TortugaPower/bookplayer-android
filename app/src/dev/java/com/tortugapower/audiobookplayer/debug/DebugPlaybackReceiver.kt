package com.tortugapower.audiobookplayer.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dev flavor only (`src/dev`): a resume that `scripts/chaos` sends with `adb shell am broadcast`, i.e.
 * from a background process with no user gesture behind it — the shape of the watch's remote play
 * (`WearCommandListenerService.play`) and of Android Auto's controller commands, which is what
 * ANDROID-BOOKPLAYER-21 needs: once media3 has dropped the foreground after a pause, such a resume
 * cannot bring it back (Android 12+ grants no allowance to it).
 *
 * `RESUME` calls the player directly, as the watch listener does. `RESUME_VIA_MEDIA_KEY` dispatches
 * KEYCODE_MEDIA_PLAY through AudioManager from inside the app — measured on API 31, that is refused too:
 * the system allowlists a package for a media key only when someone else sent it (a headset, the
 * launcher), never for its own. Both are kept so the rig can show the difference from an injected key.
 */
class DebugPlaybackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESUME -> {
                val current = PlaybackManager.currentItem.value
                if (current != null) {
                    if (!PlaybackManager.isPlaying.value) PlaybackManager.togglePlayPause()
                } else {
                    // Cold process (the OS killed the app): the watch's "play this item" branch —
                    // resolve off-main, load on main.
                    CoroutineScope(Dispatchers.IO).launch {
                        val dao = AppDatabase.getDatabase(context.applicationContext).libraryDao()
                        val item = dao.getRecentPlayedItemsSync(1).firstOrNull() ?: dao.getRootItemsSync().firstOrNull()
                        if (item != null) withContext(Dispatchers.Main) {
                            PlaybackManager.playItem(context.applicationContext, item)
                        }
                    }
                }
            }
            ACTION_RESUME_VIA_MEDIA_KEY -> {
                val audioManager = context.getSystemService(AudioManager::class.java)
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            }
        }
    }

    companion object {
        const val ACTION_RESUME = "com.tortugapower.audiobookplayer.debug.RESUME"
        const val ACTION_RESUME_VIA_MEDIA_KEY = "com.tortugapower.audiobookplayer.debug.RESUME_VIA_MEDIA_KEY"
    }
}
