package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.logic.ExternalServerUpsert.Incoming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins which saved row a fresh sign-in replaces — the store half of iOS's re-auth contract
 * (IntegrationConnectionStoreTests: same-account replace, moved-server replacingID, different account
 * forks, canonical URL variants collapse).
 */
class ExternalServerUpsertTest {

    private fun row(
        id: Long,
        url: String,
        username: String? = "gianni",
        userId: String? = "u1",
        type: ExternalServiceType = ExternalServiceType.AUDIOBOOKSHELF,
    ) = ExternalServerEntity(id = id, name = "srv", type = type, url = url, username = username, userId = userId)

    private fun incoming(
        url: String,
        username: String? = "gianni",
        userId: String? = "u1",
        replacingId: Long? = null,
        type: ExternalServiceType = ExternalServiceType.AUDIOBOOKSHELF,
    ) = Incoming(type = type, url = url, username = username, userId = userId, replacingId = replacingId)

    @Test fun `same account on the same server replaces its row`() {
        val existing = listOf(row(1, "https://abs.example.com"))
        assertEquals(1L, ExternalServerUpsert.rowToReplace(existing, incoming("https://abs.example.com"))?.id)
    }

    /** Trailing slash and default port are the same server; two rows here would mean duplicate connections for one account. */
    @Test fun `canonically equal URLs are the same server`() {
        val existing = listOf(row(1, "https://abs.example.com"))
        assertEquals(1L, ExternalServerUpsert.rowToReplace(existing, incoming("https://abs.example.com:443/"))?.id)
        assertEquals(1L, ExternalServerUpsert.rowToReplace(existing, incoming("HTTPS://ABS.example.com"))?.id)
    }

    @Test fun `a different account on the same server is a new connection`() {
        val existing = listOf(row(1, "https://abs.example.com"))
        assertNull(ExternalServerUpsert.rowToReplace(existing, incoming("https://abs.example.com", username = "other", userId = "u2")))
    }

    /** User ids decide when both sides have one: a renamed account is still the same account. */
    @Test fun `user id wins over username when both sides carry one`() {
        val existing = listOf(row(1, "https://abs.example.com", username = "old-name", userId = "u1"))
        assertEquals(1L, ExternalServerUpsert.rowToReplace(existing, incoming("https://abs.example.com", username = "new-name", userId = "u1"))?.id)
        assertNull(ExternalServerUpsert.rowToReplace(existing, incoming("https://abs.example.com", username = "old-name", userId = "u9")))
    }

    /** Rows saved before the column existed (or servers that never report an id) fall back to the username. */
    @Test fun `legacy rows without a user id match by username`() {
        val existing = listOf(row(1, "https://abs.example.com", userId = null))
        assertEquals(1L, ExternalServerUpsert.rowToReplace(existing, incoming("https://abs.example.com", userId = "u1"))?.id)
        assertNull(ExternalServerUpsert.rowToReplace(existing, incoming("https://abs.example.com", username = "someone-else", userId = "u1")))
    }

    /** The moved-server case: the account match finds nothing at the new URL; the re-auth origin row must be replaced, keeping its id. */
    @Test fun `re-auth at an edited URL replaces the origin row when the account matches`() {
        val existing = listOf(row(1, "https://old.example.com"))
        val replaced = ExternalServerUpsert.rowToReplace(existing, incoming("https://moved.example.com", replacingId = 1))
        assertEquals(1L, replaced?.id)
    }

    /** Signing into a different account is genuinely a new connection, not a move — the old row stays. */
    @Test fun `re-auth origin row is not replaced by a different account`() {
        val existing = listOf(row(1, "https://old.example.com"))
        assertNull(ExternalServerUpsert.rowToReplace(existing, incoming("https://moved.example.com", username = "someone-else", userId = "u2", replacingId = 1)))
    }

    @Test fun `re-auth origin row of another integration is ignored`() {
        val existing = listOf(row(1, "https://old.example.com", type = ExternalServiceType.JELLYFIN))
        assertNull(ExternalServerUpsert.rowToReplace(existing, incoming("https://moved.example.com", replacingId = 1)))
    }

    /** An account already saved at the new URL wins over the origin row (iOS: `isSameAccount ?? replacingID`). */
    @Test fun `an existing row at the new URL wins over the origin row`() {
        val existing = listOf(row(1, "https://old.example.com"), row(2, "https://moved.example.com"))
        assertEquals(2L, ExternalServerUpsert.rowToReplace(existing, incoming("https://moved.example.com", replacingId = 1))?.id)
    }

    @Test fun `no match and no origin means a brand-new row`() {
        assertNull(ExternalServerUpsert.rowToReplace(emptyList(), incoming("https://abs.example.com")))
        assertNull(ExternalServerUpsert.rowToReplace(listOf(row(1, "https://other.example.com")), incoming("https://abs.example.com")))
    }
}
