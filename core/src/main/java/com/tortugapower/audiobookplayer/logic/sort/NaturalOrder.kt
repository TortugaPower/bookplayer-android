package com.tortugapower.audiobookplayer.logic.sort

import java.text.Collator

/**
 * Locale-aware, numeric-friendly string comparison — the Android counterpart of iOS's
 * `String.localizedStandardCompare`. Text runs are compared with a locale [Collator] (so
 * accents/casing follow the user's locale), while runs of digits are compared by numeric value,
 * so "Chapter 2" sorts before "Chapter 10" instead of after it.
 *
 * `java.text.Collator` alone is locale-aware but NOT numeric ("2" > "10" lexically), so we split
 * each string into alternating non-digit / digit segments and compare segment by segment.
 */
object NaturalOrder : Comparator<String> {

    // SECONDARY strength: case-insensitive but accent-sensitive, matching a typical
    // "natural" library sort where "a" and "A" are equal but "e" and "é" are not.
    private val collator: Collator = Collator.getInstance().apply {
        strength = Collator.SECONDARY
    }

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val aDigit = a[i].isDigit()
            val bDigit = b[j].isDigit()

            // Extract each side's next chunk by ITS OWN type. Both chunks are always non-empty (each
            // includes at least the current char), so the indices always advance — that's what keeps
            // this comparator consistent (transitive). Comparing a digit chunk against a text chunk
            // via the collator is fine and stable; only two digit chunks compare numerically.
            val aEnd = if (aDigit) digitRunEnd(a, i) else textRunEnd(a, i)
            val bEnd = if (bDigit) digitRunEnd(b, j) else textRunEnd(b, j)

            val cmp = if (aDigit && bDigit) {
                compareNumericRuns(a, i, aEnd, b, j, bEnd)
            } else {
                collator.compare(a.substring(i, aEnd), b.substring(j, bEnd))
            }
            if (cmp != 0) return cmp
            i = aEnd
            j = bEnd
        }
        // Whichever string still has characters left sorts after the shorter prefix.
        return (a.length - i) - (b.length - j)
    }

    private fun digitRunEnd(s: String, start: Int): Int {
        var k = start
        while (k < s.length && s[k].isDigit()) k++
        return k
    }

    private fun textRunEnd(s: String, start: Int): Int {
        var k = start
        while (k < s.length && !s[k].isDigit()) k++
        return k
    }

    /** Compare two digit runs [aStart,aEnd) and [bStart,bEnd) as unsigned integers of any length. */
    private fun compareNumericRuns(a: String, aStart: Int, aEnd: Int, b: String, bStart: Int, bEnd: Int): Int {
        var ai = aStart
        var bi = bStart
        // Skip leading zeros so "007" == "7" in magnitude.
        while (ai < aEnd - 1 && a[ai] == '0') ai++
        while (bi < bEnd - 1 && b[bi] == '0') bi++
        val aLen = aEnd - ai
        val bLen = bEnd - bi
        if (aLen != bLen) return aLen - bLen
        var x = ai
        var y = bi
        while (x < aEnd) {
            val d = a[x] - b[y]
            if (d != 0) return d
            x++
            y++
        }
        return 0
    }
}
