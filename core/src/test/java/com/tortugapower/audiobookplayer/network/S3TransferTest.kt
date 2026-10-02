package com.tortugapower.audiobookplayer.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

/** A part PUT carries exactly its byte range and nothing a presigned URL would refuse */
class S3TransferTest {

    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    private fun file(size: Int) = folder.newFile().apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }

    @Test fun aPart_sendsItsRange_withItsLength_andNoAuthOrType() = runBlocking {
        val file = file(1_000)
        server.enqueue(MockResponse().setResponseCode(200))
        var lastProgress = 0L

        val status = S3Transfer.putPart(server.url("/key?X-Amz-Signature=s").toString(), file, offset = 300, length = 400) { lastProgress = it }

        val request = server.takeRequest()
        assertEquals(200, status)
        assertEquals("PUT", request.method)
        assertEquals("400", request.getHeader("Content-Length"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Content-Type"))
        assertArrayEquals(file.readBytes().copyOfRange(300, 700), request.body.readByteArray())
        assertEquals(400L, lastProgress)
    }

    @Test fun theStatus_isReturned_notThrown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(403, S3Transfer.putPart(server.url("/key").toString(), file(10), 0, 10))
    }

    @Test fun aContainersPut_isEmpty() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(200, S3Transfer.putEmpty(server.url("/folder").toString()))
        val request = server.takeRequest()
        assertEquals("0", request.getHeader("Content-Length"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun cancellingTheCaller_cancelsTheRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val put = async { S3Transfer.putPart(server.url("/slow").toString(), file(10), 0, 10) }
        server.takeRequest(5, TimeUnit.SECONDS)
        delay(100)
        assertEquals(1, S3Transfer.client.dispatcher.runningCallsCount())
        put.cancel()
        val thrown = runCatching { withTimeout(2_000) { put.await() } }.exceptionOrNull()
        assertTrue("$thrown", thrown is CancellationException)
        // The call itself is cancelled, not just the wait for it
        withTimeout(2_000) { while (S3Transfer.client.dispatcher.runningCallsCount() > 0) delay(20) }
    }

    @Test fun aFailingProgressCallback_doesntFailThePart() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(200, S3Transfer.putPart(server.url("/key").toString(), file(10), 0, 10) { error("bug") })
    }

    @Test fun aRangePastTheEndOfTheFile_failsTheWrite() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val thrown = runCatching { S3Transfer.putPart(server.url("/short").toString(), file(10), 5, 10) }.exceptionOrNull()
        assertTrue("$thrown", thrown is java.io.IOException)
    }
}
