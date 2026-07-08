package com.tortugapower.audiobookplayer.datalayer

/**
 * Constants for the phone↔watch Wear Data Layer contract, shared by `:app` (the responder) and `:wear`
 * (the requester) so the capability name and message path never drift between the two sides.
 */
object WearDataLayer {
    /** Capability the phone app advertises; the watch resolves the phone node by it via CapabilityClient. */
    const val CAPABILITY_PHONE = "bookplayer_phone"

    /** MessageClient path: watch → phone auth request (empty body). */
    const val PATH_AUTH = "/bookplayer/auth"

    /** MessageClient path: phone → watch reply, carrying the [WatchAuthCodec]-encoded account (or sentinel). */
    const val PATH_AUTH_RESPONSE = "/bookplayer/auth/response"

    /** Reply sentinel the phone sends when it has no signed-in account to hand off. */
    const val NOT_SIGNED_IN = "not_signed_in"

    // --- Remote-controller mode (phase 2): phone drives playback, watch is the remote. ---

    /**
     * DataClient item: the rarely-changing library snapshot (recent list + current item + skip intervals),
     * a [WatchLibraryState] encoded by [WatchRemoteCodec]. Latest-wins; survives reconnect.
     */
    const val PATH_LIBRARY_STATE = "/bookplayer/library"

    /**
     * DataClient item: the volatile playback state (isPlaying/speed/boost), a [WatchPlaybackState]. Kept
     * separate from the library item so play/pause re-pushes this tiny payload, never the recent list. Also
     * carries the play/pause echo — the phone's true state for ANY cause (call, sleep timer, book end).
     */
    const val PATH_PLAYBACK_STATE = "/bookplayer/playback"

    /** MessageClient path: watch → phone control command, a [WatchCommand] encoded by [WatchRemoteCodec]. */
    const val PATH_COMMAND = "/bookplayer/command"

    /**
     * DataClient item: the user's selected app theme colors, a [WatchTheme] encoded by [WatchRemoteCodec].
     * Theme choice is device-local on the phone (never backend-synced), so this is the only channel that
     * brings it to the watch. Latest-wins; survives reconnect, so the watch keeps the last theme offline.
     */
    const val PATH_THEME = "/bookplayer/theme"

    /** DataMap key under which both remote-state DataItems store their [WatchRemoteCodec] payload bytes. */
    const val KEY_PAYLOAD = "payload"
}
