package com.tortugapower.audiobookplayer.datalayer

import com.tortugapower.audiobookplayer.database.entities.AccountTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec is the single source of truth for the phone→watch reply format, so its round-trip and the
 * two failure sentinels (no account, garbage) are pinned here — the transport can't be unit-tested, but
 * this contract can.
 */
class WatchAuthCodecTest {

    private val payload = WatchAuthPayload("u1", "e@x.com", "jwt-123", AccountTier.PRO, revenuecatId = "rc-uuid")

    @Test fun encodeThenDecode_isSuccessWithSamePayload() {
        val reply = WatchAuthCodec.decodeReply(WatchAuthCodec.encodeReply(payload))
        assertEquals(WatchAuthReply.Success(payload), reply)
    }

    @Test fun encodeNull_isNotSignedInSentinel() {
        assertEquals(WearDataLayer.NOT_SIGNED_IN, WatchAuthCodec.encodeReply(null).toString(Charsets.UTF_8))
    }

    @Test fun decodeSentinel_isNotSignedIn() {
        val bytes = WearDataLayer.NOT_SIGNED_IN.toByteArray(Charsets.UTF_8)
        assertEquals(WatchAuthReply.NotSignedIn, WatchAuthCodec.decodeReply(bytes))
    }

    @Test fun decodeGarbage_isMalformed() {
        assertEquals(WatchAuthReply.Malformed, WatchAuthCodec.decodeReply("{ not json".toByteArray()))
    }

    @Test fun decodeEmpty_isMalformed() {
        assertTrue(WatchAuthCodec.decodeReply(ByteArray(0)) is WatchAuthReply.Malformed)
    }

    @Test fun decodeWellFormedJsonMissingRequiredFields_isMalformed() {
        // Valid JSON, but missing accountId/token — Gson would otherwise produce a payload with nulls in
        // non-null fields and pass it off as Success.
        val json = """{"email":"e@x.com","tier":"PRO"}"""
        assertTrue(WatchAuthCodec.decodeReply(json.toByteArray()) is WatchAuthReply.Malformed)
    }

    @Test fun decodeBlankRequiredField_isMalformed() {
        val json = """{"accountId":"","email":"e@x.com","token":"jwt","tier":"PRO"}"""
        assertTrue(WatchAuthCodec.decodeReply(json.toByteArray()) is WatchAuthReply.Malformed)
    }
}
