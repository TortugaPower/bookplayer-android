package com.tortugapower.audiobookplayer.datalayer

import com.google.gson.Gson

/**
 * The wire codec for the phone→watch auth reply, shared by `:app` (encodes) and `:wear` (decodes) so the
 * two sides can never disagree on the format. The reply is either the gson [WatchAuthPayload] or the
 * [WearDataLayer.NOT_SIGNED_IN] sentinel; anything else decodes to [WatchAuthReply.Malformed].
 *
 * Kept pure (no Android/Wearable types) so the full round-trip is unit-tested without a device — the
 * transport (node discovery, send/receive) is the only part that needs one.
 */
object WatchAuthCodec {
    private val gson = Gson()

    /** Phone side: encode the account to hand off, or the sentinel when there's nothing to hand off. */
    fun encodeReply(payload: WatchAuthPayload?): ByteArray =
        (payload?.let { gson.toJson(it) } ?: WearDataLayer.NOT_SIGNED_IN).toByteArray(Charsets.UTF_8)

    /** Watch side: decode the phone's reply bytes. */
    fun decodeReply(bytes: ByteArray): WatchAuthReply {
        val text = bytes.toString(Charsets.UTF_8)
        if (text == WearDataLayer.NOT_SIGNED_IN) return WatchAuthReply.NotSignedIn
        return try {
            // Gson bypasses Kotlin null-safety, so a JSON object missing required fields yields a payload
            // with nulls in non-null properties. Validate the identity/auth-critical fields so such a reply
            // is Malformed (as the KDoc promises) instead of a Success that NPEs or persists nulls. (A null
            // field makes the isNotBlank() call itself throw, which the catch below maps to Malformed too.)
            gson.fromJson(text, WatchAuthPayload::class.java)
                ?.takeIf { it.accountId.isNotBlank() && it.email.isNotBlank() && it.token.isNotBlank() }
                ?.let { WatchAuthReply.Success(it) }
                ?: WatchAuthReply.Malformed
        } catch (e: Exception) {
            WatchAuthReply.Malformed
        }
    }
}

/** Result of decoding the phone's auth reply. */
sealed interface WatchAuthReply {
    data class Success(val payload: WatchAuthPayload) : WatchAuthReply
    data object NotSignedIn : WatchAuthReply
    data object Malformed : WatchAuthReply
}
