package com.tortugapower.audiobookplayer.logic

import java.io.IOException
import java.io.InputStream

/**
 * Bounded readers for the two container formats the app parses by hand: MP4/QuickTime boxes and
 * ID3v2 frames. Everything here walks headers through a [SeekableByteSource] and hands back file
 * RANGES; callers decide what to materialize, each under an explicit ceiling. Nothing in this file
 * allocates in proportion to the file. Reading a whole `moov` or ID3 tag is what OOM'd 256 MB-heap
 * devices on files with tens of MB of embedded cover art (Sentry ANDROID-BOOKPLAYER-17/-18).
 */

/** File extensions handled as MP4/QuickTime containers. */
internal val QUICKTIME_EXTENSIONS = setOf("m4b", "m4a", "mp4", "m4v", "mov", "aax", "aaxc")

/** A box or frame located in the source: its payload is the absolute range `[start, end)`. */
internal data class ByteRange(val type: String, val start: Long, val end: Long) {
    val size: Long get() = end - start
}

internal object Mp4Boxes {
    /** Walk guard against a crafted chain of tiny boxes. */
    private const val MAX_CHILDREN = 4096

    /**
     * The direct children of the source range `[start, end)`, located by reading 16-byte headers only.
     * A malformed size ends the walk; the children found so far are returned.
     */
    fun children(source: SeekableByteSource, start: Long, end: Long): List<ByteRange> {
        val children = ArrayList<ByteRange>()
        var cursor = start
        while (cursor + 8 <= end && children.size < MAX_CHILDREN) {
            val header = source.readAt(cursor, 16) ?: break
            if (header.size < 8) break
            val size32 = beU32(header, 0)
            var boxSize = size32
            var headerSize = 8L
            when (size32) {
                1L -> {
                    if (header.size < 16) return children
                    boxSize = beU64(header, 8)
                    headerSize = 16L
                }
                0L -> boxSize = end - cursor
            }
            if (boxSize < headerSize || boxSize > end - cursor) break
            children.add(ByteRange(type4(header, 4), cursor + headerSize, cursor + boxSize))
            cursor += boxSize
        }
        return children
    }

    /** Children of a FullBox such as `meta`, whose payload opens with 4 bytes of version + flags. */
    fun fullBoxChildren(source: SeekableByteSource, box: ByteRange): List<ByteRange> =
        children(source, box.start + 4, box.end)

    /** The payload of [range], or null when it is empty, over [maxSize], or not fully readable. */
    fun read(source: SeekableByteSource, range: ByteRange, maxSize: Int): ByteArray? {
        if (range.size <= 0 || range.size > maxSize) return null
        return readExactly(source, range.start, range.size.toInt())
    }
}

internal object Id3Frames {
    /** Where a tag's frames live: `[bodyStart, bodyEnd)`, clamped to the file. */
    class Tag(val major: Int, val flags: Int, val bodyStart: Long, val bodyEnd: Long) {
        /** Whole-tag unsynchronisation (v2.3): payload byte ranges are not the raw frame contents. */
        val unsynchronised: Boolean get() = (flags and 0x80) != 0
    }

    /** One frame: payload is `[payloadStart, payloadStart + size)`; [formatFlags] is the second flags byte. */
    class Frame(val id: String, val payloadStart: Long, val size: Long, private val major: Int, private val formatFlags: Int) {
        /**
         * False when the payload is compressed, encrypted, unsynchronised or prefixed with a data-length
         * indicator — i.e. when the bytes in the file are not the frame's contents.
         */
        val isRawPayload: Boolean
            get() = if (major >= 4) (formatFlags and 0x0F) == 0 else (formatFlags and 0xC0) == 0
    }

    /** The ID3v2 header at offset 0, or null when absent or older than v2.3 (the first version with CHAP/APIC as used here). */
    fun tag(source: SeekableByteSource): Tag? {
        return try {
            val fileSize = source.size()
            if (fileSize < 10) return null
            val head = source.readAt(0, 10) ?: return null
            if (head.size < 10) return null
            if (head[0].toInt() != 'I'.code || head[1].toInt() != 'D'.code || head[2].toInt() != '3'.code) return null
            val major = u8(head, 3)
            if (major < 3) return null
            val tagSize = synchsafe(head, 6)
            if (tagSize <= 0) return null
            Tag(major, u8(head, 5), 10L, minOf(10L + tagSize, fileSize))
        } catch (e: Exception) {
            null
        }
    }

    /** The tag's frames in file order, reading 10-byte headers only; stops at padding or a malformed size. */
    fun frames(source: SeekableByteSource, tag: Tag): Sequence<Frame> = sequence {
        var cursor = tag.bodyStart
        while (cursor + 10 <= tag.bodyEnd) {
            val header = readExactly(source, cursor, 10) ?: break
            val id = type4(header, 0)
            if (id.isEmpty() || id[0].code == 0 || !id.all { it.isLetterOrDigit() }) break // padding / end of frames
            val payloadStart = cursor + 10
            val size = frameSize(header, tag.major, payloadStart, tag.bodyEnd)
            if (size <= 0 || payloadStart + size > tag.bodyEnd) break
            yield(Frame(id, payloadStart, size, tag.major, u8(header, 9)))
            cursor = payloadStart + size
        }
    }

    /** Outer frame size: v2.4 is synchsafe, v2.3 plain; fall back to the other reading if it overruns [limit]. */
    private fun frameSize(header: ByteArray, major: Int, payloadStart: Long, limit: Long): Long {
        val plain = beU32(header, 4)
        val synchsafeSize = synchsafe(header, 4).toLong()
        var size = if (major >= 4) synchsafeSize else plain
        if (payloadStart + size > limit) size = if (major >= 4) plain else synchsafeSize
        return size
    }
}

/**
 * A sequential [InputStream] over `[start, start + length)` of a [SeekableByteSource], fetched in
 * [chunkSize] pieces. Lets `BitmapFactory.decodeStream` consume embedded artwork without a buffer the
 * size of the image. Closing the stream closes the source when [closeSource] is set.
 */
internal class ByteRangeInputStream(
    private val source: SeekableByteSource,
    private val start: Long,
    private val length: Long,
    private val closeSource: Boolean = true,
    private val chunkSize: Int = 64 * 1024
) : InputStream() {
    private var position = 0L       // bytes of the range fetched so far
    private var buffer = ByteArray(0)
    private var bufferPos = 0

    override fun read(): Int {
        if (!fill()) return -1
        return buffer[bufferPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, buffer.size - bufferPos)
        System.arraycopy(buffer, bufferPos, b, off, n)
        bufferPos += n
        return n
    }

    override fun available(): Int =
        minOf((buffer.size - bufferPos) + (length - position), Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        if (closeSource) source.close()
    }

    /** Ensures unread bytes are buffered; false at the end of the range. */
    private fun fill(): Boolean {
        if (bufferPos < buffer.size) return true
        val remaining = length - position
        if (remaining <= 0) return false
        val bytes = source.readAt(start + position, minOf(remaining, chunkSize.toLong()).toInt())
            ?: throw IOException("read failed at offset ${start + position}")
        if (bytes.isEmpty()) return false
        buffer = bytes
        bufferPos = 0
        position += bytes.size
        return true
    }
}

/** Exactly [length] bytes at [offset], or null if the source can't provide the full range. */
internal fun readExactly(source: SeekableByteSource, offset: Long, length: Int): ByteArray? {
    if (length <= 0) return null
    val bytes = source.readAt(offset, length) ?: return null
    return if (bytes.size == length) bytes else null
}

// --- primitive readers shared by the parsers ---

internal fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

internal fun beU16(data: ByteArray, offset: Int): Int =
    if (offset + 2 <= data.size) (u8(data, offset) shl 8) or u8(data, offset + 1) else 0

internal fun beU32(data: ByteArray, offset: Int): Long =
    if (offset + 4 <= data.size)
        (u8(data, offset).toLong() shl 24) or (u8(data, offset + 1).toLong() shl 16) or
            (u8(data, offset + 2).toLong() shl 8) or u8(data, offset + 3).toLong()
    else 0L

internal fun beU64(data: ByteArray, offset: Int): Long {
    if (offset + 8 > data.size) return 0L
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or u8(data, offset + i).toLong()
    return value
}

/** 28-bit ID3v2 synchsafe integer (7 bits per byte, top bit always clear). */
internal fun synchsafe(data: ByteArray, offset: Int): Int =
    if (offset + 4 <= data.size)
        (u8(data, offset) and 0x7F shl 21) or (u8(data, offset + 1) and 0x7F shl 14) or
            (u8(data, offset + 2) and 0x7F shl 7) or (u8(data, offset + 3) and 0x7F)
    else 0

/** Four-character box/frame type at [offset], or "" when out of bounds. */
internal fun type4(data: ByteArray, offset: Int): String =
    if (offset + 4 <= data.size) String(data, offset, 4, Charsets.ISO_8859_1) else ""
