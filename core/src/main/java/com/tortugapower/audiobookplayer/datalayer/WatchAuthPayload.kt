package com.tortugapower.audiobookplayer.datalayer

import com.tortugapower.audiobookplayer.database.entities.AccountTier

/**
 * The account snapshot the phone hands to the watch over the Wear Data Layer, so the watch can
 * authenticate without its own sign-in flow (the Android analog of iOS's `requestAuth`). Serialized
 * as gson bytes on the [WearDataLayer.PATH_AUTH] message.
 *
 * The transport wiring (phone responder + watch requester) lands in the next slice; this is the shared
 * contract both `:app` and `:wear` compile against so the two sides can never disagree on the shape.
 */
data class WatchAuthPayload(
    val accountId: String,
    val email: String,
    val token: String,
    val tier: AccountTier,
    // The RevenueCat app-user id (distinct from accountId). The watch must log into RevenueCat with this
    // — same as the phone (`revenuecatId ?: id`) — or it resolves a different RC user with no entitlement
    // and downgrades the tier. Nullable: falls back to accountId when the account has none.
    val revenuecatId: String? = null,
)
