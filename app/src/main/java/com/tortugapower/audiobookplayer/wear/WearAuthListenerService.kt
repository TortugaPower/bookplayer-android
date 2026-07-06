package com.tortugapower.audiobookplayer.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.datalayer.WatchAuthCodec
import com.tortugapower.audiobookplayer.datalayer.WatchAuthPayload
import com.tortugapower.audiobookplayer.datalayer.WearDataLayer
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import kotlinx.coroutines.runBlocking

/**
 * Phone side of the Wear sign-in handoff (the Android analog of iOS's `PhoneWatchConnectivityService`
 * `handleAuthRequest`). When the watch sends [WearDataLayer.PATH_AUTH], we reply on
 * [WearDataLayer.PATH_AUTH_RESPONSE] with the signed-in account (token decrypted out of Room by
 * [RoomAccountRepository]) encoded via [WatchAuthCodec], or the not-signed-in sentinel.
 *
 * Callbacks run on a background binder thread, so the short `runBlocking` DB read is off the main thread.
 * We build the repository from the singleton [AppDatabase] rather than depend on `BookPlayerApplication`,
 * so the service works even if the system starts it before the app's own init.
 */
class WearAuthListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearDataLayer.PATH_AUTH) return

        val reply = runBlocking {
            val accountRepository = RoomAccountRepository(AppDatabase.getDatabase(applicationContext).accountDao())
            val account = accountRepository.getAccount()
            // A blank id means no real signed-in account — treat as not signed in (mirrors iOS).
            val payload = account?.takeIf { it.id.isNotBlank() }?.let {
                WatchAuthPayload(
                    accountId = it.id,
                    email = it.email,
                    token = it.apiToken,
                    tier = it.tier,
                    revenuecatId = it.revenuecatId,
                )
            }
            WatchAuthCodec.encodeReply(payload)
        }

        // Best-effort reply to the requesting node; if it fails the watch's request times out and retries.
        Wearable.getMessageClient(this)
            .sendMessage(event.sourceNodeId, WearDataLayer.PATH_AUTH_RESPONSE, reply)
            .addOnFailureListener { Log.e(TAG, "Failed to send auth reply to watch", it) }
    }

    private companion object {
        const val TAG = "WearAuthListener"
    }
}
