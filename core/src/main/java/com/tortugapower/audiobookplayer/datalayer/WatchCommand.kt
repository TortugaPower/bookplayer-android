package com.tortugapower.audiobookplayer.datalayer

/**
 * A control command the watch sends to the phone (`PATH_COMMAND`) in remote-controller mode; the phone maps
 * it onto its `PlaybackManager`/`SleepTimerManager`. Mirrors iOS's companion command vocabulary, typed
 * instead of stringly-keyed. Only the fields relevant to a given [type] are set.
 */
data class WatchCommand(
    val type: WatchCommandType,
    /** PLAY: the item's `relativePath` to load/resume (null = resume/last book). */
    val itemId: String? = null,
    /** CHAPTER: target chapter's whole-book start, in seconds. */
    val chapterStart: Double? = null,
    /** SPEED: new playback rate. */
    val speed: Float? = null,
    /** SLEEP: -1 = off, -2 = end of chapter, else a countdown in seconds. */
    val sleepSeconds: Long? = null,
    /** BOOST_VOLUME: desired on/off. */
    val boostOn: Boolean? = null,
)

enum class WatchCommandType {
    PLAY,
    PAUSE,
    SKIP_FORWARD,
    SKIP_BACKWARD,
    CHAPTER,
    SPEED,
    SLEEP,
    BOOST_VOLUME,
    REFRESH,
}
