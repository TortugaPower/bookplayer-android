package com.tortugapower.audiobookplayer.wear.data

import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
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
