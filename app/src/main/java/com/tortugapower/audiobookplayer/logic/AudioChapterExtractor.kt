package com.tortugapower.audiobookplayer.logic

import java.io.File

/** One embedded chapter parsed from a file, with FILE-LOCAL timing (ms). */
data class ExtractedChapter(val title: String, val startMs: Long, val durationMs: Long)

/**
 * Hand-rolled embedded-chapter parsers, ported from iOS `AudioMetadataService`'s manual fallbacks.
 * These recover chapters the native reader (Media3 / MediaMetadataRetriever) won't expose:
 *  - **MP4/QuickTime `chap` text track** — m4b/m4a authored by tools (e.g. MarkAble) that tag the
 *    chapter track with an external data reference / invalid language, so the native API reports none.
 *  - **ID3v2 `CHAP` frames** — MP3s whose chapters have no `CTOC` table-of-contents frame, which
 *    native readers ignore, leaving the `CHAP` frames as opaque blobs.
 *
 * Pure JVM (no Android APIs) so it is unit-tested directly against the shared iOS `ChapterFixtures`.
 * Intended as the fallback under a native-first extractor (see the import wiring in a later phase).
 */
object AudioChapterExtractor {
    private const val MAX_SAMPLE_SIZE = 64 * 1024
    private const val MAX_SAMPLE_COUNT = 100_000
    private const val MAX_MOOV_SIZE = 64 * 1024 * 1024
    private const val MAX_ID3_TAG_SIZE = 16 * 1024 * 1024
    private val QUICKTIME_EXTENSIONS = setOf("m4b", "m4a", "mp4", "m4v", "mov", "aax", "aaxc")

    private data class Box(val type: String, val start: Int, val end: Int)

    /**
     * Parse embedded chapters with the manual parsers only, choosing by container. [totalDurationMs]
     * is used to derive the last chapter's duration. Returns null when no chapters are found.
     */
    fun extractManualChapters(file: File, totalDurationMs: Long): List<ExtractedChapter>? {
        val source = try { FileByteSource(file) } catch (e: Exception) { return null }
        return try {
            extractManualChapters(source, file.extension.lowercase(), totalDurationMs)
        } finally {
            source.close()
        }
    }

    /**
     * Parse embedded chapters from any [SeekableByteSource] (local file OR remote HTTP-`Range` stream),
     * choosing the parser by container [extension]. Same guarantees as the file overload: walks untrusted
     * bytes, so a crafted/corrupt/unreachable source degrades to null (no chapters), never throws.
     */
    fun extractManualChapters(source: SeekableByteSource, extension: String, totalDurationMs: Long): List<ExtractedChapter>? {
        return try {
            if (extension in QUICKTIME_EXTENSIONS) {
                extractQuickTimeTextChapters(source, totalDurationMs) ?: extractId3Chapters(source, totalDurationMs)
            } else {
                extractId3Chapters(source, totalDurationMs)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MP4 / QuickTime text chapter track
    // ---------------------------------------------------------------------------------------------

    private fun extractQuickTimeTextChapters(source: SeekableByteSource, totalDurationMs: Long): List<ExtractedChapter>? {
        try {
            val fileSize = source.size()
            if (fileSize <= 0) return null
            val moov = readTopLevelBox(source, "moov", fileSize) ?: return null
            val traks = childBoxes(moov, 0, moov.size).filter { it.type == "trak" }
            if (traks.isEmpty()) return null

            val trackById = HashMap<Long, Box>()
            var chapterTrackId: Long? = null
            for (trak in traks) {
                val tid = trackId(moov, trak.start, trak.end) ?: continue
                trackById[tid] = trak
                if (chapterTrackId == null) {
                    val tref = firstChild(moov, trak.start, trak.end, "tref")
                    val chap = tref?.let { firstChild(moov, it.start, it.end, "chap") }
                    if (chap != null && chap.end >= chap.start + 4) chapterTrackId = beU32(moov, chap.start)
                }
            }
            val chapterTrak = chapterTrackId?.let { trackById[it] } ?: return null
            return parseTextChapters(moov, chapterTrak, source, totalDurationMs)
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseTextChapters(moov: ByteArray, trak: Box, source: SeekableByteSource, totalDurationMs: Long): List<ExtractedChapter>? {
        val mdhd = descend(moov, trak, listOf("mdia", "mdhd")) ?: return null
        val stbl = descend(moov, trak, listOf("mdia", "minf", "stbl")) ?: return null
        if (mdhd.start >= mdhd.end) return null
        val mdhdVersion = u8(moov, mdhd.start)
        val timescaleOffset = mdhd.start + (if (mdhdVersion == 1) 20 else 12)
        if (timescaleOffset + 4 > mdhd.end) return null
        val timescale = beU32(moov, timescaleOffset)
        if (timescale <= 0) return null

        val deltas = parseStts(moov, stbl) ?: return null
        val sizes = parseStsz(moov, stbl) ?: return null
        val chunkOffsets = parseChunkOffsets(moov, stbl) ?: return null
        val stsc = parseStsc(moov, stbl) ?: return null
        val locations = sampleLocations(sizes, chunkOffsets, stsc)

        val sampleCount = minOf(locations.size, deltas.size)
        if (sampleCount <= 0) return null

        // Sample start times = cumulative sum of per-sample durations (stts) / timescale.
        val startsMs = LongArray(sampleCount)
        var cumulative = 0L
        for (i in 0 until sampleCount) {
            startsMs[i] = cumulative * 1000L / timescale
            cumulative += deltas[i]
        }

        val chapters = ArrayList<ExtractedChapter>()
        for (i in 0 until sampleCount) {
            val (offset, size) = locations[i]
            if (size < 2 || size > MAX_SAMPLE_SIZE) continue
            val sample = readBytes(source, offset, size) ?: continue
            if (sample.size < 2) continue
            val titleLength = beU16(sample, 0)
            val titleEnd = minOf(2 + titleLength, sample.size)
            val title = decodeText(sample.copyOfRange(2, titleEnd))
            val startMs = startsMs[i]
            val nextMs = if (i < sampleCount - 1) startsMs[i + 1] else totalDurationMs
            chapters.add(ExtractedChapter(title, startMs, (nextMs - startMs).coerceAtLeast(0)))
        }
        return chapters.ifEmpty { null }
    }

    // --- MP4 sample tables ---

    private fun parseStts(moov: ByteArray, stbl: Box): List<Long>? {
        val box = firstChild(moov, stbl.start, stbl.end, "stts") ?: return null
        if (box.start + 8 > box.end) return null
        val entryCount = beU32(moov, box.start + 4).toInt()
        val deltas = ArrayList<Long>()
        var cursor = box.start + 8
        for (i in 0 until entryCount) {
            if (cursor + 8 > box.end) break
            val count = beU32(moov, cursor).toInt()
            val delta = beU32(moov, cursor + 4)
            // Bound `count` on its own first: `deltas.size + count` can overflow Int negative for a
            // crafted count near Int.MAX_VALUE, slipping past the guard into a ~2 GB `repeat` → OOM.
            if (count < 0 || count > MAX_SAMPLE_COUNT || deltas.size + count > MAX_SAMPLE_COUNT) return null
            repeat(count) { deltas.add(delta) }
            cursor += 8
        }
        return deltas
    }

    private fun parseStsz(moov: ByteArray, stbl: Box): List<Int>? {
        val box = firstChild(moov, stbl.start, stbl.end, "stsz") ?: return null
        if (box.start + 12 > box.end) return null
        val uniform = beU32(moov, box.start + 4)
        val count = beU32(moov, box.start + 8).toInt()
        if (count < 0 || count > MAX_SAMPLE_COUNT) return null
        if (uniform != 0L) return List(count) { uniform.toInt() }
        val sizes = ArrayList<Int>()
        var cursor = box.start + 12
        for (i in 0 until count) {
            if (cursor + 4 > box.end) break
            sizes.add(beU32(moov, cursor).toInt())
            cursor += 4
        }
        return sizes
    }

    private fun parseChunkOffsets(moov: ByteArray, stbl: Box): List<Long>? {
        firstChild(moov, stbl.start, stbl.end, "stco")?.let { return readOffsets(moov, it, 4) { o -> beU32(moov, o) } }
        firstChild(moov, stbl.start, stbl.end, "co64")?.let { return readOffsets(moov, it, 8) { o -> beU64(moov, o) } }
        return null
    }

    private inline fun readOffsets(moov: ByteArray, box: Box, entrySize: Int, read: (Int) -> Long): List<Long>? {
        if (box.start + 8 > box.end) return null
        val count = beU32(moov, box.start + 4).toInt()
        if (count < 0 || count > MAX_SAMPLE_COUNT) return null
        val offsets = ArrayList<Long>()
        var cursor = box.start + 8
        for (i in 0 until count) {
            if (cursor + entrySize > box.end) break
            offsets.add(read(cursor))
            cursor += entrySize
        }
        return offsets
    }

    private fun parseStsc(moov: ByteArray, stbl: Box): List<Pair<Int, Int>>? {
        val box = firstChild(moov, stbl.start, stbl.end, "stsc") ?: return null
        if (box.start + 8 > box.end) return null
        val entryCount = beU32(moov, box.start + 4).toInt()
        if (entryCount < 0 || entryCount > MAX_SAMPLE_COUNT) return null
        val entries = ArrayList<Pair<Int, Int>>()
        var cursor = box.start + 8
        for (i in 0 until entryCount) {
            if (cursor + 12 > box.end) break
            entries.add(beU32(moov, cursor).toInt() to beU32(moov, cursor + 4).toInt())
            cursor += 12
        }
        return entries.ifEmpty { null }
    }

    private fun sampleLocations(sizes: List<Int>, chunkOffsets: List<Long>, stsc: List<Pair<Int, Int>>): List<Pair<Long, Int>> {
        val locations = ArrayList<Pair<Long, Int>>()
        var sampleIndex = 0
        for (chunkIndex in chunkOffsets.indices) {
            val chunkNumber = chunkIndex + 1
            var samplesPerChunk = stsc[0].second
            for (entry in stsc) {
                if (entry.first <= chunkNumber) samplesPerChunk = entry.second else break
            }
            var offset = chunkOffsets[chunkIndex]
            repeat(samplesPerChunk) {
                if (sampleIndex >= sizes.size) return locations
                val size = sizes[sampleIndex]
                locations.add(offset to size)
                offset += size
                sampleIndex++
            }
        }
        return locations
    }

    // --- MP4 box navigation ---

    private fun readTopLevelBox(source: SeekableByteSource, name: String, fileSize: Long): ByteArray? {
        var offset = 0L
        while (offset + 8 <= fileSize) {
            val header = source.readAt(offset, 16) ?: return null
            if (header.size < 8) return null
            val size32 = beU32(header, 0)
            var boxSize = size32
            var headerSize = 8L
            when (size32) {
                1L -> { if (header.size < 16) return null; boxSize = beU64(header, 8); headerSize = 16L }
                0L -> boxSize = fileSize - offset
            }
            if (boxSize < headerSize || boxSize > fileSize - offset) return null
            if (type4(header, 4) == name) {
                val payloadLength = boxSize - headerSize
                if (payloadLength > MAX_MOOV_SIZE) return null
                return readBytes(source, offset + headerSize, payloadLength.toInt())
            }
            offset += boxSize
        }
        return null
    }

    private fun childBoxes(data: ByteArray, start: Int, end: Int): List<Box> {
        val children = ArrayList<Box>()
        var cursor = start
        while (cursor + 8 <= end) {
            val size32 = beU32(data, cursor)
            var size = size32.toInt()
            var headerSize = 8
            when (size32) {
                1L -> {
                    if (cursor + 16 > end) break
                    val extended = beU64(data, cursor + 8)
                    size = if (extended > (end - cursor).toLong()) (end - cursor + 1) else extended.toInt()
                    headerSize = 16
                }
                0L -> size = end - cursor
            }
            if (size < headerSize || cursor + size > end) break
            children.add(Box(type4(data, cursor + 4), cursor + headerSize, cursor + size))
            cursor += size
        }
        return children
    }

    private fun firstChild(data: ByteArray, start: Int, end: Int, type: String): Box? =
        childBoxes(data, start, end).firstOrNull { it.type == type }

    private fun descend(data: ByteArray, root: Box, path: List<String>): Box? {
        var current = root
        for (type in path) {
            current = firstChild(data, current.start, current.end, type) ?: return null
        }
        return current
    }

    private fun trackId(data: ByteArray, trakStart: Int, trakEnd: Int): Long? {
        val tkhd = firstChild(data, trakStart, trakEnd, "tkhd") ?: return null
        if (tkhd.start >= tkhd.end) return null
        val version = u8(data, tkhd.start)
        val offset = tkhd.start + (if (version == 1) 20 else 12)
        if (offset + 4 > tkhd.end) return null
        return beU32(data, offset)
    }

    // ---------------------------------------------------------------------------------------------
    // ID3v2 CHAP frames
    // ---------------------------------------------------------------------------------------------

    private fun extractId3Chapters(source: SeekableByteSource, totalDurationMs: Long): List<ExtractedChapter>? {
        val tag = readId3Tag(source) ?: return null
        val major = tag.major
        val body = tag.body

        val parsed = ArrayList<Triple<Long, Long?, String>>() // startMs, endMs?, title
        var cursor = 0
        while (cursor + 10 <= body.size) {
            val id = type4(body, cursor)
            if (id.isEmpty() || id[0].code == 0 || !id.all { it.isLetterOrDigit() }) break // padding / end of frames
            val size = frameSize(body, cursor + 4, major, cursor + 10, body.size)
            val payloadStart = cursor + 10
            // Long addition: a crafted size near Int.MAX_VALUE would overflow `payloadStart + size`
            // negative and slip past this guard, then throw in copyOfRange.
            if (size <= 0 || payloadStart.toLong() + size > body.size) break
            if (id == "CHAP") {
                parseChapFrame(body.copyOfRange(payloadStart, payloadStart + size))?.let { parsed.add(it) }
            }
            cursor = payloadStart + size
        }
        if (parsed.isEmpty()) return null

        parsed.sortBy { it.first }
        val chapters = ArrayList<ExtractedChapter>()
        for (i in parsed.indices) {
            val (startMs, endMs, title) = parsed[i]
            val duration = when {
                endMs != null && endMs > startMs -> endMs - startMs
                i < parsed.size - 1 -> parsed[i + 1].first - startMs
                else -> totalDurationMs - startMs
            }
            chapters.add(ExtractedChapter(title, startMs, duration.coerceAtLeast(0)))
        }
        return chapters.ifEmpty { null }
    }

    private class Id3Tag(val major: Int, val body: ByteArray)

    private fun readId3Tag(source: SeekableByteSource): Id3Tag? {
        try {
            val fileSize = source.size()
            if (fileSize < 10) return null
            val head = source.readAt(0, 10) ?: return null
            if (head.size < 10) return null
            if (head[0].toInt() != 'I'.code || head[1].toInt() != 'D'.code || head[2].toInt() != '3'.code) return null
            val major = u8(head, 3)
            if (major < 3) return null // CHAP frames are ID3v2.3+
            val tagSize = synchsafe(head, 6)
            if (tagSize <= 0 || tagSize > MAX_ID3_TAG_SIZE) return null
            val bodyLength = minOf(tagSize.toLong(), fileSize - 10).toInt()
            val body = readBytes(source, 10, bodyLength) ?: return null
            return Id3Tag(major, body)
        } catch (e: Exception) {
            return null
        }
    }

    /** CHAP body: element-id (null-terminated), start/end ms + start/end byte offset (4×UInt32 BE), sub-frames. */
    private fun parseChapFrame(body: ByteArray): Triple<Long, Long?, String>? {
        val terminator = body.indexOfFirst { it.toInt() == 0 }
        if (terminator < 0) return null
        var cursor = terminator + 1
        if (cursor + 16 > body.size) return null
        val startMs = beU32(body, cursor)
        val endMs = beU32(body, cursor + 4)
        cursor += 16
        val end: Long? = if (endMs == 0xFFFFFFFFL || endMs <= startMs) null else endMs
        return Triple(startMs, end, parseChapTitle(body, cursor))
    }

    /** Walk a CHAP frame's sub-frames and return the TIT2 (title) text. */
    private fun parseChapTitle(body: ByteArray, start: Int): String {
        var cursor = start
        while (cursor + 10 <= body.size) {
            val frameId = type4(body, cursor)
            val payloadStart = cursor + 10
            // The CHAP blob doesn't carry the tag version; infer synchsafe vs plain per sub-frame.
            val plainSize = beU32(body, cursor + 4).toInt()
            val synchsafeSize = synchsafe(body, cursor + 4)
            val bytesAreSynchsafe = (4..7).all { (body[cursor + it].toInt() and 0x80) == 0 }
            var size = if (bytesAreSynchsafe) synchsafeSize else plainSize
            if (payloadStart.toLong() + size > body.size) {
                size = if (bytesAreSynchsafe) plainSize else synchsafeSize
            } else if (bytesAreSynchsafe && plainSize != synchsafeSize && payloadStart.toLong() + plainSize == body.size.toLong()) {
                size = plainSize
            }
            if (size <= 0 || payloadStart.toLong() + size > body.size) break
            if (frameId == "TIT2") return decodeId3Text(body.copyOfRange(payloadStart, payloadStart + size))
            cursor = payloadStart + size
        }
        return ""
    }

    /** Outer ID3 frame size: v2.4 is synchsafe, v2.3 plain; fall back to the other if it overruns. */
    private fun frameSize(data: ByteArray, offset: Int, major: Int, payloadStart: Int, limit: Int): Int {
        val plain = beU32(data, offset).toInt()
        val synchsafeSize = synchsafe(data, offset)
        var size = if (major >= 4) synchsafeSize else plain
        if (payloadStart.toLong() + size > limit) size = if (major >= 4) plain else synchsafeSize
        return size
    }

    private fun decodeId3Text(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val encoding = payload[0].toInt() and 0xFF
        var bytes = payload.copyOfRange(1, payload.size)
        return when (encoding) {
            0 -> { // ISO-8859-1
                if (bytes.isNotEmpty() && bytes.last().toInt() == 0) bytes = bytes.copyOf(bytes.size - 1)
                String(bytes, Charsets.ISO_8859_1)
            }
            1 -> decodeUtf16WithBom(stripUtf16Terminator(bytes)) // UTF-16 with BOM
            2 -> String(stripUtf16Terminator(bytes), Charsets.UTF_16BE) // UTF-16BE no BOM
            else -> { // 3 = UTF-8 (and unknown, treated leniently)
                if (bytes.isNotEmpty() && bytes.last().toInt() == 0) bytes = bytes.copyOf(bytes.size - 1)
                String(bytes, Charsets.UTF_8)
            }
        }
    }

    private fun stripUtf16Terminator(bytes: ByteArray): ByteArray =
        if (bytes.size >= 2 && bytes.size % 2 == 0 && bytes[bytes.size - 1].toInt() == 0 && bytes[bytes.size - 2].toInt() == 0)
            bytes.copyOf(bytes.size - 2) else bytes

    private fun decodeUtf16WithBom(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.UTF_16LE)
    }

    // ---------------------------------------------------------------------------------------------
    // Text + primitive readers
    // ---------------------------------------------------------------------------------------------

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if ((b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE)) return String(bytes, Charsets.UTF_16)
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** Exactly [length] bytes at [offset], or null if the source can't provide the full range. */
    private fun readBytes(source: SeekableByteSource, offset: Long, length: Int): ByteArray? {
        if (length <= 0) return null
        val bytes = source.readAt(offset, length) ?: return null
        return if (bytes.size == length) bytes else null
    }

    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    private fun beU16(data: ByteArray, offset: Int): Int =
        if (offset + 2 <= data.size) (u8(data, offset) shl 8) or u8(data, offset + 1) else 0

    private fun beU32(data: ByteArray, offset: Int): Long =
        if (offset + 4 <= data.size)
            (u8(data, offset).toLong() shl 24) or (u8(data, offset + 1).toLong() shl 16) or
                (u8(data, offset + 2).toLong() shl 8) or u8(data, offset + 3).toLong()
        else 0L

    private fun beU64(data: ByteArray, offset: Int): Long {
        if (offset + 8 > data.size) return 0L
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or u8(data, offset + i).toLong()
        return value
    }

    /** 28-bit ID3v2 synchsafe integer (7 bits per byte, top bit always clear). */
    private fun synchsafe(data: ByteArray, offset: Int): Int =
        if (offset + 4 <= data.size)
            ((u8(data, offset) and 0x7F) shl 21) or ((u8(data, offset + 1) and 0x7F) shl 14) or
                ((u8(data, offset + 2) and 0x7F) shl 7) or (u8(data, offset + 3) and 0x7F)
        else 0

    private fun type4(data: ByteArray, offset: Int): String =
        if (offset + 4 <= data.size) String(data, offset, 4, Charsets.ISO_8859_1) else ""
}
