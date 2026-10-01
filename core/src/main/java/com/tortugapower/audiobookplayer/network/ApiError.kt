package com.tortugapower.audiobookplayer.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tortugapower.audiobookplayer.logic.CodedFailure
import retrofit2.Response

/**
 * The BookPlayer API's error envelope, `{ message, error }`. `error` is a stable code the API sends
 * only for a request that can never succeed as sent (e.g. `item_not_found`, `not_subscribed`); a
 * failure without one may succeed on a retry.
 */
data class ApiError(
    val message: String?,
    val code: String?,
    val httpStatus: Int?,
    /** The body as received, for logs */
    val rawBody: String?,
) {
    /** The failure to park on, or null when the API sent no code */
    fun codedFailure(): CodedFailure? =
        code?.let { CodedFailure(code = it, message = message.orEmpty(), httpStatus = httpStatus) }

    companion object {
        private val gson = Gson()

        /**
         * Reads a non-2xx response's envelope. Retrofit's `body()` is always null on errors: the payload
         * is in `errorBody()`, which can be read only once, so call this before anything else reads it.
         */
        fun parse(response: Response<*>): ApiError {
            val raw = runCatching { response.errorBody()?.string() }.getOrNull()
            return parse(raw, response.code())
        }

        fun parse(rawBody: String?, httpStatus: Int?): ApiError {
            val obj = rawBody?.let { runCatching { gson.fromJson(it, JsonObject::class.java) }.getOrNull() }
            val message = obj?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            val code = obj?.get("error")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            return ApiError(message = message, code = code, httpStatus = httpStatus, rawBody = rawBody)
        }
    }
}
