package com.tortugapower.audiobookplayer.datalayer

import com.google.gson.annotations.SerializedName

/**
 * The state the phone publishes to the watch for remote-controller mode, and the model the watch renders.
 * Two independent DataItems (see [WearDataLayer]) so play/pause only re-sends the small [WatchPlaybackState]:
 *
 *  - [WatchLibraryState] → `PATH_LIBRARY_STATE` — changes rarely (item change / refresh / settings).
 *  - [WatchPlaybackState] → `PATH_PLAYBACK_STATE` — volatile (play/pause/speed/boost); carries the play-state echo.
 *
 * Deliberately minimal to match iOS's companion payload: recent rows are title + author (no artwork, no
 * progress); chapters ride only on the current item (the chapter list is reachable only from now-playing).
 */

/** A recently-played item as a remote-list row. [id] is the item's `relativePath` (used to play it). */
data class WatchItem(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("author") val author: String,
)

/** A chapter of the current item. [start] is the whole-book offset in seconds. */
data class WatchChapter(
    @SerializedName("title") val title: String,
    @SerializedName("start") val start: Double,
    @SerializedName("index") val index: Int,
)

/** The currently-loaded item, with chapters for the now-playing chapter list. */
data class WatchNowPlaying(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("author") val author: String,
    @SerializedName("chapters") val chapters: List<WatchChapter>,
)

/** Rarely-changing library snapshot pushed to the watch (`PATH_LIBRARY_STATE`). */
data class WatchLibraryState(
    @SerializedName("recentItems") val recentItems: List<WatchItem>,
    @SerializedName("currentItem") val currentItem: WatchNowPlaying?,
    @SerializedName("rewindInterval") val rewindInterval: Int,
    @SerializedName("forwardInterval") val forwardInterval: Int,
)

/**
 * Volatile playback state pushed to the watch (`PATH_PLAYBACK_STATE`); the play/pause echo lives here.
 * [progress] (whole-book 0..1) and [currentChapter] (1-based; 0 = none) ride here — not on the library
 * item — so the watch's glance surfaces (tile/complication) show the phone's live progress + chapter for
 * free users too (iOS parity: the companion pushes the full current item), without re-sending the library.
 */
data class WatchPlaybackState(
    @SerializedName("isPlaying") val isPlaying: Boolean,
    @SerializedName("speed") val speed: Float,
    @SerializedName("boostVolume") val boostVolume: Boolean,
    @SerializedName("progress") val progress: Float = 0f,
    @SerializedName("currentChapter") val currentChapter: Int = 0,
)
