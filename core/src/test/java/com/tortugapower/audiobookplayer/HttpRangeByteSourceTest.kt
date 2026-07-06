package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.HttpRangeByteSource
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Covers HttpRangeByteSource's Range mechanics: 206 assembly, Content-Range size, read-ahead cache
 *  hits, and the 200 (Range-ignored) → unsupported fallback. */
class HttpRangeByteSourceTest {

    private lateinit var server: MockWebServer
    private val data = ByteArray(4096) { (it % 251).toByte() } // deterministic body

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    /** Serves real byte ranges from [data] with 206 + Content-Range, counting requests. */
    private fun serveRanges(): () -> Int {
        var requests = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests++
                val range = request.getHeader("Range")!!.removePrefix("bytes=").split("-")
                val start = range[0].toInt()
                val end = minOf(range[1].toInt(), data.size - 1)
                val slice = data.copyOfRange(start, end + 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                    .setBody(Buffer().write(slice))
            }
        }
        return { requests }
    }

    @Test fun sizeFromContentRange_andExactReads() {
        serveRanges()
        val src = HttpRangeByteSource(server.url("/f").toString(), headers = null)
        assertEquals(4096L, src.size())
        assertArrayEquals(data.copyOfRange(0, 10), src.readAt(0, 10))
        assertArrayEquals(data.copyOfRange(100, 132), src.readAt(100, 32))
        src.close()
    }

    @Test fun readAhead_servesNearbyReadsWithoutNewRequests() {
        val count = serveRanges()
        val src = HttpRangeByteSource(server.url("/f").toString(), headers = null, readAheadBytes = 1024)
        assertArrayEquals(data.copyOfRange(0, 8), src.readAt(0, 8))   // fetches a 1024-byte window
        val after = count()
        assertArrayEquals(data.copyOfRange(8, 24), src.readAt(8, 16)) // within window → cache hit
        assertEquals("nearby read should not hit the network", after, count())
        src.close()
    }

    @Test fun size_primesHeadCache_soFirstReadIsCacheHit() {
        val count = serveRanges()
        val src = HttpRangeByteSource(server.url("/f").toString(), headers = null, readAheadBytes = 1024)
        assertEquals(4096L, src.size())
        val afterSize = count()
        assertArrayEquals(data.copyOfRange(0, 16), src.readAt(0, 16)) // within the primed head window
        assertEquals("size() should prime the head; first read must not hit the network", afterSize, count())
        src.close()
    }

    @Test fun partialAtEof() {
        serveRanges()
        val src = HttpRangeByteSource(server.url("/f").toString(), headers = null, readAheadBytes = 64)
        val tail = src.readAt(4090, 64) // only 6 bytes remain
        assertEquals(6, tail?.size)
        src.close()
    }

    @Test fun rangeIgnored200_isUnsupported() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody(Buffer().write(data)) // ignores Range
        }
        val src = HttpRangeByteSource(server.url("/f").toString(), headers = null)
        assertNull(src.readAt(0, 10))
        assertTrue(src.size() <= 0) // never learned a size
        src.close()
    }

    @Test fun sendsAuthHeaders() {
        serveRanges()
        val src = HttpRangeByteSource(server.url("/f").toString(), headers = mapOf("Authorization" to "Bearer xyz"))
        src.readAt(0, 4)
        assertEquals("Bearer xyz", server.takeRequest().getHeader("Authorization"))
        src.close()
    }
}
