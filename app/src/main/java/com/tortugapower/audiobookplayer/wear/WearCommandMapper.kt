package com.tortugapower.audiobookplayer.wear

import com.tortugapower.audiobookplayer.datalayer.WatchCommand
import com.tortugapower.audiobookplayer.datalayer.WatchCommandType
import com.tortugapower.audiobookplayer.datalayer.WatchSleepSentinel

/**
 * The playback effects a remote command can trigger. An interface (not direct `PlaybackManager` calls) so
 * the command→action routing is unit-testable with a fake, and so [WearCommandListenerService] is the only
 * place that touches the real player/threading. Mirrors iOS's `ActionParserService`.
 */
interface RemotePlaybackActions {
    /** [itemId] = the item's relativePath to load; null = resume/keep the current item. */
    fun play(itemId: String?)
    fun pause()
    fun skipForward()
    fun skipBackward()
    fun seekToChapter(startSeconds: Double)
    fun setSpeed(speed: Float)
    fun sleepOff()
    fun sleepEndOfChapter()
    fun sleepAfter(seconds: Long)
    fun setBoost(on: Boolean)
    fun refresh()
}

/** Pure routing of a decoded [WatchCommand] to [RemotePlaybackActions]. */
object WearCommandMapper {
    fun dispatch(command: WatchCommand, actions: RemotePlaybackActions) {
        when (command.type) {
            WatchCommandType.PLAY -> actions.play(command.itemId)
            WatchCommandType.PAUSE -> actions.pause()
            WatchCommandType.SKIP_FORWARD -> actions.skipForward()
            WatchCommandType.SKIP_BACKWARD -> actions.skipBackward()
            WatchCommandType.CHAPTER -> command.chapterStart?.let { actions.seekToChapter(it) }
            WatchCommandType.SPEED -> command.speed?.let { actions.setSpeed(it) }
            WatchCommandType.SLEEP -> when (val seconds = command.sleepSeconds) {
                // Local capture: `sleepSeconds` is a :core property, so it won't smart-cast to non-null here.
                null -> Unit
                WatchSleepSentinel.OFF -> actions.sleepOff()
                WatchSleepSentinel.END_OF_CHAPTER -> actions.sleepEndOfChapter()
                else -> actions.sleepAfter(seconds)
            }
            WatchCommandType.BOOST_VOLUME -> command.boostOn?.let { actions.setBoost(it) }
            WatchCommandType.REFRESH -> actions.refresh()
        }
    }
}
