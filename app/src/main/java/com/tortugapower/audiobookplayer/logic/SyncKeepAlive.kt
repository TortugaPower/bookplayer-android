package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Starts the sync service (TaskConcurrencyServiceHost) when a transfer needs it to keep the process alive
 * (SyncKeepAlivePolicy), and tells the service what it's keeping alive, so it stops itself once nothing is.
 *
 * Android allows a foreground service to start from the background only at a few moments. The two that matter here
 * are when the app has just left the screen, and while playback holds the foreground. So the service starts as the
 * app leaves with a transfer running, or as playback leaves the foreground ([onPlaybackLeavingForeground], before it
 * demotes). A start that's refused anyway (the dataSync budget spent, or no exemption) isn't retried: the transfer
 * goes on while the process lives, and the next time the app leaves the screen tries again.
 */
internal class SyncKeepAlive(
    scope: CoroutineScope,
    private val visible: StateFlow<Boolean>,
    private val playing: StateFlow<Boolean>,
    private val lanes: StateFlow<Map<String, LaneState>>,
    private val startService: () -> Unit,
) {
    /** The transfer lanes the service keeps alive: none when nothing needs it */
    val transfers: StateFlow<Set<String>> = combine(visible, playing, lanes, SyncKeepAlivePolicy::transfersToKeepAlive)
        .stateIn(scope, SharingStarted.Eagerly, SyncKeepAlivePolicy.transfersToKeepAlive(visible.value, playing.value, lanes.value))

    /**
     * Whether a transfer runs with the app on screen, the user's or one the app queued itself: when to ask for the
     * notifications its notice needs (NotificationPermissionPrompt)
     */
    val transferOnScreen: StateFlow<Boolean> = combine(visible, lanes) { visible, lanes ->
        visible && SyncKeepAlivePolicy.transfersWorking(lanes).isNotEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, visible.value && SyncKeepAlivePolicy.transfersWorking(lanes.value).isNotEmpty())

    init {
        scope.launch {
            transfers.map { it.isNotEmpty() }.distinctUntilChanged().filter { it }.collect { startService() }
        }
    }

    /**
     * Playback is about to leave the foreground (PlaybackForeground.onLeaving), on the service's thread before it
     * demotes: the app may still start a foreground service from the background, and won't once it has demoted.
     */
    fun onPlaybackLeavingForeground() {
        if (SyncKeepAlivePolicy.transfersToKeepAlive(visible.value, playing = false, lanes.value).isNotEmpty()) {
            startService()
        }
    }
}
