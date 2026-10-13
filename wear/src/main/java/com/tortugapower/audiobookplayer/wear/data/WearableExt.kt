package com.tortugapower.audiobookplayer.wear.data

import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.NodeClient
import com.tortugapower.audiobookplayer.datalayer.WearDataLayer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Await a Play Services [Task] as a suspend function (no kotlinx-coroutines-play-services dependency). */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/** Id of the nearest reachable node advertising the phone capability, or null if none is connected. */
internal suspend fun CapabilityClient.findPhoneNodeId(): String? {
    val nodes = getCapability(WearDataLayer.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
    return (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
}

/** Whether the watch can reach BookPlayer on the phone. */
enum class PhoneAppStatus { READY, APP_MISSING, NO_PHONE }

internal suspend fun phoneAppStatus(capabilityClient: CapabilityClient, nodeClient: NodeClient): PhoneAppStatus {
    if (capabilityClient.findPhoneNodeId() != null) return PhoneAppStatus.READY
    return phoneAppStatus(appReachable = false, nodeConnected = nodeClient.connectedNodes.await().isNotEmpty())
}

/**
 * Tells "the phone has no BookPlayer" apart from "no phone is connected": only the phone app advertises
 * [WearDataLayer.CAPABILITY_PHONE], so a connected node without it is a phone that lacks the app.
 */
internal fun phoneAppStatus(appReachable: Boolean, nodeConnected: Boolean): PhoneAppStatus = when {
    appReachable -> PhoneAppStatus.READY
    nodeConnected -> PhoneAppStatus.APP_MISSING
    else -> PhoneAppStatus.NO_PHONE
}
