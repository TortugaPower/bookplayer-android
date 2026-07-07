package com.tortugapower.audiobookplayer.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.tortugapower.audiobookplayer.datalayer.WatchCommand
import com.tortugapower.audiobookplayer.datalayer.WatchRemoteCodec
import com.tortugapower.audiobookplayer.datalayer.WearDataLayer

/** Sends remote-control commands to the phone — an interface so the ViewModel is testable with a fake. */
interface RemoteCommandSender {
    suspend fun send(command: WatchCommand)
}

/**
 * Resolves the paired phone node and sends a [WatchCommand] over the Data Layer ([WearDataLayer.PATH_COMMAND]).
 * Best-effort/fire-and-forget: if the phone isn't reachable the command is silently dropped (matches iOS's
 * companion commands, which no-op when the session isn't reachable).
 */
class WearRemoteClient(
    private val messageClient: MessageClient,
    private val capabilityClient: CapabilityClient,
) : RemoteCommandSender {
    constructor(context: Context) : this(
        Wearable.getMessageClient(context),
        Wearable.getCapabilityClient(context),
    )

    override suspend fun send(command: WatchCommand) {
        val phoneNodeId = capabilityClient.findPhoneNodeId() ?: return
        try {
            messageClient.sendMessage(phoneNodeId, WearDataLayer.PATH_COMMAND, WatchRemoteCodec.encodeCommand(command))
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send command ${command.type}", e)
        }
    }

    private companion object {
        const val TAG = "WearRemoteClient"
    }
}
