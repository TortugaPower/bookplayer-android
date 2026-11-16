package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Keeps the sync engine's work going once the app leaves the screen (SyncKeepAlivePolicy): starts the sync service
 * (SyncKeepAliveService: the target's TaskConcurrencyServiceHost or WearSyncServiceHost) when a transfer needs it,
 * and tells the service what it's keeping alive, so it stops itself once nothing is; and schedules sync drains
 * (SyncDrainScheduler) for whatever is left to them.
 *
 * Android allows a foreground service to start from the background only at a few moments. The two that matter here
 * are when the app has just left the screen, and while playback holds the foreground. So the service starts as the
 * app leaves with a transfer running, or as playback leaves the foreground ([onPlaybackLeavingForeground], before it
 * demotes). A start that's refused anyway (the dataSync budget spent, or no exemption), or a service Android ends
 * when its budget runs out, hands its transfers to drains ([onServiceUnavailable]) until the app is next opened. And
 * while a drain runs, it is what keeps the process alive: the service isn't started then (Android 16 counts a job
 * running beside a foreground service against the app's job quota).
 */
class SyncKeepAlive(
    scope: CoroutineScope,
    private val visible: StateFlow<Boolean>,
    private val playing: StateFlow<Boolean>,
    private val lanes: StateFlow<Map<String, LaneState>>,
    private val serviceRunning: StateFlow<Boolean>,
    private val draining: StateFlow<Boolean>,
    /** Whether the engine has looked at the queue: until then, what drains it needs isn't known (SyncRuntime.scanned) */
    private val scanned: StateFlow<Boolean>,
    private val startService: () -> Unit,
    private val scheduleDrains: suspend () -> Unit,
) {
    // Android won't have the service keep transfers alive, until the app is next opened (which resets its budget)
    private val serviceUnavailable = MutableStateFlow(false)

    /** The transfer lanes the service keeps alive: none when nothing needs it, or when a drain or nothing can */
    val transfers: StateFlow<Set<String>> = combine(visible, playing, lanes, draining, serviceUnavailable) { visible, playing, lanes, draining, unavailable ->
        SyncKeepAlivePolicy.transfersToKeepAlive(visible, playing, lanes, serviceUsable = !draining && !unavailable)
    }.stateIn(scope, SharingStarted.Eagerly, transfersNow())

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
        scope.launch {
            visible.filter { it }.collect { serviceUnavailable.value = false }
        }
        // Left to drains: scheduled from the stored queue, and again whenever the lanes change meanwhile, or the engine
        // has first looked at the queue (book uploads held for Wi-Fi start no lane), a moment after: a drain started
        // with the process takes over by then, and a stop that leaves something running just moved its work here
        scope.launch {
            combine(visible, playing, serviceRunning, transfers, draining) { visible, playing, running, transfers, draining ->
                SyncKeepAlivePolicy.leftToDrains(visible, playing, running, transfers, draining)
            }.combine(lanes) { left, _ -> left }.combine(scanned) { left, _ -> left }.collectLatest { left ->
                if (!left) return@collectLatest
                delay(DRAIN_SCHEDULE_DELAY_MS)
                scheduleDrains()
            }
        }
    }

    /** Android refused the service or ended it (SyncKeepAliveService.onUnavailable): drains carry the transfers */
    fun onServiceUnavailable() {
        serviceUnavailable.value = true
    }

    /**
     * Playback is about to leave the foreground (PlaybackForeground.onLeaving), on the service's thread before it
     * demotes: the app may still start a foreground service from the background, and won't once it has demoted.
     */
    fun onPlaybackLeavingForeground() {
        if (transfersNow(playing = false).isNotEmpty()) startService()
    }

    private fun transfersNow(playing: Boolean = this.playing.value): Set<String> = SyncKeepAlivePolicy.transfersToKeepAlive(
        visible.value, playing, lanes.value, serviceUsable = !draining.value && !serviceUnavailable.value,
    )

    companion object {
        internal const val DRAIN_SCHEDULE_DELAY_MS = 1_000L
    }
}
