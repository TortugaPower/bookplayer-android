package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.logic.JellyfinQuickConnect.Failure
import com.tortugapower.audiobookplayer.logic.JellyfinQuickConnect.State
import com.tortugapower.audiobookplayer.logic.JellyfinQuickConnect.Ticket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The Quick Connect state machine under virtual time: every transition the sheet renders, the three
 * failure reasons the UI maps to copy, and that stopping really stops (a poller left running after
 * the user cancelled would keep hitting the server for its full ~16-minute budget).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JellyfinQuickConnectTest {

    private class FakeTransport(
        var ticket: Ticket? = Ticket(secret = "s3cr3t", code = "7H2K9Q"),
        var approvedAfterPolls: Int = Int.MAX_VALUE,
        var initiateError: Exception? = null,
        var pollError: Exception? = null,
    ) : JellyfinQuickConnect.Transport {
        var initiateCalls = 0
        var polls = 0

        override suspend fun initiate(): Ticket? {
            initiateCalls++
            initiateError?.let { throw it }
            return ticket
        }

        override suspend fun isAuthorized(secret: String): Boolean {
            polls++
            pollError?.let { throw it }
            return polls > approvedAfterPolls
        }
    }

    private fun runTest(block: suspend kotlinx.coroutines.test.TestScope.(record: MutableList<State>, transport: FakeTransport, qc: JellyfinQuickConnect) -> Unit) =
        kotlinx.coroutines.test.runTest {
            val transport = FakeTransport()
            val qc = JellyfinQuickConnect(transport, pollIntervalMs = 5_000, maxPolls = 3)
            val record = mutableListOf<State>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { qc.state.collect { record += it } }
            block(record, transport, qc)
        }

    @Test fun `happy path walks retrieving, awaiting, authenticated`() = runTest { record, transport, qc ->
        transport.approvedAfterPolls = 2
        qc.start(this)
        advanceUntilIdle()

        assertEquals(
            listOf(State.Idle, State.RetrievingCode, State.AwaitingCode("7H2K9Q"), State.Authenticated("s3cr3t")),
            record,
        )
        assertEquals(1, transport.initiateCalls)
        assertEquals(3, transport.polls)
    }

    @Test fun `polls wait the interval between attempts`() = runTest { _, transport, qc ->
        transport.approvedAfterPolls = 1
        qc.start(this)
        runCurrent()
        assertEquals("first poll is immediate", 1, transport.polls)
        advanceTimeBy(4_999)
        assertEquals(1, transport.polls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, transport.polls)
        assertEquals(State.Authenticated("s3cr3t"), qc.state.value)
    }

    @Test fun `a server that answers without a code fails with NO_CODE`() = runTest { record, transport, qc ->
        transport.ticket = null
        qc.start(this)
        advanceUntilIdle()

        assertEquals(State.Failed(Failure.NO_CODE), qc.state.value)
        assertTrue(record.none { it is State.AwaitingCode })
        assertEquals(0, transport.polls)
    }

    @Test fun `exhausting the polling budget fails with TIMEOUT`() = runTest { _, transport, qc ->
        qc.start(this)
        advanceUntilIdle()

        assertEquals(State.Failed(Failure.TIMEOUT), qc.state.value)
        assertEquals(3, transport.polls)
    }

    @Test fun `transport errors fail with OTHER, never with the raw exception`() = runTest { _, transport, qc ->
        transport.initiateError = IOException("HTTP 401")
        qc.start(this)
        advanceUntilIdle()
        assertEquals(State.Failed(Failure.OTHER), qc.state.value)

        qc.stop()
        transport.initiateError = null
        transport.pollError = IOException("HTTP 404 Unknown secret")
        qc.start(this)
        advanceUntilIdle()
        assertEquals(State.Failed(Failure.OTHER), qc.state.value)
    }

    @Test fun `stop cancels the poller and resets to idle`() = runTest { record, transport, qc ->
        qc.start(this)
        runCurrent()
        assertEquals(State.AwaitingCode("7H2K9Q"), qc.state.value)
        val pollsAtStop = transport.polls

        qc.stop()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(State.Idle, qc.state.value)
        assertEquals("no poll may land after stop", pollsAtStop, transport.polls)
        assertEquals(State.Idle, record.last())
    }

    @Test fun `start is a no-op while a flow is running or finished`() = runTest { _, transport, qc ->
        qc.start(this)
        runCurrent()
        qc.start(this)
        advanceUntilIdle()
        assertEquals("a second start must not initiate again", 1, transport.initiateCalls)

        // Finished (timed out) flows stay put until stopped.
        qc.start(this)
        advanceUntilIdle()
        assertEquals(1, transport.initiateCalls)

        qc.stop()
        qc.start(this)
        advanceUntilIdle()
        assertEquals(2, transport.initiateCalls)
    }
}
