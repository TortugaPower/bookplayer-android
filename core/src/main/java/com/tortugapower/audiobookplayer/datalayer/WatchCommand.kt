package com.tortugapower.audiobookplayer.datalayer

import com.google.gson.annotations.SerializedName

/**
 * A control command the watch sends to the phone (`PATH_COMMAND`) in remote-controller mode; the phone maps
 * it onto its `PlaybackManager`/`SleepTimerManager`. Mirrors iOS's companion command vocabulary, typed
 * instead of stringly-keyed. Only the fields relevant to a given [type] are set.
 */
data class WatchCommand(
    @SerializedName("type") val type: WatchCommandType,
    /** PLAY: the item's `relativePath` to load/resume (null = resume/last book). */
    @SerializedName("itemId") val itemId: String? = null,
    /** CHAPTER: target chapter's whole-book start, in seconds. */
    @SerializedName("chapterStart") val chapterStart: Double? = null,
    /** SPEED: new playback rate. */
    @SerializedName("speed") val speed: Float? = null,
    /** SLEEP: -1 = off, -2 = end of chapter, else a countdown in seconds. */
    @SerializedName("sleepSeconds") val sleepSeconds: Long? = null,
    /** BOOST_VOLUME: desired on/off. */
    @SerializedName("boostOn") val boostOn: Boolean? = null,
    /** VOLUME: crown nudge — true = one step up, false = one step down (the phone's media-stream volume). */
    @SerializedName("volumeUp") val volumeUp: Boolean? = null,
)

enum class WatchCommandType {
    @SerializedName("PLAY") PLAY,
    @SerializedName("PAUSE") PAUSE,
    @SerializedName("SKIP_FORWARD") SKIP_FORWARD,
    @SerializedName("SKIP_BACKWARD") SKIP_BACKWARD,
    @SerializedName("CHAPTER") CHAPTER,
    @SerializedName("SPEED") SPEED,
    @SerializedName("SLEEP") SLEEP,
    @SerializedName("BOOST_VOLUME") BOOST_VOLUME,
    @SerializedName("VOLUME") VOLUME,
    @SerializedName("REFRESH") REFRESH,
}

/** Sentinel values for [WatchCommand.sleepSeconds], shared by the watch (encodes) and phone (decodes). */
object WatchSleepSentinel {
    const val OFF = -1L
    const val END_OF_CHAPTER = -2L
}
