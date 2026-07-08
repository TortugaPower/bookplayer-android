package com.tortugapower.audiobookplayer.datalayer

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
    val id: String,
    val title: String,
    val author: String,
)

/** A chapter of the current item. [start] is the whole-book offset in seconds. */
data class WatchChapter(
    val title: String,
    val start: Double,
    val index: Int,
)

/** The currently-loaded item, with chapters for the now-playing chapter list. */
data class WatchNowPlaying(
    val id: String,
    val title: String,
    val author: String,
    val chapters: List<WatchChapter>,
)

/** Rarely-changing library snapshot pushed to the watch (`PATH_LIBRARY_STATE`). */
data class WatchLibraryState(
    val recentItems: List<WatchItem>,
    val currentItem: WatchNowPlaying?,
    val rewindInterval: Int,
    val forwardInterval: Int,
)

/**
 * Volatile playback state pushed to the watch (`PATH_PLAYBACK_STATE`); the play/pause echo lives here.
 * [progress] (whole-book 0..1) and [currentChapter] (1-based; 0 = none) ride here — not on the library
 * item — so the watch's glance surfaces (tile/complication) show the phone's live progress + chapter for
 * free users too (iOS parity: the companion pushes the full current item), without re-sending the library.
 */
data class WatchPlaybackState(
    val isPlaying: Boolean,
    val speed: Float,
    val boostVolume: Boolean,
    val progress: Float = 0f,
    val currentChapter: Int = 0,
)
