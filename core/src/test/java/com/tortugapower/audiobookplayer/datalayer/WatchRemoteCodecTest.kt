package com.tortugapower.audiobookplayer.datalayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The codec is the shared contract for remote-controller mode, so its round-trips and the drop-on-malformed
 * / drop-on-incomplete behavior are pinned here (the DataClient/MessageClient transport can't be unit-tested).
 */
class WatchRemoteCodecTest {

    private val library = WatchLibraryState(
        recentItems = listOf(
            WatchItem(id = "a.m4b", title = "Book A", author = "Author A"),
            WatchItem(id = "b.m4b", title = "Book B", author = "Author B"),
        ),
        currentItem = WatchNowPlaying(
            id = "a.m4b",
            title = "Book A",
            author = "Author A",
            chapters = listOf(
                WatchChapter(title = "Ch 1", start = 0.0, index = 0),
                WatchChapter(title = "Ch 2", start = 123.5, index = 1),
            ),
        ),
        rewindInterval = 30,
        forwardInterval = 30,
    )

    private val playback = WatchPlaybackState(isPlaying = true, speed = 1.5f, boostVolume = true)

    // --- round trips ---

    @Test fun libraryState_roundTrips() {
        val decoded = WatchRemoteCodec.decodeLibraryState(WatchRemoteCodec.encodeLibraryState(library))
        assertEquals(library, decoded)
    }

    @Test fun libraryState_roundTrips_withNoCurrentItem() {
        val state = library.copy(currentItem = null)
        val decoded = WatchRemoteCodec.decodeLibraryState(WatchRemoteCodec.encodeLibraryState(state))
        assertEquals(state, decoded)
    }

    @Test fun playbackState_roundTrips() {
        val decoded = WatchRemoteCodec.decodePlaybackState(WatchRemoteCodec.encodePlaybackState(playback))
        assertEquals(playback, decoded)
    }

    @Test fun command_roundTrips_play() {
        val cmd = WatchCommand(type = WatchCommandType.PLAY, itemId = "a.m4b")
        val decoded = WatchRemoteCodec.decodeCommand(WatchRemoteCodec.encodeCommand(cmd))
        assertEquals(cmd, decoded)
    }

    @Test fun command_roundTrips_allOptionalFields() {
        val cmd = WatchCommand(
            type = WatchCommandType.SLEEP,
            chapterStart = 10.0,
            speed = 2.0f,
            sleepSeconds = -2L,
            boostOn = true,
        )
        val decoded = WatchRemoteCodec.decodeCommand(WatchRemoteCodec.encodeCommand(cmd))
        assertEquals(cmd, decoded)
    }

    // --- malformed / incomplete → null ---

    @Test fun decodeGarbage_isNull() {
        assertNull(WatchRemoteCodec.decodeLibraryState("{ not json".toByteArray()))
        assertNull(WatchRemoteCodec.decodePlaybackState("{ not json".toByteArray()))
        assertNull(WatchRemoteCodec.decodeCommand("{ not json".toByteArray()))
    }

    @Test fun libraryState_itemMissingId_isNull() {
        val json = """{"recentItems":[{"title":"T","author":"A"}],"rewindInterval":30,"forwardInterval":30}"""
        assertNull(WatchRemoteCodec.decodeLibraryState(json.toByteArray()))
    }

    @Test fun libraryState_itemBlankId_isNull() {
        val json =
            """{"recentItems":[{"id":"","title":"T","author":"A"}],"rewindInterval":30,"forwardInterval":30}"""
        assertNull(WatchRemoteCodec.decodeLibraryState(json.toByteArray()))
    }

    @Test fun playbackState_missingSpeed_isNull() {
        // No speed field -> Gson defaults the primitive to 0f -> treated as an incomplete payload.
        val json = """{"isPlaying":true,"boostVolume":false}"""
        assertNull(WatchRemoteCodec.decodePlaybackState(json.toByteArray()))
    }

    @Test fun command_missingType_isNull() {
        val json = """{"itemId":"a.m4b"}"""
        assertNull(WatchRemoteCodec.decodeCommand(json.toByteArray()))
    }

    private val theme = WatchTheme(
        accentHex = "459EEC",
        primaryHex = "FAFBFC",
        secondaryHex = "8F8E94",
        backgroundHex = "202225",
        surfaceHex = "111113",
        separatorHex = "434448",
    )

    @Test fun theme_roundTrips() {
        assertEquals(theme, WatchRemoteCodec.decodeTheme(WatchRemoteCodec.encodeTheme(theme)))
    }

    @Test fun theme_missingField_isNull() {
        val json = """{"accentHex":"459EEC","primaryHex":"FAFBFC","secondaryHex":"8F8E94"}"""
        assertNull(WatchRemoteCodec.decodeTheme(json.toByteArray()))
    }

    @Test fun theme_malformedHex_isNull() {
        val json = """{"accentHex":"nothex","primaryHex":"FAFBFC","secondaryHex":"8F8E94","backgroundHex":"202225","surfaceHex":"111113","separatorHex":"434448"}"""
        assertNull(WatchRemoteCodec.decodeTheme(json.toByteArray()))
    }
}
