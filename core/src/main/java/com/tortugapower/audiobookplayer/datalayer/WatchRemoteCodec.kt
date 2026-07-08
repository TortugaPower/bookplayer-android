package com.tortugapower.audiobookplayer.datalayer

import com.google.gson.Gson

/**
 * Wire codec for remote-controller mode, shared by `:app` (phone encodes state / decodes commands) and
 * `:wear` (watch decodes state / encodes commands) so the two sides can't disagree on the format. Pure (no
 * Android/Wearable types) so the whole round-trip is unit-tested without a device — only the transport
 * (DataClient/MessageClient) needs one.
 *
 * Every `decode*` returns null for bytes that are malformed OR well-formed-but-incomplete: Gson bypasses
 * Kotlin null-safety, so a JSON object missing a field yields a payload with nulls in non-null properties.
 * We validate the fields each side relies on and drop anything that doesn't hold up, rather than surface a
 * value that NPEs downstream (same approach as [WatchAuthCodec]).
 */
object WatchRemoteCodec {
    private val gson = Gson()

    fun encodeLibraryState(state: WatchLibraryState): ByteArray = state.toBytes()
    fun encodePlaybackState(state: WatchPlaybackState): ByteArray = state.toBytes()
    fun encodeCommand(command: WatchCommand): ByteArray = command.toBytes()
    fun encodeTheme(theme: WatchTheme): ByteArray = theme.toBytes()

    fun decodeLibraryState(bytes: ByteArray): WatchLibraryState? =
        parse(bytes, WatchLibraryState::class.java)?.takeIf { it.isValid() }

    fun decodePlaybackState(bytes: ByteArray): WatchPlaybackState? =
        parse(bytes, WatchPlaybackState::class.java)?.takeIf { it.isValid() }

    fun decodeCommand(bytes: ByteArray): WatchCommand? =
        parse(bytes, WatchCommand::class.java)?.takeIf { it.isValid() }

    fun decodeTheme(bytes: ByteArray): WatchTheme? =
        parse(bytes, WatchTheme::class.java)?.takeIf { it.isValid() }

    private fun Any.toBytes(): ByteArray = gson.toJson(this).toByteArray(Charsets.UTF_8)

    private fun <T> parse(bytes: ByteArray, type: Class<T>): T? =
        try {
            gson.fromJson(bytes.toString(Charsets.UTF_8), type)
        } catch (e: Exception) {
            null
        }
}

// Gson can leave declared-non-null fields null when the JSON omits them; the != null checks below are
// "always true" to the compiler (SENSELESS_COMPARISON) but genuinely guard those runtime nulls.
@Suppress("SENSELESS_COMPARISON")
private fun WatchLibraryState.isValid(): Boolean =
    recentItems != null && recentItems.all { it.isValid() } &&
        (currentItem == null || currentItem.isValid()) &&
        rewindInterval >= 0 && forwardInterval >= 0

@Suppress("SENSELESS_COMPARISON")
private fun WatchItem.isValid(): Boolean =
    id != null && id.isNotBlank() && title != null && author != null

@Suppress("SENSELESS_COMPARISON")
private fun WatchNowPlaying.isValid(): Boolean =
    id != null && id.isNotBlank() && title != null && author != null &&
        chapters != null && chapters.all { it.isValid() }

@Suppress("SENSELESS_COMPARISON")
private fun WatchChapter.isValid(): Boolean =
    title != null && start >= 0.0 && index >= 0

// isPlaying/boostVolume are primitives (default false when absent, harmless); a positive speed is the tell
// that the payload was actually populated rather than defaulted from a missing field.
private fun WatchPlaybackState.isValid(): Boolean = speed > 0f

@Suppress("SENSELESS_COMPARISON")
private fun WatchCommand.isValid(): Boolean = type != null

// Every color field must be present and a parseable RRGGBB hex; a payload missing any is dropped rather
// than yielding a theme that renders as transparent/black on the watch.
@Suppress("SENSELESS_COMPARISON")
private fun WatchTheme.isValid(): Boolean =
    listOf(accentHex, primaryHex, secondaryHex, backgroundHex, surfaceHex, separatorHex)
        .all { it != null && it.matches(Regex("^[0-9a-fA-F]{6}$")) }
