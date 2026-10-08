package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * When the synced sort preferences must be pulled past their cooldown because the account changed —
 * iOS re-runs `PreferencesSyncService.bootstrap()` (a forced pull) on every `.accountUpdate`. The host
 * forces a pull on each value this emits.
 */
object PreferencesPullTriggers {
    /**
     * One emission per change of the signed-in account or its tier to one with cloud sync (login, a
     * free → LITE/PRO upgrade, a different account). The value at subscription is skipped: launch is
     * covered by the foreground pull. Token refreshes and other row updates don't change (id, tier), so
     * they don't trigger.
     */
    fun onSyncAccountChange(accounts: Flow<AccountEntity?>): Flow<Unit> =
        accounts
            .map { account -> account?.let { it.id to it.tier } }
            .distinctUntilChanged()
            .drop(1)
            .filter { it != null && TaskAccessPolicy.canAccessSyncService(it.second) }
            .map { }
}
