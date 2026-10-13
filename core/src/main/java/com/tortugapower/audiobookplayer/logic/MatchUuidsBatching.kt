package com.tortugapower.audiobookplayer.logic

import com.google.gson.Gson

/**
 * Splits relativePath → uuid pairs into `POST /v1/library/uuids` requests the API accepts: at most
 * [MAX_ITEMS] keys (its record limit) and a body under its 100 KB JSON limit. Long paths reach the byte
 * limit long before the count, and an over-limit body is an uncoded 413 that a queued match retries
 * forever, holding up the sync lane. Bytes are measured as Gson writes them (escaped characters, UTF-8
 * paths). Order is kept. A pair that alone exceeds the budget goes out by itself.
 */
object MatchUuidsBatching {
    const val MAX_ITEMS = 1_000

    /** Headroom under the API's 100 KB (102,400 bytes) */
    const val MAX_BODY_BYTES = 96 * 1024

    private val gson = Gson()

    /** `{"items":{}}` */
    private const val ENVELOPE_BYTES = 12

    fun batches(
        items: Map<String, String>,
        maxItems: Int = MAX_ITEMS,
        maxBytes: Int = MAX_BODY_BYTES,
    ): List<Map<String, String>> {
        val batches = mutableListOf<Map<String, String>>()
        var batch = LinkedHashMap<String, String>()
        var bytes = ENVELOPE_BYTES
        for ((path, uuid) in items) {
            // `"path":"uuid"`, after a comma unless it's the batch's first
            val entry = jsonBytes(path) + 1 + jsonBytes(uuid)
            if (batch.isNotEmpty() && (batch.size >= maxItems || bytes + 1 + entry > maxBytes)) {
                batches += batch
                batch = LinkedHashMap()
                bytes = ENVELOPE_BYTES
            }
            bytes += if (batch.isEmpty()) entry else 1 + entry
            batch[path] = uuid
        }
        if (batch.isNotEmpty()) batches += batch
        return batches
    }

    private fun jsonBytes(value: String) = gson.toJson(value).toByteArray(Charsets.UTF_8).size
}
