package com.tortugapower.audiobookplayer.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the JSON key for a synced item's last-played timestamp. The API (and iOS) send
 * `lastPlayDateTimestamp`; reading the wrong key (`lastPlayDate`, the DB/upload key) left every synced
 * item's date null, so server-synced plays never appeared in the Wear recents / Auto Recent tab. This
 * pins the contract so it can't silently regress.
 */
class ContentsResponseDecodeTest {

    private val gson = Gson()

    private fun itemJson(lastPlayKey: String) = """
        {"content":[{
          "uuid":"u1","relativePath":"a.m4b","title":"Book A","details":"Author",
          "originalFileName":"a.m4b","duration":100.0,"currentTime":0.0,
          "percentCompleted":0.0,"isFinished":false,"orderRank":0,"type":1,
          "$lastPlayKey":1700000000.0
        }],"lastItemPlayed":null}
    """.trimIndent()

    @Test fun decodesLastPlayDateTimestampFromApiKey() {
        val response = gson.fromJson(itemJson("lastPlayDateTimestamp"), ContentsResponse::class.java)
        assertEquals(1700000000.0, response.content.first().lastPlayDateTimestamp!!, 0.001)
    }

    @Test fun ignoresLegacyLastPlayDateKey() {
        // The old (wrong) key must NOT populate the field — proves we read the API's actual key.
        val response = gson.fromJson(itemJson("lastPlayDate"), ContentsResponse::class.java)
        assertNull(response.content.first().lastPlayDateTimestamp)
    }
}
