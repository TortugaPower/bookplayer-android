package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.AccountTier

/** What a RevenueCat reading of the account means for the sync queue, against the reading before it */
enum class TierChange {
    /**
     * The first reading since launch or sign-in. Sync found off here went off while the app was closed: its
     * queue is held for the account's return, not wiped (iOS `SyncService.setup`).
     */
    FirstReading,

    /** PRO or LITE gone mid-session: the server lanes are wiped (iOS `updateSyncEnabled(false)`) */
    Lapse,

    /** PRO or LITE back */
    Return,

    /** Sync stays on without file uploads: they're dropped (iOS cancels them and drops the queued ones) */
    ProToLite,
    LiteToPro,

    /** The same tier, or between two that don't sync (FREE, PLUS) */
    Unchanged,
}

object TierTransitions {
    /** PLUS is a tip: alone it doesn't sync, so it reads as lapsed */
    fun tierOf(hasPro: Boolean, hasLite: Boolean, hasPlus: Boolean): AccountTier = when {
        hasPro -> AccountTier.PRO
        hasLite -> AccountTier.LITE
        hasPlus -> AccountTier.PLUS
        else -> AccountTier.FREE
    }

    /** [previous] is null before the first reading */
    fun classify(previous: AccountTier?, current: AccountTier): TierChange {
        if (previous == null) return TierChange.FirstReading
        val synced = TaskAccessPolicy.canAccessSyncService(previous)
        val syncs = TaskAccessPolicy.canAccessSyncService(current)
        return when {
            synced && !syncs -> TierChange.Lapse
            !synced && syncs -> TierChange.Return
            previous == AccountTier.PRO && current == AccountTier.LITE -> TierChange.ProToLite
            previous == AccountTier.LITE && current == AccountTier.PRO -> TierChange.LiteToPro
            else -> TierChange.Unchanged
        }
    }

    /** Whether [current] lets the engine run work [previous] held: sync back, or file uploads */
    fun releasesWork(previous: AccountTier?, current: AccountTier): Boolean =
        TaskAccessPolicy.canAccessSyncService(current) &&
            (!TaskAccessPolicy.canAccessSyncService(previous) || (current == AccountTier.PRO && previous != AccountTier.PRO))
}
