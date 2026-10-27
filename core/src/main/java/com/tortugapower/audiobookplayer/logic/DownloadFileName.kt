package com.tortugapower.audiobookplayer.logic

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * The name a downloaded file is saved and imported under. The caller's name is a guess made before the
 * request — media-server list items often carry no file name, so "<title>.mp3" stands in — while the
 * server knows the real one: Audiobookshelf answers a book download with "<title>.zip" (a zip of the
 * book's folder), Jellyfin with the original "<file>.m4b". Archive expansion and the importer go by this
 * name, so a guessed ".mp3" on a zip left an unplayable file. Mirrors iOS, which takes
 * `URLResponse.suggestedFilename` (Content-Disposition first).
 */
object DownloadFileName {

    private val EXTENDED = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
    private val QUOTED = Regex("""filename\s*=\s*"((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""filename\s*=\s*([^;\s"]+)""", RegexOption.IGNORE_CASE)
    private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\u0003\u0004"

    /**
     * The file name in a Content-Disposition header (RFC 6266): the RFC 5987 `filename*` form first, then
     * `filename`. Only the last path segment is kept, so a path in the header never becomes one on disk,
     * and a name with control characters (a decoded %00 or %0A) is rejected. Null when the header is absent
     * or names no usable file.
     */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val name = EXTENDED.find(header)?.let { match ->
            val charset = runCatching { Charset.forName(match.groupValues[1].ifBlank { "UTF-8" }) }
                .getOrDefault(Charsets.UTF_8)
            percentDecode(match.groupValues[2].trim(), charset)
        } ?: QUOTED.find(header)?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1")
            ?: BARE.find(header)?.groupValues?.get(1)
        return name?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()
            ?.takeIf { candidate -> candidate.isNotEmpty() && candidate.none { it.isISOControl() } }
    }

    /**
     * The name to save the download under: the server's Content-Disposition name when it carries an
     * extension, else [requested]. Either way it is sanitized for the file system.
     */
    fun resolve(requested: String, contentDisposition: String?): String {
        val fromServer = fromContentDisposition(contentDisposition)?.let(FilenameUtils::sanitizeFilename)
        return if (fromServer != null && fromServer.substringAfterLast('.', "").isNotEmpty()) fromServer
        else FilenameUtils.sanitizeFilename(requested)
    }

    /** True when [head] starts with a zip local-file header. */
    fun looksLikeZip(head: ByteArray): Boolean =
        head.size >= ZIP_SIGNATURE.size && ZIP_SIGNATURE.indices.all { head[it] == ZIP_SIGNATURE[it] }

    /**
     * [name] with a ".zip" extension when the downloaded bytes are a zip it doesn't declare (a server or
     * proxy that drops Content-Disposition), so archive expansion still runs.
     */
    fun archiveAware(name: String, head: ByteArray): String =
        if (looksLikeZip(head) && !ImportArchiveUtils.isArchive(name)) "${ImportArchiveUtils.stripExtension(name)}.zip"
        else name

    private fun percentDecode(value: String, charset: Charset): String? {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.write(byte)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return String(bytes.toByteArray(), charset)
    }
}
