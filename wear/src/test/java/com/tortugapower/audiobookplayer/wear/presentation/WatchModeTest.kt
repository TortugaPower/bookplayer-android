package com.tortugapower.audiobookplayer.wear.presentation

import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tier → mode gate is the phase-1 product decision (PRO = standalone, everything else = remote,
 * no account = sign in). Locked down here so a future tier tweak can't silently regress it.
 */
class WatchModeTest {
    private fun account(tier: AccountTier) =
        AccountEntity(id = "u1", email = "e@x.com", apiToken = "t", tier = tier)

    @Test fun nullAccount_isSignIn() =
        assertEquals(WatchMode.SIGN_IN, watchModeFor(null))

    @Test fun pro_isStandalone() =
        assertEquals(WatchMode.STANDALONE, watchModeFor(account(AccountTier.PRO)))

    @Test fun lite_isRemoteController() =
        assertEquals(WatchMode.REMOTE_CONTROLLER, watchModeFor(account(AccountTier.LITE)))

    @Test fun plus_isRemoteController() =
        assertEquals(WatchMode.REMOTE_CONTROLLER, watchModeFor(account(AccountTier.PLUS)))

    @Test fun free_isRemoteController() =
        assertEquals(WatchMode.REMOTE_CONTROLLER, watchModeFor(account(AccountTier.FREE)))
}
