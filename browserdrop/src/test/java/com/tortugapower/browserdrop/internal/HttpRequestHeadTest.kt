package com.tortugapower.browserdrop.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from SwiftBrowserDrop's `HTTPRequestHeadTests`. */
class HttpRequestHeadTest {
    private fun parse(text: String) = HttpRequestHead.parse(text.toByteArray(Charsets.UTF_8))

    @Test
    fun parsesRequestLineQueryAndHeaders() {
        val head = requireHead(
            parse(
                "POST /upload?path=Book%2F01%20One.mp3&path=ignored HTTP/1.1\r\nHost: 192.168.1.5\r\n" +
                    "Content-Length: 42\r\nX-Note: a\r\nx-note: b"
            )
        )
        assertEquals("POST", head.method)
        assertEquals("/upload", head.path)
        assertEquals("Book/01 One.mp3", head.query["path"])
        assertEquals(42L, head.contentLength)
        assertEquals("192.168.1.5", head.headers["host"])
        assertEquals("a, b", head.headers["x-note"])
        assertFalse(head.expectsContinue)
    }

    @Test
    fun keepsPlusSigns() {
        assertEquals("a+b.mp3", requireHead(parse("POST /upload?path=a+b.mp3 HTTP/1.1\r\nHost: x")).query["path"])
    }

    @Test
    fun decodesUtf8Escapes() {
        assertEquals("Caf\u00E9.mp3", requireHead(parse("POST /upload?path=Caf%C3%A9.mp3 HTTP/1.1")).query["path"])
    }

    @Test
    fun readsExpectContinue() {
        assertTrue(requireHead(parse("POST /upload HTTP/1.1\r\nExpect: 100-Continue")).expectsContinue)
    }

    @Test
    fun acceptsRepeatedEqualLengths() {
        assertEquals(3L, requireHead(parse("POST /upload HTTP/1.1\r\nContent-Length: 3\r\nContent-Length: 3")).contentLength)
    }

    @Test
    fun refusesMalformedHeads() {
        val heads = listOf(
            "", "GET / HTTP/2.0", "GET  / HTTP/1.1", "get / HTTP/1.1", "GET http://x/ HTTP/1.1", "GET /",
            "POST /upload HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2",
            "POST /upload HTTP/1.1\r\nContent-Length: -1",
            "POST /upload HTTP/1.1\r\nContent-Length: 1e3",
            "POST /upload HTTP/1.1\r\nContent-Length: ",
            "POST /upload HTTP/1.1\r\nContent-Length: 99999999999999999999",
            "GET / HTTP/1.1\r\nHost: a\r\n folded",
            "GET / HTTP/1.1\r\nNoColon",
            "GET / HTTP/1.1\r\nBad Name: x",
            "POST /upload?path=%G1.mp3 HTTP/1.1",
            "POST /upload?path=%FF.mp3 HTTP/1.1",
            "POST /upload?path=a% HTTP/1.1"
        )
        for (text in heads) assertNull("head: $text", parse(text))
    }

    @Test
    fun refusesInvalidUtf8() {
        assertNull(HttpRequestHead.parse(byteArrayOf('G'.code.toByte(), 0xC3.toByte(), ' '.code.toByte())))
    }

    private fun requireHead(head: HttpRequestHead?): HttpRequestHead =
        head ?: throw AssertionError("expected the head to parse")
}
