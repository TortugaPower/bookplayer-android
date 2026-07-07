package com.tortugapower.audiobookplayer.datalayer

/**
 * Constants for the phone↔watch Wear Data Layer contract, shared by `:app` (the responder) and `:wear`
 * (the requester) so the capability name and message path never drift between the two sides.
 */
object WearDataLayer {
    /** Capability the phone app advertises; the watch resolves the phone node by it via CapabilityClient. */
    const val CAPABILITY_PHONE = "bookplayer_phone"

    /** MessageClient path: watch → phone auth request (empty body). */
    const val PATH_AUTH = "/bookplayer/auth"

    /** MessageClient path: phone → watch reply, carrying the [WatchAuthCodec]-encoded account (or sentinel). */
    const val PATH_AUTH_RESPONSE = "/bookplayer/auth/response"

    /** Reply sentinel the phone sends when it has no signed-in account to hand off. */
    const val NOT_SIGNED_IN = "not_signed_in"
}
