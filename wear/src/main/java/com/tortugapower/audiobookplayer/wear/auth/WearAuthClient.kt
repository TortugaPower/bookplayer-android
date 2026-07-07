package com.tortugapower.audiobookplayer.wear.auth

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.tortugapower.audiobookplayer.datalayer.WatchAuthCodec
import com.tortugapower.audiobookplayer.datalayer.WatchAuthPayload
import com.tortugapower.audiobookplayer.datalayer.WatchAuthReply
import com.tortugapower.audiobookplayer.datalayer.WearDataLayer
import com.tortugapower.audiobookplayer.wear.data.await
import com.tortugapower.audiobookplayer.wear.data.findPhoneNodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The one operation the ViewModel depends on, extracted so it can be injected and faked in tests
 * (the real [WearAuthClient] needs the Wearable API and a device).
 */
interface WatchAuthenticator {
    suspend fun requestAuth(): WearAuthOutcome
}

/**
 * Watch side of the sign-in handoff (mirrors iOS's `requestAuthFromiPhone`). Resolves the paired phone
 * node by [WearDataLayer.CAPABILITY_PHONE], sends [WearDataLayer.PATH_AUTH], and awaits the phone's reply
 * on [WearDataLayer.PATH_AUTH_RESPONSE], decoding it via the shared [WatchAuthCodec].
 *
 * The reply arrives as a separate message, so the listener is registered *before* the request is sent to
 * avoid a race, and removed once we're done. The whole exchange is bounded by [REPLY_TIMEOUT_MS].
 */
class WearAuthClient(
    private val messageClient: MessageClient,
    private val capabilityClient: CapabilityClient,
) : WatchAuthenticator {
    constructor(context: Context) : this(
        Wearable.getMessageClient(context),
        Wearable.getCapabilityClient(context),
    )

    override suspend fun requestAuth(): WearAuthOutcome {
        val phoneNodeId = capabilityClient.findPhoneNodeId() ?: return WearAuthOutcome.PhoneNotReachable

        val replyBytes = CompletableDeferred<ByteArray>()
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == WearDataLayer.PATH_AUTH_RESPONSE) {
                replyBytes.complete(event.data)
            }
        }
        return try {
            // Await registration before sending, so the listener is actually live in GMS when the phone's
            // reply comes back — otherwise a fast reply can be dropped and we'd fall through to the timeout.
            // Inside the try so a registration failure surfaces as Failed (and removeListener still runs)
            // rather than throwing out of requestAuth() and stranding the caller.
            messageClient.addListener(listener).await()
            messageClient.sendMessage(phoneNodeId, WearDataLayer.PATH_AUTH, ByteArray(0)).await()
            val bytes = withTimeout(REPLY_TIMEOUT_MS) { replyBytes.await() }
            when (val reply = WatchAuthCodec.decodeReply(bytes)) {
                is WatchAuthReply.Success -> WearAuthOutcome.Success(reply.payload)
                WatchAuthReply.NotSignedIn -> WearAuthOutcome.NotSignedInOnPhone
                WatchAuthReply.Malformed -> WearAuthOutcome.Failed("invalid response")
            }
        } catch (e: TimeoutCancellationException) {
            WearAuthOutcome.Failed("timed out")
        } catch (e: Exception) {
            WearAuthOutcome.Failed(e.message ?: "transfer failed")
        } finally {
            messageClient.removeListener(listener)
        }
    }

    private companion object {
        const val REPLY_TIMEOUT_MS = 15_000L
    }
}

/** Outcome of a handoff attempt — maps to the sign-in UI state (mirrors iOS's WatchConnectivityError cases). */
sealed interface WearAuthOutcome {
    data class Success(val payload: WatchAuthPayload) : WearAuthOutcome
    data object NotSignedInOnPhone : WearAuthOutcome
    data object PhoneNotReachable : WearAuthOutcome
    data class Failed(val reason: String) : WearAuthOutcome
}
