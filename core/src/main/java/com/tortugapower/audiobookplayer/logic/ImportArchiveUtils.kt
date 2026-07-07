package com.tortugapower.audiobookplayer.logic

import java.io.File
import java.text.Collator
import java.util.zip.ZipFile

/**
 * Pure helpers for the zip-import pipeline (BookPlayer iOS parity): archive detection,
 * zip-slip-safe extraction, top-level enumeration, collision-free destination naming, and the
 * locale-aware numeric-friendly ordering applied before library insertion.
 */
object ImportArchiveUtils {

    // LPF is an EPUB-adjacent packaged format that is zip-encoded.
    private val ARCHIVE_EXTENSIONS = setOf("zip", "lpf")

    // Extensions that become BOOK items when found inside an archive. Direct picks are already
    // mime-filtered by the system picker; archive contents are not, so cover art / metadata
    // files travel with their folder on disk but don't become library items.
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "mp4", "3gp", "amr", "mka", "webm", "mid",
        // video containers Media3 plays as audio sources
        "mkv", "m4v", "mov"
    )

    fun isArchive(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS

    fun isAudioFile(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    /** Filename without its final extension ("Book.zip" → "Book"); defensive for names without one. */
    fun stripExtension(fileName: String): String =
        fileName.substringBeforeLast('.', fileName).ifBlank { fileName }

    /**
     * Extracts a zip-encoded archive (zip/lpf) into [destDir]. Returns false on any failure —
     * corrupt archive, IO error, or an entry that would escape [destDir] (zip-slip). The caller
     * is expected to discard [destDir] on failure; partial content may remain in it.
     */
    fun extractArchive(archive: File, destDir: File): Boolean {
        return try {
            ZipFile(archive).use { zip ->
                val destRoot = destDir.canonicalFile
                for (entry in zip.entries()) {
                    val target = File(destRoot, entry.name)
                    if (!target.canonicalPath.startsWith(destRoot.canonicalPath + File.separator)) {
                        return false
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The directory's top level only — hidden entries and macOS zip metadata (`__MACOSX`) are
     * skipped, and subdirectories are returned as single entries, never descended into.
     */
    fun topLevelEntries(dir: File): List<File> =
        dir.listFiles().orEmpty()
            .filter { !it.isHidden && !it.name.startsWith(".") && it.name != "__MACOSX" }
            .sortedBy { it.name }

    /**
     * First free destination in [parentDir] for [name]: the name itself, then `-1`, `-2`, …
     * appended before the extension until a free slot is found.
     */
    fun uniqueDestination(parentDir: File, name: String): File {
        var candidate = File(parentDir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = if (base != name) name.substringAfterLast('.') else null
        var index = 1
        while (candidate.exists()) {
            val suffixed = if (ext != null) "$base-$index.$ext" else "$name-$index"
            candidate = File(parentDir, suffixed)
            index++
        }
        return candidate
    }

    /**
     * Locale-aware, numeric-friendly comparator ("Chapter 2" < "Chapter 10"): digit runs compare
     * by numeric value, everything else through the default locale's [Collator].
     */
    val naturalOrderComparator: Comparator<String> = object : Comparator<String> {
        private val collator = Collator.getInstance()

        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                if (a[i].isDigit() && b[j].isDigit()) {
                    val (numA, nextI) = readWhile(a, i) { it.isDigit() }
                    val (numB, nextJ) = readWhile(b, j) { it.isDigit() }
                    val cmp = compareNumeric(numA, numB)
                    if (cmp != 0) return cmp
                    i = nextI
                    j = nextJ
                } else {
                    val (chunkA, nextI) = readWhile(a, i) { !it.isDigit() }
                    val (chunkB, nextJ) = readWhile(b, j) { !it.isDigit() }
                    val cmp = collator.compare(chunkA, chunkB)
                    if (cmp != 0) return cmp
                    i = nextI
                    j = nextJ
                }
            }
            return (a.length - i) - (b.length - j)
        }

        private inline fun readWhile(s: String, start: Int, predicate: (Char) -> Boolean): Pair<String, Int> {
            var end = start
            while (end < s.length && predicate(s[end])) end++
            return s.substring(start, end) to end
        }

        private fun compareNumeric(a: String, b: String): Int {
            val trimmedA = a.trimStart('0').ifEmpty { "0" }
            val trimmedB = b.trimStart('0').ifEmpty { "0" }
            if (trimmedA.length != trimmedB.length) return trimmedA.length - trimmedB.length
            val cmp = trimmedA.compareTo(trimmedB)
            if (cmp != 0) return cmp
            return a.length - b.length
        }
    }
}
