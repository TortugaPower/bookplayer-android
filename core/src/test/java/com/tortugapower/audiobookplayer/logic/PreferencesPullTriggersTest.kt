package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which account changes force a preferences pull (iOS re-bootstraps its PreferencesSyncService on
 * every account update): a change of account or tier to one with cloud sync, never the value at launch
 * (the foreground pull covers that), a downgrade, a logout, or a token refresh.
 */
class PreferencesPullTriggersTest {

    private fun account(id: String, tier: AccountTier, token: String = "token") =
        AccountEntity(id = id, email = "$id@example.invalid", apiToken = token, tier = tier)

    private fun triggers(vararg accounts: AccountEntity?): Int = runBlocking {
        PreferencesPullTriggers.onSyncAccountChange(flowOf(*accounts)).toList().size
    }

    @Test fun `the account at launch never triggers`() {
        assertEquals(0, triggers(account("a", AccountTier.PRO)))
    }

    @Test fun `signing in to a sync tier triggers once`() {
        assertEquals(1, triggers(null, account("a", AccountTier.LITE)))
    }

    @Test fun `a free sign-in doesn't, the upgrade to a sync tier does`() {
        assertEquals(1, triggers(null, account("a", AccountTier.FREE), account("a", AccountTier.PRO)))
    }

    @Test fun `token refreshes and other row updates don't trigger again`() {
        assertEquals(1, triggers(null, account("a", AccountTier.PRO, "t1"), account("a", AccountTier.PRO, "t2")))
    }

    @Test fun `logout, PLUS and downgrades don't trigger, a different sync account does`() {
        assertEquals(0, triggers(account("a", AccountTier.PRO), account("a", AccountTier.FREE)))
        assertEquals(1, triggers(account("a", AccountTier.PRO), null, account("b", AccountTier.PLUS), account("c", AccountTier.LITE)))
    }
}
