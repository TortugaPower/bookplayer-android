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
 *
 * Memory contract: container headers are walked box-by-box through the [SeekableByteSource] and only
 * the boxes actually parsed are materialized, each under a fixed ceiling. `moov` routinely carries
 * tens of MB of cover art in `udta`; reading it wholesale is what OOM'd 256 MB-heap devices during
 * import (ANDROID-BOOKPLAYER-17/-18). `AudioChapterExtractorMemoryTest` pins the ceilings.
 */
object AudioChapterExtractor {
    private const val MAX_SAMPLE_SIZE = 64 * 1024
    private const val MAX_SAMPLE_COUNT = 100_000
    private const val MAX_CHAPTER_TRAK_SIZE = 8 * 1024 * 1024 // a text track's sample tables; larger is corrupt/hostile
    private const val MAX_HEADER_BOX_SIZE = 64 * 1024         // tkhd / tref payloads
    private const val MAX_ID3_FRAME_SIZE = 64 * 1024          // one CHAP frame: element id + timing + title sub-frames

    /** A box inside an already-materialized byte array: payload is `[start, end)`. See [ByteRange] for boxes still in the source. */
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
            val moov = Mp4Boxes.children(source, 0L, fileSize).firstOrNull { it.type == "moov" } ?: return null
            val traks = Mp4Boxes.children(source, moov.start, moov.end).filter { it.type == "trak" }
            if (traks.isEmpty()) return null

            // Pass 1 — headers only. Each trak's id comes from `tkhd`; the audio trak names the chapter
            // trak through `tref/chap`. Both boxes are ~100 bytes; the rest of the trak is never read.
            val trakById = HashMap<Long, ByteRange>()
            var chapterTrackId: Long? = null
            for (trak in traks) {
                val kids = Mp4Boxes.children(source, trak.start, trak.end)
                val tkhd = kids.firstOrNull { it.type == "tkhd" }?.let { Mp4Boxes.read(source, it, MAX_HEADER_BOX_SIZE) } ?: continue
                val tid = trackIdFromTkhd(tkhd) ?: continue
                trakById[tid] = trak
                if (chapterTrackId == null) {
                    val tref = kids.firstOrNull { it.type == "tref" }?.let { Mp4Boxes.read(source, it, MAX_HEADER_BOX_SIZE) }
                    val chap = tref?.let { firstChild(it, 0, it.size, "chap") }
                    if (tref != null && chap != null && chap.end >= chap.start + 4) chapterTrackId = beU32(tref, chap.start)
                }
            }

            // Pass 2 — materialize the chapter trak alone, and only at a plausible size.
            val chapterTrak = chapterTrackId?.let { trakById[it] } ?: return null
            val trak = Mp4Boxes.read(source, chapterTrak, MAX_CHAPTER_TRAK_SIZE) ?: return null
            return parseTextChapters(trak, Box("trak", 0, trak.size), source, totalDurationMs)
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseTextChapters(data: ByteArray, trak: Box, source: SeekableByteSource, totalDurationMs: Long): List<ExtractedChapter>? {
        val mdhd = descend(data, trak, listOf("mdia", "mdhd")) ?: return null
        val stbl = descend(data, trak, listOf("mdia", "minf", "stbl")) ?: return null
        if (mdhd.start >= mdhd.end) return null
        val mdhdVersion = u8(data, mdhd.start)
        val timescaleOffset = mdhd.start + (if (mdhdVersion == 1) 20 else 12)
        if (timescaleOffset + 4 > mdhd.end) return null
        val timescale = beU32(data, timescaleOffset)
        if (timescale <= 0) return null

        val deltas = parseStts(data, stbl) ?: return null
        val sizes = parseStsz(data, stbl) ?: return null
        val chunkOffsets = parseChunkOffsets(data, stbl) ?: return null
        val stsc = parseStsc(data, stbl) ?: return null
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
            val sample = readExactly(source, offset, size) ?: continue
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

    private fun parseStts(data: ByteArray, stbl: Box): List<Long>? {
        val box = firstChild(data, stbl.start, stbl.end, "stts") ?: return null
        if (box.start + 8 > box.end) return null
        val entryCount = beU32(data, box.start + 4).toInt()
        val deltas = ArrayList<Long>()
        var cursor = box.start + 8
        for (i in 0 until entryCount) {
            if (cursor + 8 > box.end) break
            val count = beU32(data, cursor).toInt()
            val delta = beU32(data, cursor + 4)
            // Bound `count` on its own first: `deltas.size + count` can overflow Int negative for a
            // crafted count near Int.MAX_VALUE, slipping past the guard into a ~2 GB `repeat` → OOM.
            if (count < 0 || count > MAX_SAMPLE_COUNT || deltas.size + count > MAX_SAMPLE_COUNT) return null
            repeat(count) { deltas.add(delta) }
            cursor += 8
        }
        return deltas
    }

    private fun parseStsz(data: ByteArray, stbl: Box): List<Int>? {
        val box = firstChild(data, stbl.start, stbl.end, "stsz") ?: return null
        if (box.start + 12 > box.end) return null
        val uniform = beU32(data, box.start + 4)
        val count = beU32(data, box.start + 8).toInt()
        if (count < 0 || count > MAX_SAMPLE_COUNT) return null
        if (uniform != 0L) return List(count) { uniform.toInt() }
        val sizes = ArrayList<Int>()
        var cursor = box.start + 12
        for (i in 0 until count) {
            if (cursor + 4 > box.end) break
            sizes.add(beU32(data, cursor).toInt())
            cursor += 4
        }
        return sizes
    }

    private fun parseChunkOffsets(data: ByteArray, stbl: Box): List<Long>? {
        firstChild(data, stbl.start, stbl.end, "stco")?.let { return readOffsets(data, it, 4) { o -> beU32(data, o) } }
        firstChild(data, stbl.start, stbl.end, "co64")?.let { return readOffsets(data, it, 8) { o -> beU64(data, o) } }
        return null
    }

    private inline fun readOffsets(data: ByteArray, box: Box, entrySize: Int, read: (Int) -> Long): List<Long>? {
        if (box.start + 8 > box.end) return null
        val count = beU32(data, box.start + 4).toInt()
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

    private fun parseStsc(data: ByteArray, stbl: Box): List<Pair<Int, Int>>? {
        val box = firstChild(data, stbl.start, stbl.end, "stsc") ?: return null
        if (box.start + 8 > box.end) return null
        val entryCount = beU32(data, box.start + 4).toInt()
        if (entryCount < 0 || entryCount > MAX_SAMPLE_COUNT) return null
        val entries = ArrayList<Pair<Int, Int>>()
        var cursor = box.start + 8
        for (i in 0 until entryCount) {
            if (cursor + 12 > box.end) break
            entries.add(beU32(data, cursor).toInt() to beU32(data, cursor + 4).toInt())
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

    /** Track id from a `tkhd` payload: after version/flags + creation/modification times (v0: 32-bit, v1: 64-bit). */
    private fun trackIdFromTkhd(tkhd: ByteArray): Long? {
        if (tkhd.isEmpty()) return null
        val offset = if (u8(tkhd, 0) == 1) 20 else 12
        if (offset + 4 > tkhd.size) return null
        return beU32(tkhd, offset)
    }

    // ---------------------------------------------------------------------------------------------
    // ID3v2 CHAP frames
    // ---------------------------------------------------------------------------------------------

    private fun extractId3Chapters(source: SeekableByteSource, totalDurationMs: Long): List<ExtractedChapter>? {
        val tag = Id3Frames.tag(source) ?: return null

        // Walk frame headers through the source; an APIC cover of any size is stepped over, never read.
        val parsed = ArrayList<Triple<Long, Long?, String>>() // startMs, endMs?, title
        for (frame in Id3Frames.frames(source, tag)) {
            if (frame.id != "CHAP" || frame.size > MAX_ID3_FRAME_SIZE) continue
            readExactly(source, frame.payloadStart, frame.size.toInt())?.let { body -> parseChapFrame(body)?.let { parsed.add(it) } }
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
}
