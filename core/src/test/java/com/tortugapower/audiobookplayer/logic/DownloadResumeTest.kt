package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a resume sends as If-Range, and which answers continue a part (DownloadResume) */
class DownloadResumeTest {

    private val date = "Fri, 05 Jun 2026 01:20:46 GMT"

    @Test fun `a strong ETag is the validator`() {
        assertEquals("\"f7a7d96a\"", DownloadResume.validator("\"f7a7d96a\"", date))
    }

    /** If-Range can't use a weak ETag (Audiobookshelf's): its date can */
    @Test fun `a weak ETag gives way to the date`() {
        assertEquals(date, DownloadResume.validator("W/\"2b-18f\"", date))
    }

    /** Jellyfin sends only the date */
    @Test fun `the date alone is the validator`() {
        assertEquals(date, DownloadResume.validator(null, date))
    }

    @Test fun `a server with neither can't be resumed`() {
        assertNull(DownloadResume.validator(null, null))
        assertNull(DownloadResume.validator("W/\"weak\"", " "))
    }

    @Test fun `the rest of the same file continues the part`() {
        assertEquals(1_139_450L, DownloadResume.continuedLength("bytes 100-1139449/1139450", "\"v1\"", date, 100, "\"v1\""))
        assertEquals(11L, DownloadResume.continuedLength("bytes 6-10/11", null, date, 6, date))
    }

    @Test fun `bytes from elsewhere than the part's end don't`() {
        assertNull(DownloadResume.continuedLength("bytes 0-10/11", "\"v1\"", null, 6, "\"v1\""))
    }

    /** A server or a proxy that ignored If-Range: the rest of a file that changed */
    @Test fun `another file's bytes don't`() {
        assertNull(DownloadResume.continuedLength("bytes 6-10/11", "\"v2\"", null, 6, "\"v1\""))
        assertNull("no validator on the answer", DownloadResume.continuedLength("bytes 6-10/11", null, null, 6, "\"v1\""))
        assertNull(DownloadResume.continuedLength("bytes 6-10/11", null, "Sat, 06 Jun 2026 01:20:46 GMT", 6, date))
    }

    @Test fun `a range without the whole length, or none, doesn't`() {
        assertNull(DownloadResume.continuedLength("bytes 6-10/*", "\"v1\"", null, 6, "\"v1\""))
        assertNull(DownloadResume.continuedLength(null, "\"v1\"", null, 6, "\"v1\""))
        assertNull(DownloadResume.continuedLength("items 6-10/11", "\"v1\"", null, 6, "\"v1\""))
    }

    @Test fun `the payload keeps its fields around the validator`() {
        val payload = """{"uuid":"b1","title":"Book","relativePath":"Book.m4b","remoteURL":"https://s3/x"}"""

        val withIt = DownloadFilePayload.withValidator(payload, "\"v1\"")
        assertEquals("\"v1\"", DownloadFilePayload.validator(withIt))
        assertEquals("b1", com.google.gson.JsonParser.parseString(withIt).asJsonObject.get("uuid").asString)

        val without = DownloadFilePayload.withValidator(withIt, null)
        assertNull(DownloadFilePayload.validator(without))
        assertEquals("https://s3/x", com.google.gson.JsonParser.parseString(without).asJsonObject.get("remoteURL").asString)
    }

    @Test fun `a payload without one has none`() {
        assertNull(DownloadFilePayload.validator("""{"uuid":"b1"}"""))
        assertNull(DownloadFilePayload.validator("not json"))
    }
}
