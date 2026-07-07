package com.tortugapower.audiobookplayer.wear.presentation

import com.tortugapower.audiobookplayer.datalayer.WatchCommand
import com.tortugapower.audiobookplayer.datalayer.WatchCommandType
import com.tortugapower.audiobookplayer.datalayer.WatchItem
import com.tortugapower.audiobookplayer.datalayer.WatchLibraryState
import com.tortugapower.audiobookplayer.datalayer.WatchPlaybackState
import com.tortugapower.audiobookplayer.wear.data.RemoteCommandSender
import com.tortugapower.audiobookplayer.wear.data.RemoteContextRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeRepo : RemoteContextRepository {
        val library = MutableStateFlow<WatchLibraryState?>(null)
        val playback = MutableStateFlow<WatchPlaybackState?>(null)
        override val libraryState: Flow<WatchLibraryState?> = library
        override val playbackState: Flow<WatchPlaybackState?> = playback
    }

    private class FakeSender : RemoteCommandSender {
        val sent = mutableListOf<WatchCommand>()
        override suspend fun send(command: WatchCommand) { sent += command }
    }

    private val repo = FakeRepo()
    private val sender = FakeSender()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // `state` is a WhileSubscribed StateFlow, so it only merges upstream while collected. Start a
    // background collector (as the real UI does via collectAsStateWithLifecycle) so state.value updates.
    private fun TestScope.model(): RemoteViewModel =
        RemoteViewModel(repo, sender).also { vm -> backgroundScope.launch { vm.state.collect {} } }

    @Test fun connecting_untilStateArrives() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        assertTrue(vm.state.value.connecting)

        repo.library.value = WatchLibraryState(
            recentItems = listOf(WatchItem("a.m4b", "Book A", "Author A")),
            currentItem = null,
            rewindInterval = 30,
            forwardInterval = 30,
        )
        advanceUntilIdle()
        assertFalse(vm.state.value.connecting)
        assertEquals(listOf("a.m4b"), vm.state.value.recentItems.map { it.id })
    }

    @Test fun isPlaying_reflectsPlaybackEcho() = runTest(dispatcher) {
        val vm = model()
        repo.playback.value = WatchPlaybackState(isPlaying = true, speed = 1.0f, boostVolume = false)
        advanceUntilIdle()
        assertTrue(vm.state.value.isPlaying)
    }

    @Test fun playItem_sendsPlayWithId_andOptimisticallyPlays() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        vm.playItem("b.m4b")
        advanceUntilIdle()
        assertEquals(WatchCommand(WatchCommandType.PLAY, itemId = "b.m4b"), sender.sent.single())
        assertTrue(vm.state.value.isPlaying)
    }

    @Test fun togglePlayPause_pausesWhenPlaying() = runTest(dispatcher) {
        val vm = model()
        repo.playback.value = WatchPlaybackState(isPlaying = true, speed = 1.0f, boostVolume = false)
        advanceUntilIdle()
        vm.togglePlayPause()
        advanceUntilIdle()
        assertEquals(WatchCommandType.PAUSE, sender.sent.single().type)
        assertFalse(vm.state.value.isPlaying)
    }

    @Test fun togglePlayPause_playsWhenPaused() = runTest(dispatcher) {
        val vm = model()
        repo.playback.value = WatchPlaybackState(isPlaying = false, speed = 1.0f, boostVolume = false)
        advanceUntilIdle()
        vm.togglePlayPause()
        advanceUntilIdle()
        assertEquals(WatchCommandType.PLAY, sender.sent.single().type)
        assertTrue(vm.state.value.isPlaying)
    }

    @Test fun playbackEcho_clearsOptimisticOverride() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        vm.playItem("b.m4b")           // optimistic isPlaying = true
        advanceUntilIdle()
        assertTrue(vm.state.value.isPlaying)

        // Authoritative echo says paused -> optimistic cleared, real state wins.
        repo.playback.value = WatchPlaybackState(isPlaying = false, speed = 1.0f, boostVolume = false)
        advanceUntilIdle()
        assertFalse(vm.state.value.isPlaying)
    }

    @Test fun refresh_sendsRefresh() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(WatchCommandType.REFRESH, sender.sent.single().type)
    }

    @Test fun skips_sendSkipCommands() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        vm.skipForward()
        vm.skipBackward()
        advanceUntilIdle()
        assertEquals(
            listOf(WatchCommandType.SKIP_FORWARD, WatchCommandType.SKIP_BACKWARD),
            sender.sent.map { it.type },
        )
    }

    @Test fun seekChapter_sendsChapterStart() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        vm.seekChapter(123.5)
        advanceUntilIdle()
        assertEquals(WatchCommand(WatchCommandType.CHAPTER, chapterStart = 123.5), sender.sent.single())
    }

    @Test fun increaseSpeed_addsStepFromCurrent() = runTest(dispatcher) {
        val vm = model()
        repo.playback.value = WatchPlaybackState(isPlaying = true, speed = 1.5f, boostVolume = false)
        advanceUntilIdle()
        vm.increaseSpeed()
        advanceUntilIdle()
        val cmd = sender.sent.single()
        assertEquals(WatchCommandType.SPEED, cmd.type)
        assertEquals(1.6f, cmd.speed!!, 0.001f)
    }

    @Test fun decreaseSpeed_clampsAtMin() = runTest(dispatcher) {
        val vm = model()
        repo.playback.value = WatchPlaybackState(isPlaying = true, speed = 0.5f, boostVolume = false)
        advanceUntilIdle()
        vm.decreaseSpeed()
        advanceUntilIdle()
        assertEquals(0.5f, sender.sent.single().speed!!, 0.001f)
    }

    @Test fun rapidIncreaseSpeed_accumulatesOptimistically() = runTest(dispatcher) {
        val vm = model()
        repo.playback.value = WatchPlaybackState(isPlaying = true, speed = 1.5f, boostVolume = false)
        advanceUntilIdle()
        vm.increaseSpeed()
        vm.increaseSpeed() // before any echo — should build on the optimistic 1.6, not the stale 1.5
        advanceUntilIdle()
        assertEquals(1.6f, sender.sent[0].speed!!, 0.001f)
        assertEquals(1.7f, sender.sent[1].speed!!, 0.001f)
    }

    @Test fun cycleSpeed_wrapsPastMax() = runTest(dispatcher) {
        val vm = model()
        repo.playback.value = WatchPlaybackState(isPlaying = true, speed = 4.0f, boostVolume = false)
        advanceUntilIdle()
        vm.cycleSpeed()
        advanceUntilIdle()
        assertEquals(0.5f, sender.sent.single().speed!!, 0.001f)
    }

    @Test fun sleep_sendsSentinelsAndCountdown() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        vm.sleepOff()
        vm.sleepEndOfChapter()
        vm.sleepAfter(15)
        advanceUntilIdle()
        assertEquals(listOf(-1L, -2L, 900L), sender.sent.map { it.sleepSeconds })
    }

    @Test fun toggleBoost_sendsInverseOfCurrent() = runTest(dispatcher) {
        val vm = model()
        repo.playback.value = WatchPlaybackState(isPlaying = false, speed = 1.0f, boostVolume = false)
        advanceUntilIdle()
        vm.toggleBoost()
        advanceUntilIdle()
        assertEquals(true, sender.sent.single().boostOn)
    }
}
