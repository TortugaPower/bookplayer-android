package com.tortugapower.browserdrop.internal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The request line and headers of one HTTP/1.x request.
 *
 * Only what the server needs is supported: origin-form targets, `Content-Length` bodies and
 * `Expect: 100-continue`. Anything ambiguous (folded headers, conflicting lengths) is refused.
 */
internal data class HttpRequestHead(
    val method: String,
    /** Percent-decoded path, for example `/upload`. */
    val path: String,
    /** Percent-decoded query items; the first value wins. `+` stays `+`, as in `encodeURIComponent`. */
    val query: Map<String, String>,
    /** Header values keyed by lowercased name. Repeated headers are joined with ", ". */
    val headers: Map<String, String>,
    val contentLength: Long?
) {
    val expectsContinue: Boolean get() = headers["expect"]?.lowercase() == "100-continue"

    companion object {
        /** Requests whose head is larger than this are refused with 431. */
        const val MAXIMUM_SIZE = 16 * 1024

        /** Parses the bytes before the blank line that ends the head. */
        fun parse(bytes: ByteArray): HttpRequestHead? {
            val text = decodeUtf8(bytes) ?: return null
            val lines = text.split("\r\n")

            val requestLine = lines[0].split(" ")
            if (requestLine.size != 3) return null
            val (method, target, version) = requestLine
            if (method.isEmpty() || !method.all { it in 'A'..'Z' }) return null
            if (version != "HTTP/1.1" && version != "HTTP/1.0") return null
            if (!target.startsWith("/")) return null

            val rawPath = target.substringBefore('?')
            val path = percentDecode(rawPath) ?: return null
            val query = mutableMapOf<String, String>()
            if ('?' in target) {
                val rawQuery = target.substringAfter('?')
                if (rawQuery.isNotEmpty()) {
                    for (item in rawQuery.split("&")) {
                        val name = percentDecode(item.substringBefore('=')) ?: return null
                        val value = percentDecode(item.substringAfter('=', "")) ?: return null
                        query.putIfAbsent(name, value)
                    }
                }
            }

            val headers = mutableMapOf<String, String>()
            var contentLength: Long? = null
            for (line in lines.drop(1)) {
                // Obsolete line folding is a classic smuggling vector; nothing legitimate sends it.
                val colon = line.indexOf(':')
                if (colon < 0 || line.startsWith(" ") || line.startsWith("\t")) return null
                val name = line.substring(0, colon).lowercase()
                val value = line.substring(colon + 1).trim(' ', '\t')
                if (name.isEmpty() || ' ' in name || '\t' in name) return null

                if (name == "content-length") {
                    if (value.isEmpty() || !value.all { it in '0'..'9' }) return null
                    val length = value.toLongOrNull() ?: return null
                    if (contentLength != null && contentLength != length) return null
                    contentLength = length
                }
                headers[name] = headers[name]?.let { "$it, $value" } ?: value
            }

            return HttpRequestHead(method, path, query, headers, contentLength)
        }

        private fun decodeUtf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }

        /** `%XX` escapes decoded as UTF-8; null on a malformed escape or invalid UTF-8. */
        private fun percentDecode(text: String): String? {
            if ('%' !in text) return text
            val out = ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 2 >= text.length) return null
                    val byte = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    out.write(byte)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return decodeUtf8(out.toByteArray())
        }
    }
}
