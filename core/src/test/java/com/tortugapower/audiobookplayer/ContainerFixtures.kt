package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.logic.SeekableByteSource
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Fixture surgery for the container parsers' memory tests. Test files are built as *segments* — real
 * [ByteArray]s interleaved with [Long] counts of virtual zero bytes — so a 40 MB cover costs nothing
 * to describe and is only ever materialized if the code under test asks for it (which is the bug).
 */
internal object ContainerFixtures {
    const val MB = 1024 * 1024
    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    fun fixtureBytes(name: String): ByteArray =
        (ContainerFixtures::class.java.getResourceAsStream("/chapterfixtures/$name") ?: error("Missing fixture $name")).use { it.readBytes() }

    fun segmentLength(segments: List<Any>): Long = segments.sumOf { seg -> if (seg is ByteArray) seg.size.toLong() else seg as Long }

    /** Materializes small segment lists (virtual zeros become real zeros). */
    fun toBytes(segments: List<Any>): ByteArray {
        val out = ByteArrayOutputStream()
        for (seg in segments) if (seg is ByteArray) out.write(seg) else out.write(ByteArray((seg as Long).toInt()))
        return out.toByteArray()
    }

    // --- MP4 -------------------------------------------------------------------------------------

    /**
     * The chapter fixtures end with `moov`, so appending a child inside it (and growing moov's size
     * field) moves no `stco` sample offsets. [payload] becomes the new child's payload.
     */
    fun m4bWithMoovChild(fixture: ByteArray, type: String, payload: List<Any>): List<Any> {
        val (moovStart, moovSize) = findTopLevel(fixture, "moov")
        require(moovStart + moovSize == fixture.size) { "fixture must end with moov" }
        val payloadLength = segmentLength(payload)
        val head = fixture.copyOf(fixture.size + 8)
        writeU32(head, moovStart, moovSize + 8 + payloadLength)
        writeU32(head, fixture.size, 8 + payloadLength)
        type.toByteArray(Charsets.ISO_8859_1).copyInto(head, fixture.size + 4)
        return listOf(head) + payload
    }

    /** Inflates the second `trak` (the chapter text track) by [extraPayload] zero bytes inserted right after it. */
    fun m4bWithInflatedTextTrak(fixture: ByteArray, extraPayload: Long): List<Any> {
        val (moovStart, moovSize) = findTopLevel(fixture, "moov")
        val traks = children(fixture, moovStart + 8, moovStart + moovSize).filter { it.type == "trak" }
        require(traks.size == 2) { "fixture must have audio + text traks" }
        val text = traks[1]
        val head = fixture.copyOf(text.end)
        val tail = fixture.copyOfRange(text.end, fixture.size)
        writeU32(head, moovStart, moovSize + extraPayload)
        writeU32(head, text.start, text.size + extraPayload)
        return listOf(head, extraPayload, tail)
    }

    /**
     * `udta { meta { version/flags, ilst { covr { data { type, locale, image } } } } }` — the iTunes-style
     * cover-art chain, with [image] as segments so it can be huge and virtual.
     */
    fun coverArtUdta(image: List<Any>, typeIndicator: Int = 14 /* PNG */): List<Any> {
        val imageLength = segmentLength(image)
        val dataSize = 8 + 8 + imageLength
        val covrSize = 8 + dataSize
        val ilstSize = 8 + covrSize
        val metaSize = 8 + 4 + ilstSize
        val udtaSize = 8 + metaSize
        val headers = ByteArrayOutputStream()
        fun box(size: Long, type: String) { headers.write(u32(size)); headers.write(type.toByteArray(Charsets.ISO_8859_1)) }
        box(udtaSize, "udta")
        box(metaSize, "meta"); headers.write(ByteArray(4))          // FullBox version + flags
        box(ilstSize, "ilst")
        box(covrSize, "covr")
        box(dataSize, "data"); headers.write(u32(typeIndicator.toLong())); headers.write(ByteArray(4)) // locale
        return listOf(headers.toByteArray()) + image
    }

    /** [coverArtUdta] minus the outer `udta` header — what [m4bWithMoovChild] expects when it wraps a `udta` itself. */
    fun coverArtUdtaPayload(image: List<Any>, typeIndicator: Int = 14): List<Any> {
        val chain = coverArtUdta(image, typeIndicator)
        val headers = chain[0] as ByteArray
        return listOf(headers.copyOfRange(8, headers.size)) + chain.drop(1)
    }

    // --- ID3 -------------------------------------------------------------------------------------

    class Id3Frame(val id: String, val payload: List<Any>, val formatFlags: Int = 0)

    /** Inserts [frames] ahead of the fixture's own frames and grows the tag size accordingly (v2.3 or v2.4 sizes). */
    fun mp3WithLeadingFrames(fixture: ByteArray, frames: List<Id3Frame>, tagFlags: Int? = null): List<Any> {
        require(fixture[0] == 'I'.code.toByte()) { "fixture must carry an ID3v2 tag" }
        val major = fixture[3].toInt()
        val oldTagSize = synchsafe(fixture, 6)
        val added = frames.sumOf { 10 + segmentLength(it.payload) }
        val header = fixture.copyOf(10)
        if (tagFlags != null) header[5] = tagFlags.toByte()
        writeSynchsafe(header, 6, oldTagSize + added)
        val segments = ArrayList<Any>()
        segments.add(header)
        for (frame in frames) {
            val fh = ByteArray(10)
            frame.id.toByteArray(Charsets.ISO_8859_1).copyInto(fh, 0)
            val size = segmentLength(frame.payload)
            if (major >= 4) writeSynchsafe(fh, 4, size) else writeU32(fh, 4, size)
            fh[9] = frame.formatFlags.toByte()
            segments.add(fh)
            segments.addAll(frame.payload)
        }
        segments.add(fixture.copyOfRange(10, fixture.size))
        return segments
    }

    /** APIC payload: encoding, MIME (NUL), picture type, description (NUL per encoding), image segments. */
    fun apicPayload(image: List<Any>, pictureType: Int = 3, mime: String = "image/png", encoding: Int = 0, description: String = ""): List<Any> {
        val head = ByteArrayOutputStream()
        head.write(encoding)
        head.write(mime.toByteArray(Charsets.ISO_8859_1)); head.write(0)
        head.write(pictureType)
        when (encoding) {
            1 -> { head.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())); head.write(description.toByteArray(Charsets.UTF_16LE)); head.write(byteArrayOf(0, 0)) }
            2 -> { head.write(description.toByteArray(Charsets.UTF_16BE)); head.write(byteArrayOf(0, 0)) }
            else -> { head.write(description.toByteArray(Charsets.ISO_8859_1)); head.write(0) }
        }
        return listOf(head.toByteArray()) + image
    }

    // --- images ----------------------------------------------------------------------------------

    /** A real, decodable [width]x[height] opaque RGB PNG (solid colour), a few hundred bytes. */
    fun tinyPng(width: Int, height: Int): ByteArray {
        val raw = ByteArray((1 + width * 3) * height)
        for (y in 0 until height) {
            val row = y * (1 + width * 3)
            for (x in 0 until width) { raw[row + 1 + x * 3] = 0x20; raw[row + 2 + x * 3] = 0x80.toByte(); raw[row + 3 + x * 3] = 0xC0.toByte() }
        }
        val deflater = Deflater()
        deflater.setInput(raw); deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) compressed.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        fun chunk(type: String, data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(u32(data.size.toLong())); val t = type.toByteArray(Charsets.ISO_8859_1); out.write(t); out.write(data)
            val crc = CRC32(); crc.update(t); crc.update(data); out.write(u32(crc.value))
            return out.toByteArray()
        }
        val ihdr = ByteArrayOutputStream().apply { write(u32(width.toLong())); write(u32(height.toLong())); write(byteArrayOf(8, 2, 0, 0, 0)) }
        val png = ByteArrayOutputStream()
        png.write(PNG_SIGNATURE); png.write(chunk("IHDR", ihdr.toByteArray())); png.write(chunk("IDAT", compressed.toByteArray())); png.write(chunk("IEND", ByteArray(0)))
        return png.toByteArray()
    }

    // --- primitives ------------------------------------------------------------------------------

    data class Child(val type: String, val start: Int, val size: Int) { val end get() = start + size }

    fun children(data: ByteArray, start: Int, end: Int): List<Child> {
        val out = ArrayList<Child>()
        var cursor = start
        while (cursor + 8 <= end) {
            val size = beU32(data, cursor).toInt()
            if (size < 8) break
            out.add(Child(String(data, cursor + 4, 4, Charsets.ISO_8859_1), cursor, size))
            cursor += size
        }
        return out
    }

    fun findTopLevel(data: ByteArray, type: String): Pair<Int, Int> =
        children(data, 0, data.size).first { it.type == type }.let { it.start to it.size }

    fun beU32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xFF) shl 24) or ((d[o + 1].toLong() and 0xFF) shl 16) or
            ((d[o + 2].toLong() and 0xFF) shl 8) or (d[o + 3].toLong() and 0xFF)

    fun u32(v: Long): ByteArray = ByteArray(4).also { writeU32(it, 0, v) }

    fun writeU32(d: ByteArray, o: Int, v: Long) {
        require(v in 0..0xFFFFFFFFL)
        d[o] = (v ushr 24).toByte(); d[o + 1] = (v ushr 16).toByte(); d[o + 2] = (v ushr 8).toByte(); d[o + 3] = v.toByte()
    }

    fun synchsafe(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0x7F) shl 21) or ((d[o + 1].toLong() and 0x7F) shl 14) or
            ((d[o + 2].toLong() and 0x7F) shl 7) or (d[o + 3].toLong() and 0x7F)

    fun writeSynchsafe(d: ByteArray, o: Int, v: Long) {
        require(v < (1L shl 28))
        d[o] = ((v ushr 21) and 0x7F).toByte(); d[o + 1] = ((v ushr 14) and 0x7F).toByte()
        d[o + 2] = ((v ushr 7) and 0x7F).toByte(); d[o + 3] = (v and 0x7F).toByte()
    }
}

/**
 * Serves a sequence of segments — each a [ByteArray] or a [Long] count of virtual zero bytes — and
 * fails any single read above [readCeiling]. Records the largest read for the tests' assertions.
 */
internal class BoundedSource(private val segments: List<Any>, private val readCeiling: Int = 8 * ContainerFixtures.MB) : SeekableByteSource {
    var largestRead = 0
    private val total: Long = ContainerFixtures.segmentLength(segments)

    override fun size(): Long = total

    override fun readAt(offset: Long, length: Int): ByteArray? {
        if (length <= 0) return ByteArray(0)
        if (length > readCeiling) throw AssertionError("code under test asked for a $length-byte read; ceiling is $readCeiling")
        largestRead = maxOf(largestRead, length)
        if (offset < 0 || offset >= total) return ByteArray(0)
        val n = minOf(length.toLong(), total - offset).toInt()
        val out = ByteArray(n) // zero-filled: virtual segments need no copying
        var segStart = 0L
        for (seg in segments) {
            val segLen = if (seg is ByteArray) seg.size.toLong() else seg as Long
            val segEnd = segStart + segLen
            if (seg is ByteArray && segEnd > offset && segStart < offset + n) {
                val from = maxOf(offset, segStart)
                val to = minOf(offset + n, segEnd)
                System.arraycopy(seg, (from - segStart).toInt(), out, (from - offset).toInt(), (to - from).toInt())
            }
            segStart = segEnd
            if (segStart >= offset + n) break
        }
        return out
    }

    override fun close() {}
}
