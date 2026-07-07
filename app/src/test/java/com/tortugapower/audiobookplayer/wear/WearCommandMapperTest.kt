package com.tortugapower.audiobookplayer.wear

import com.tortugapower.audiobookplayer.datalayer.WatchCommand
import com.tortugapower.audiobookplayer.datalayer.WatchCommandType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The command→action routing is the phone's half of the remote-control contract, so its decision table
 * (incl. the sleep sentinels and the "ignore a command missing its argument" cases) is pinned here.
 */
class WearCommandMapperTest {

    private class RecordingActions : RemotePlaybackActions {
        val calls = mutableListOf<String>()
        override fun play(itemId: String?) { calls += "play:$itemId" }
        override fun pause() { calls += "pause" }
        override fun skipForward() { calls += "skipForward" }
        override fun skipBackward() { calls += "skipBackward" }
        override fun seekToChapter(startSeconds: Double) { calls += "chapter:$startSeconds" }
        override fun setSpeed(speed: Float) { calls += "speed:$speed" }
        override fun sleepOff() { calls += "sleepOff" }
        override fun sleepEndOfChapter() { calls += "sleepEndOfChapter" }
        override fun sleepAfter(seconds: Long) { calls += "sleepAfter:$seconds" }
        override fun setBoost(on: Boolean) { calls += "boost:$on" }
        override fun refresh() { calls += "refresh" }
    }

    private fun dispatch(command: WatchCommand): List<String> =
        RecordingActions().also { WearCommandMapper.dispatch(command, it) }.calls

    @Test fun play_forwardsItemId() =
        assertEquals(listOf("play:a.m4b"), dispatch(WatchCommand(WatchCommandType.PLAY, itemId = "a.m4b")))

    @Test fun play_nullItemId_resumes() =
        assertEquals(listOf("play:null"), dispatch(WatchCommand(WatchCommandType.PLAY)))

    @Test fun pause_maps() =
        assertEquals(listOf("pause"), dispatch(WatchCommand(WatchCommandType.PAUSE)))

    @Test fun skips_map() {
        assertEquals(listOf("skipForward"), dispatch(WatchCommand(WatchCommandType.SKIP_FORWARD)))
        assertEquals(listOf("skipBackward"), dispatch(WatchCommand(WatchCommandType.SKIP_BACKWARD)))
    }

    @Test fun chapter_forwardsStart() =
        assertEquals(listOf("chapter:123.5"), dispatch(WatchCommand(WatchCommandType.CHAPTER, chapterStart = 123.5)))

    @Test fun chapter_missingStart_ignored() =
        assertEquals(emptyList<String>(), dispatch(WatchCommand(WatchCommandType.CHAPTER)))

    @Test fun speed_forwardsRate() =
        assertEquals(listOf("speed:1.5"), dispatch(WatchCommand(WatchCommandType.SPEED, speed = 1.5f)))

    @Test fun speed_missingRate_ignored() =
        assertEquals(emptyList<String>(), dispatch(WatchCommand(WatchCommandType.SPEED)))

    @Test fun sleep_off() =
        assertEquals(listOf("sleepOff"), dispatch(WatchCommand(WatchCommandType.SLEEP, sleepSeconds = -1L)))

    @Test fun sleep_endOfChapter() =
        assertEquals(listOf("sleepEndOfChapter"), dispatch(WatchCommand(WatchCommandType.SLEEP, sleepSeconds = -2L)))

    @Test fun sleep_countdown() =
        assertEquals(listOf("sleepAfter:300"), dispatch(WatchCommand(WatchCommandType.SLEEP, sleepSeconds = 300L)))

    @Test fun sleep_missingSeconds_ignored() =
        assertEquals(emptyList<String>(), dispatch(WatchCommand(WatchCommandType.SLEEP)))

    @Test fun boost_forwardsState() {
        assertEquals(listOf("boost:true"), dispatch(WatchCommand(WatchCommandType.BOOST_VOLUME, boostOn = true)))
        assertEquals(listOf("boost:false"), dispatch(WatchCommand(WatchCommandType.BOOST_VOLUME, boostOn = false)))
    }

    @Test fun boost_missingState_ignored() =
        assertEquals(emptyList<String>(), dispatch(WatchCommand(WatchCommandType.BOOST_VOLUME)))

    @Test fun refresh_maps() =
        assertEquals(listOf("refresh"), dispatch(WatchCommand(WatchCommandType.REFRESH)))
}
