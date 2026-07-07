package com.tortugapower.audiobookplayer.wear.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.datalayer.WatchCommand
import com.tortugapower.audiobookplayer.datalayer.WatchCommandType
import com.tortugapower.audiobookplayer.datalayer.WatchItem
import com.tortugapower.audiobookplayer.datalayer.WatchNowPlaying
import com.tortugapower.audiobookplayer.wear.data.RemoteCommandSender
import com.tortugapower.audiobookplayer.wear.data.RemoteContextRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the remote screen renders. [connecting] = no state received from the phone yet. */
data class RemoteUiState(
    val connecting: Boolean = true,
    val recentItems: List<WatchItem> = emptyList(),
    val nowPlaying: WatchNowPlaying? = null,
    val isPlaying: Boolean = false,
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
        repository.playbackState,
        optimisticPlaying,
    ) { library, playback, optimistic ->
        RemoteUiState(
            connecting = library == null && playback == null,
            recentItems = library?.recentItems ?: emptyList(),
            nowPlaying = library?.currentItem,
            isPlaying = optimistic ?: (playback?.isPlaying ?: false),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteUiState())

    init {
        // The playback echo is authoritative — once it arrives, drop any optimistic override.
        repository.playbackState.onEach { optimisticPlaying.value = null }.launchIn(viewModelScope)
    }

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

    private fun dispatch(command: WatchCommand) {
        viewModelScope.launch { commandSender.send(command) }
    }
}
