package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One-shot UI signals for the phone player screen — events that come from outside the composition (a
 * deep link / launcher shortcut handled in `MainActivity`) and must reach `PlayerScreen` exactly once.
 *
 * Phone-only on purpose: this is UI-navigation intent, so it lives in `:app`, not the shared `:core`
 * `PlaybackManager` (the watch has its own navigation and never consumes these).
 *
 * A [Channel.CONFLATED] gives the two properties a `StateFlow<Boolean>` trigger can't provide together:
 * it *latches* a signal emitted before the screen starts collecting (cold start via the shortcut), yet
 * is *consumed once* — it does not re-deliver to a new collector after a config-change recreation.
 */
object PlayerUiSignals {

    private val _openSleepTimer = Channel<Unit>(Channel.CONFLATED)

    /** Emitted when the player screen should open the sleep-timer menu (the `bookplayer://sleep` link). */
    val openSleepTimer = _openSleepTimer.receiveAsFlow()

    fun requestOpenSleepTimer() {
        _openSleepTimer.trySend(Unit)
    }
}
