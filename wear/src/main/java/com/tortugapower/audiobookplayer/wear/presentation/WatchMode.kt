package com.tortugapower.audiobookplayer.wear.presentation

import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier

/**
 * What the Wear app presents, decided by the signed-in account's subscription tier — mirrors iOS
 * `RootView`'s `hasSyncEnabled` switch. PRO gets the standalone experience (stream/sync/download
 * on-watch); every other signed-in tier gets the phone remote-controller; no account means sign in first.
 */
enum class WatchMode { SIGN_IN, REMOTE_CONTROLLER, STANDALONE }

/** Pure tier → mode mapping (unit-tested). PRO ⇒ standalone; any other signed-in tier ⇒ remote. */
fun watchModeFor(account: AccountEntity?): WatchMode = when {
    account == null -> WatchMode.SIGN_IN
    account.tier == AccountTier.PRO -> WatchMode.STANDALONE
    else -> WatchMode.REMOTE_CONTROLLER
}
