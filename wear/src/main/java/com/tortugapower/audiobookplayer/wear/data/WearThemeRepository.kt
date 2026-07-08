package com.tortugapower.audiobookplayer.wear.data

import android.content.Context
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.tortugapower.audiobookplayer.datalayer.WatchRemoteCodec
import com.tortugapower.audiobookplayer.datalayer.WatchTheme
import com.tortugapower.audiobookplayer.datalayer.WearDataLayer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/**
 * Observes the phone's published [WatchTheme] ([WearDataLayer.PATH_THEME]) so the watch UI can adopt the
 * user's selected app theme. Seeds with the latest already-published value (the Data Layer retains it, so a
 * freshly-launched or offline watch keeps the last theme) and then emits on change; null = no theme synced
 * yet (the caller falls back to the default palette).
 */
interface WearThemeRepository {
    val theme: Flow<WatchTheme?>
}

class DataLayerWearThemeRepository(
    private val dataClient: DataClient,
) : WearThemeRepository {
    constructor(context: Context) : this(Wearable.getDataClient(context))

    override val theme: Flow<WatchTheme?> = callbackFlow {
        val path = WearDataLayer.PATH_THEME
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
    }.map { it?.let(WatchRemoteCodec::decodeTheme) }
}
