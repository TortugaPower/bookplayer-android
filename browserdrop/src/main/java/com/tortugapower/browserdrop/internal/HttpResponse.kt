package com.tortugapower.browserdrop.internal

/** A complete response. Every connection serves one request, so responses always close it. */
internal class HttpResponse(
    val status: Int,
    contentType: String,
    val body: ByteArray,
    cacheControl: String = "no-store"
) {
    val headers = mutableListOf(
        "Content-Type" to contentType,
        "Cache-Control" to cacheControl,
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer",
        "X-Frame-Options" to "DENY"
    )

    fun serialized(): ByteArray {
        val head = buildString {
            append("HTTP/1.1 $status ${reasonPhrase(status)}\r\n")
            for ((name, value) in headers) append("$name: $value\r\n")
            append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        }
        return head.toByteArray(Charsets.UTF_8) + body
    }

    companion object {
        val CONTINUE_INTERIM: ByteArray = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII)

        fun json(status: Int, fields: Map<String, String>): HttpResponse =
            HttpResponse(status, "application/json; charset=utf-8", Json.stringObject(fields).toByteArray(Charsets.UTF_8))

        fun rawJson(status: Int, json: String): HttpResponse =
            HttpResponse(status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))

        /** The error body the protocol documents: `{"error": "<code>", "message": "<text>"}`. */
        fun error(status: Int, code: String, message: String): HttpResponse =
            json(status, mapOf("error" to code, "message" to message))

        fun reasonPhrase(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Content Too Large"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            507 -> "Insufficient Storage"
            else -> "Unknown"
        }
    }
}

internal object ContentType {
    fun forExtension(extension: String): String = when (extension.lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "ico" -> "image/x-icon"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "txt" -> "text/plain; charset=utf-8"
        "webmanifest" -> "application/manifest+json"
        else -> "application/octet-stream"
    }
}
