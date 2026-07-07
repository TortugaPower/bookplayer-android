package com.tortugapower.audiobookplayer.wear.data

import android.content.Context
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.tortugapower.audiobookplayer.datalayer.WatchLibraryState
import com.tortugapower.audiobookplayer.datalayer.WatchPlaybackState
import com.tortugapower.audiobookplayer.datalayer.WatchRemoteCodec
import com.tortugapower.audiobookplayer.datalayer.WearDataLayer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/**
 * Observes the phone's remote-control DataItems and decodes them for the watch UI. Each flow seeds with the
 * latest already-published value (so a freshly-launched watch shows current state) and then emits on change.
 */
interface RemoteContextRepository {
    val libraryState: Flow<WatchLibraryState?>
    val playbackState: Flow<WatchPlaybackState?>
}

class DataLayerRemoteContextRepository(
    private val dataClient: DataClient,
) : RemoteContextRepository {
    constructor(context: Context) : this(Wearable.getDataClient(context))

    override val libraryState: Flow<WatchLibraryState?> =
        payloadFlow(WearDataLayer.PATH_LIBRARY_STATE).map { it?.let(WatchRemoteCodec::decodeLibraryState) }

    override val playbackState: Flow<WatchPlaybackState?> =
        payloadFlow(WearDataLayer.PATH_PLAYBACK_STATE).map { it?.let(WatchRemoteCodec::decodePlaybackState) }

    /** Raw payload bytes for [path]: the current value on start, then each change (null on delete). */
    private fun payloadFlow(path: String): Flow<ByteArray?> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            events.forEach { event ->
                if (event.dataItem.uri.path != path) return@forEach
                when (event.type) {
                    DataEvent.TYPE_CHANGED ->
                        trySend(DataMapItem.fromDataItem(event.dataItem).dataMap.getByteArray(WearDataLayer.KEY_PAYLOAD))
                    DataEvent.TYPE_DELETED -> trySend(null)
                }
            }
        }
        dataClient.addListener(listener)

        // Seed with the latest already-published value (best-effort; live changes arrive via the listener).
        try {
            val items = dataClient.getDataItems().await()
            try {
                items.firstOrNull { it.uri.path == path }?.let {
                    trySend(DataMapItem.fromDataItem(it).dataMap.getByteArray(WearDataLayer.KEY_PAYLOAD))
                }
            } finally {
                items.release()
            }
        } catch (e: Exception) {
            // Ignore — the listener still delivers subsequent updates.
        }

        awaitClose { dataClient.removeListener(listener) }
    }
}
