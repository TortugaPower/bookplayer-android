package com.tortugapower.audiobookplayer.wear.data

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.guava.await as awaitFuture

/**
 * The companion phone app as the watch sees it. The watch app is non-standalone (it signs in and plays remotely
 * only through the phone app), so when a connected phone lacks the app the watch offers to install it there, as
 * Google's guide for non-standalone apps asks. Extracted so the ViewModels can be tested with a fake.
 */
interface PhoneApp {
    suspend fun status(): PhoneAppStatus

    /** Opens BookPlayer's Play Store listing on the connected phone; false when no phone took it. */
    suspend fun openStoreListing(): Boolean
}

class WearPhoneApp(private val context: Context) : PhoneApp {
    private val capabilityClient = Wearable.getCapabilityClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    // On the shared IO pool: the helper's default executor is a thread of its own that's never shut down.
    private val remoteActivityHelper = RemoteActivityHelper(context, Dispatchers.IO.asExecutor())

    override suspend fun status(): PhoneAppStatus = try {
        phoneAppStatus(capabilityClient, nodeClient)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The Wearable API is unavailable: nothing on the phone side can answer either.
        PhoneAppStatus.NO_PHONE
    }

    override suspend fun openStoreListing(): Boolean = try {
        val nodes = nodeClient.connectedNodes.await()
        val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        if (phone == null) {
            false
        } else {
            val listing = Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData("market://details?id=${context.packageName}".toUri())
            // The phone may never answer: a bounded wait turns that into a failure the user can retry.
            withTimeoutOrNull(STORE_LISTING_TIMEOUT_MS) {
                remoteActivityHelper.startRemoteActivity(listing, phone.id).awaitFuture()
                true
            } ?: false
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val STORE_LISTING_TIMEOUT_MS = 15_000L
    }
}
