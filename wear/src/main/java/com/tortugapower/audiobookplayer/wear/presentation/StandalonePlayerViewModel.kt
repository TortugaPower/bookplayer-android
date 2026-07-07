package com.tortugapower.audiobookplayer.wear.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.datalayer.WatchChapter
import com.tortugapower.audiobookplayer.datalayer.WatchNowPlaying
import com.tortugapower.audiobookplayer.logic.PlayableItem
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.SleepTimerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Drives standalone (on-watch) playback. Unlike [RemoteViewModel] — which mirrors the phone over the Data
 * Layer — this binds the SAME now-playing UI ([RemoteUiState] + the shared NowPlaying/PlaybackControls/
 * ChapterList screens) directly to the LOCAL [PlaybackManager] running on the watch. Commands call
 * PlaybackManager/SleepTimerManager in-process; progress + speed/boost persist and sync through the injected
 * SyncingLibraryRepository (wired in WearApp).
 */
class StandalonePlayerViewModel : ViewModel() {

    // Optimistic speed so rapid +/- taps accumulate instantly: PlaybackManager.setPlaybackSpeed persists
    // to DataStore and only echoes back on playbackSpeed asynchronously, so basing each step on the live
    // value would read a stale rate (three +0.1 taps would all land on the same base). Cleared when the
    // real speed echoes. Mirrors RemoteViewModel.optimisticSpeed.
    private val optimisticSpeed = MutableStateFlow<Float?>(null)

    val state: StateFlow<RemoteUiState> = combine(
        combine(PlaybackManager.currentItem, PlaybackManager.currentPlayable, PlaybackManager.isPlaying) {
            item, playable, playing -> Triple(item, playable, playing)
        },
        // The real speed is authoritative — clear the optimistic override the moment it echoes back.
        combine(
            PlaybackManager.playbackSpeed.onEach { optimisticSpeed.value = null },
            PlaybackManager.volumeBoost,
        ) { speed, boost -> speed to boost },
        combine(PlaybackManager.rewindInterval, PlaybackManager.forwardInterval) { r, f -> r to f },
        optimisticSpeed,
    ) { itemPlayablePlaying, speedBoost, intervals, optSpeed ->
        val (item, playable, playing) = itemPlayablePlaying
        val (realSpeed, boost) = speedBoost
        val (rewind, forward) = intervals
        RemoteUiState(
            connecting = false, // the watch is the source of truth in standalone mode — never "connecting"
            recentItems = emptyList(), // standalone plays from the library nav, not a recents row
            nowPlaying = item?.let { toNowPlaying(it, playable) },
            isPlaying = playing,
            speed = optSpeed ?: realSpeed,
            boostVolume = boost,
            rewindInterval = rewind,
            forwardInterval = forward,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteUiState(connecting = false))

    /** Play a library item by its relativePath (the row id) on the watch. */
    fun playItem(path: String) = PlaybackManager.playItemByPath(context(), path, showPlayer = false)

    fun togglePlayPause() = PlaybackManager.togglePlayPause()
    fun skipForward() = PlaybackManager.seekForward()
    fun skipBackward() = PlaybackManager.seekBackward()

    /** Jump to a chapter by its whole-book start (seconds). */
    fun seekChapter(startSeconds: Double) =
        PlaybackManager.seekWholeBook((startSeconds * 1000).toLong())

    fun decreaseSpeed() = setSpeed(currentSpeed() - SPEED_STEP)
    fun increaseSpeed() = setSpeed(currentSpeed() + SPEED_STEP)

    /** Cycle to the next preset; surface it optimistically so the button updates before the echo. */
    fun cycleSpeed() { optimisticSpeed.value = PlaybackManager.cyclePlaybackSpeed(context()) }

    // Base each step on the optimistic value (set synchronously in setSpeed) so rapid taps accumulate
    // before PlaybackManager's async echo lands; falls back to the last echoed speed.
    private fun currentSpeed(): Float = optimisticSpeed.value ?: PlaybackManager.playbackSpeed.value

    fun sleepOff() = SleepTimerManager.stopTimer()
    fun sleepEndOfChapter() = SleepTimerManager.startTimerUntilEndOfChapter()
    fun sleepAfter(minutes: Int) = SleepTimerManager.startTimer(minutes)

    fun toggleBoost() = PlaybackManager.toggleVolumeBoost(context())

    private fun setSpeed(rate: Float) {
        val clamped = kotlin.math.round(rate.coerceIn(SPEED_MIN, SPEED_MAX) * 100f) / 100f
        optimisticSpeed.value = clamped
        PlaybackManager.setPlaybackSpeed(context(), clamped)
    }

    // Command entry points need a Context; use the :core-held app context so the VM holds no Context field.
    private fun context() = CoreContext.appContext

    companion object {
        private const val SPEED_MIN = 0.5f
        private const val SPEED_MAX = 4.0f
        private const val SPEED_STEP = 0.1f

        /** Pure entity(+chapters) → now-playing mapping (unit-tested). */
        fun toNowPlaying(item: LibraryItemEntity, playable: PlayableItem?): WatchNowPlaying = WatchNowPlaying(
            id = item.relativePath ?: item.uuid,
            title = item.title,
            author = item.author.orEmpty(),
            chapters = playable?.chapters.orEmpty().map { WatchChapter(it.title, it.start, it.index) },
        )
    }
}
