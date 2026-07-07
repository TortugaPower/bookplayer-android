package com.tortugapower.audiobookplayer.datalayer

import com.google.gson.Gson
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The payload crosses the Data Layer as gson bytes, so a lossless round-trip (including the enum tier)
 * is the contract the phone responder and watch requester both rely on.
 */
class WatchAuthPayloadTest {
    private val gson = Gson()

    @Test fun gsonRoundTrip_preservesAllFields() {
        val payload = WatchAuthPayload("u1", "e@x.com", "jwt-123", AccountTier.PRO, revenuecatId = "rc-uuid")
        val restored = gson.fromJson(gson.toJson(payload), WatchAuthPayload::class.java)
        assertEquals(payload, restored)
    }

    @Test fun gsonRoundTrip_preservesTier() {
        val payload = WatchAuthPayload("u2", "l@x.com", "jwt-456", AccountTier.LITE)
        val restored = gson.fromJson(gson.toJson(payload), WatchAuthPayload::class.java)
        assertEquals(AccountTier.LITE, restored.tier)
    }

    @Test fun gsonRoundTrip_preservesNullRevenuecatId() {
        val payload = WatchAuthPayload("u3", "n@x.com", "jwt-789", AccountTier.FREE, revenuecatId = null)
        val restored = gson.fromJson(gson.toJson(payload), WatchAuthPayload::class.java)
        assertEquals(null, restored.revenuecatId)
    }
}
