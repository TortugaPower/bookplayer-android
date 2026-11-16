package com.tortugapower.browserdrop.internal

import java.util.Locale

/** The little JSON the server writes, hand-encoded so the module needs no JSON library. */
internal object Json {
    /** An object of string values, keys sorted (as iOS's `JSONEncoder.sortedKeys`). */
    fun stringObject(fields: Map<String, String>): String =
        fields.toSortedMap().entries.joinToString(",", "{", "}") { (key, value) -> "${quote(key)}:${quote(value)}" }

    fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                // Also escapes `<` and the line separators, so the JSON is safe wherever it's embedded.
                c < ' ' || c == '<' || c == '\u2028' || c == '\u2029' -> append("\\u%04x".format(Locale.ROOT, c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
