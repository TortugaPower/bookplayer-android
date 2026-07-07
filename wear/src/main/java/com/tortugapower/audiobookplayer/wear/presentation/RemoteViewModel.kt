package com.tortugapower.audiobookplayer.wear.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.datalayer.WatchCommand
import com.tortugapower.audiobookplayer.datalayer.WatchCommandType
import com.tortugapower.audiobookplayer.datalayer.WatchItem
import com.tortugapower.audiobookplayer.datalayer.WatchNowPlaying
import com.tortugapower.audiobookplayer.datalayer.WatchSleepSentinel
import com.tortugapower.audiobookplayer.wear.data.RemoteCommandSender
import com.tortugapower.audiobookplayer.wear.data.RemoteContextRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the remote screen renders. [connecting] = no state received from the phone yet. */
data class RemoteUiState(
    val connecting: Boolean = true,
    val recentItems: List<WatchItem> = emptyList(),
    val nowPlaying: WatchNowPlaying? = null,
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
    val boostVolume: Boolean = false,
    val rewindInterval: Int = 0,
    val forwardInterval: Int = 0,
)

/**
 * Drives remote-controller mode: merges the phone's library + playback DataItems into [RemoteUiState] and
 * sends control commands. Play/pause flips optimistically for snappiness; the phone's authoritative echo
 * (the playback DataItem) then clears the override so real state wins.
 */
class RemoteViewModel(
    private val repository: RemoteContextRepository,
    private val commandSender: RemoteCommandSender,
) : ViewModel() {

    private val optimisticPlaying = MutableStateFlow<Boolean?>(null)

    val state: StateFlow<RemoteUiState> = combine(
        repository.libraryState,
        // The playback echo is authoritative — clear any optimistic override the moment a new one arrives.
        // Done inside the combine (not a second collector) so playbackState has a single DataClient listener.
        repository.playbackState.onEach { optimisticPlaying.value = null },
        optimisticPlaying,
    ) { library, playback, optimistic ->
        RemoteUiState(
            connecting = library == null && playback == null,
            recentItems = library?.recentItems ?: emptyList(),
            nowPlaying = library?.currentItem,
            isPlaying = optimistic ?: (playback?.isPlaying ?: false),
            speed = playback?.speed ?: 1.0f,
            boostVolume = playback?.boostVolume ?: false,
            rewindInterval = library?.rewindInterval ?: 0,
            forwardInterval = library?.forwardInterval ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteUiState())

    /** Tap a recent row: play it on the phone. */
    fun playItem(itemId: String) {
        optimisticPlaying.value = true
        dispatch(WatchCommand(WatchCommandType.PLAY, itemId = itemId))
    }

    /** Now-playing play/pause toggle. */
    fun togglePlayPause() {
        val playing = state.value.isPlaying
        optimisticPlaying.value = !playing
        dispatch(if (playing) WatchCommand(WatchCommandType.PAUSE) else WatchCommand(WatchCommandType.PLAY))
    }

    /** Ask the phone to re-publish its state (pull-to-refresh / empty-state affordance). */
    fun refresh() = dispatch(WatchCommand(WatchCommandType.REFRESH))

    fun skipForward() = dispatch(WatchCommand(WatchCommandType.SKIP_FORWARD))
    fun skipBackward() = dispatch(WatchCommand(WatchCommandType.SKIP_BACKWARD))

    /** Jump to a chapter by its whole-book start (seconds). */
    fun seekChapter(startSeconds: Double) =
        dispatch(WatchCommand(WatchCommandType.CHAPTER, chapterStart = startSeconds))

    fun decreaseSpeed() = setSpeed(state.value.speed - SPEED_STEP)
    fun increaseSpeed() = setSpeed(state.value.speed + SPEED_STEP)

    /** Cycle speed up by [SPEED_JUMP], wrapping back to [SPEED_MIN] past [SPEED_MAX] (mirrors iOS). */
    fun cycleSpeed() {
        val next = state.value.speed + SPEED_JUMP
        setSpeed(if (next > SPEED_MAX + 0.001f) SPEED_MIN else next)
    }

    private fun setSpeed(rate: Float) {
        val clamped = kotlin.math.round(rate.coerceIn(SPEED_MIN, SPEED_MAX) * 100f) / 100f
        dispatch(WatchCommand(WatchCommandType.SPEED, speed = clamped))
    }

    fun sleepOff() = dispatch(WatchCommand(WatchCommandType.SLEEP, sleepSeconds = WatchSleepSentinel.OFF))
    fun sleepEndOfChapter() =
        dispatch(WatchCommand(WatchCommandType.SLEEP, sleepSeconds = WatchSleepSentinel.END_OF_CHAPTER))

    fun sleepAfter(minutes: Int) =
        dispatch(WatchCommand(WatchCommandType.SLEEP, sleepSeconds = minutes * 60L))

    fun toggleBoost() =
        dispatch(WatchCommand(WatchCommandType.BOOST_VOLUME, boostOn = !state.value.boostVolume))

    private fun dispatch(command: WatchCommand) {
        viewModelScope.launch { commandSender.send(command) }
    }

    private companion object {
        const val SPEED_MIN = 0.5f
        const val SPEED_MAX = 4.0f
        const val SPEED_STEP = 0.1f
        const val SPEED_JUMP = 0.5f
    }
}
