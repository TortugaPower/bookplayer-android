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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Drives standalone (on-watch) playback. Unlike [RemoteViewModel] — which mirrors the phone over the Data
 * Layer — this binds the SAME now-playing UI ([RemoteUiState] + the shared NowPlaying/PlaybackControls/
 * ChapterList screens) directly to the LOCAL [PlaybackManager] running on the watch. Commands call
 * PlaybackManager/SleepTimerManager in-process; progress + speed/boost persist and sync through the injected
 * SyncingLibraryRepository (wired in WearApp).
 */
class StandalonePlayerViewModel : ViewModel() {

    val state: StateFlow<RemoteUiState> = combine(
        combine(PlaybackManager.currentItem, PlaybackManager.currentPlayable) { item, playable -> item to playable },
        PlaybackManager.isPlaying,
        PlaybackManager.playbackSpeed,
        PlaybackManager.volumeBoost,
        combine(PlaybackManager.rewindInterval, PlaybackManager.forwardInterval) { r, f -> r to f },
    ) { itemPlayable, playing, speed, boost, intervals ->
        val (item, playable) = itemPlayable
        val (rewind, forward) = intervals
        RemoteUiState(
            connecting = false, // the watch is the source of truth in standalone mode — never "connecting"
            recentItems = emptyList(), // standalone plays from the library nav, not a recents row
            nowPlaying = item?.let { toNowPlaying(it, playable) },
            isPlaying = playing,
            speed = speed,
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

    fun decreaseSpeed() = setSpeed(PlaybackManager.playbackSpeed.value - SPEED_STEP)
    fun increaseSpeed() = setSpeed(PlaybackManager.playbackSpeed.value + SPEED_STEP)
    fun cycleSpeed() { PlaybackManager.cyclePlaybackSpeed(context()) }

    fun sleepOff() = SleepTimerManager.stopTimer()
    fun sleepEndOfChapter() = SleepTimerManager.startTimerUntilEndOfChapter()
    fun sleepAfter(minutes: Int) = SleepTimerManager.startTimer(minutes)

    fun toggleBoost() = PlaybackManager.toggleVolumeBoost(context())

    private fun setSpeed(rate: Float) {
        val clamped = kotlin.math.round(rate.coerceIn(SPEED_MIN, SPEED_MAX) * 100f) / 100f
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
