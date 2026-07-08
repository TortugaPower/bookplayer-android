package com.tortugapower.audiobookplayer.wear.presentation

import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier

/**
 * What the Wear app presents, decided by the signed-in account's subscription tier — mirrors iOS
 * `RootView`. PRO gets the standalone experience (stream/sync/download on-watch); everyone else — including
 * a signed-out watch — gets the phone remote-controller. The companion works WITHOUT watch auth (it just
 * drives the paired phone), so signing in isn't a wall: it lives behind a Settings action in the remote UI,
 * and once a PRO account lands the mode flips to standalone on its own.
 */
enum class WatchMode { REMOTE_CONTROLLER, STANDALONE }

/** Pure tier → mode mapping (unit-tested). PRO ⇒ standalone; no account or any other tier ⇒ remote. */
fun watchModeFor(account: AccountEntity?): WatchMode =
    if (account?.tier == AccountTier.PRO) WatchMode.STANDALONE else WatchMode.REMOTE_CONTROLLER
