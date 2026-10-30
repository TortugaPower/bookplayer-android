package com.tortugapower.browserdrop.internal

import java.text.Normalizer

/** Rules for the relative paths a browser sends (`Book/Disc 1/01.mp3`) and for the page files it asks for. */
internal object PathRules {
    const val MAXIMUM_PATH_BYTES = 1024
    const val MAXIMUM_SEGMENT_BYTES = 255
    const val MAXIMUM_DEPTH = 32

    enum class Rejection { INVALID_PATH, UNSUPPORTED_TYPE }

    sealed interface Result {
        data class Accepted(val path: String) : Result
        data class Refused(val rejection: Rejection) : Result
    }

    /**
     * A cleaned upload path, or why it was refused. [allowedExtensions] holds lowercased extensions without
     * the dot; null allows any file type.
     */
    fun uploadPath(raw: String, allowedExtensions: Set<String>?): Result {
        val segments = segments(raw) ?: return Result.Refused(Rejection.INVALID_PATH)
        if (allowedExtensions != null) {
            val extension = segments.last().substringAfterLast('.', "").lowercase()
            if (extension.isEmpty() || extension !in allowedExtensions) {
                return Result.Refused(Rejection.UNSUPPORTED_TYPE)
            }
        }
        return Result.Accepted(segments.joinToString("/"))
    }

    /**
     * The segments of a relative path, or null if any of them could step outside the folder it's resolved in.
     *
     * Backslashes count as separators (Windows), names are precomposed (NFC) so the same name always maps to
     * the same key, and hidden segments (`.git`, `.DS_Store`, `..`) are refused.
     */
    fun segments(raw: String): List<String>? {
        val path = Normalizer.normalize(raw, Normalizer.Form.NFC).replace('\\', '/')
        if (path.isEmpty() || path.startsWith("/") || path.utf8Size() > MAXIMUM_PATH_BYTES) return null

        val segments = path.split("/")
        if (segments.size > MAXIMUM_DEPTH) return null
        for (segment in segments) {
            if (segment.isEmpty() ||
                segment.startsWith(".") ||
                segment.utf8Size() > MAXIMUM_SEGMENT_BYTES ||
                segment.codePoints().anyMatch { Character.getType(it) == Character.CONTROL.toInt() }
            ) {
                return null
            }
        }
        return segments
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
}
