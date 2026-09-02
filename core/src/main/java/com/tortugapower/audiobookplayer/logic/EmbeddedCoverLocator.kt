package com.tortugapower.audiobookplayer.logic

/** Embedded cover art located in a file: the image bytes are `[start, start + length)`. */
internal data class EmbeddedCover(val start: Long, val length: Long)

/**
 * Finds embedded cover art WITHOUT reading it: MP4 `moov/udta/meta/ilst/covr/data` and ID3v2 `APIC`.
 *
 * `MediaMetadataRetriever.embeddedPicture` hands back the whole picture as one array — the largest
 * thing in an audiobook file after the audio (29 MB in the field), and on a nearly full heap a
 * single allocation that size is fatal (it is the neighbour of ANDROID-BOOKPLAYER-17 in
 * `ImportManager.createBookItem`). Callers decode from the returned range through a stream instead.
 * Returns null whenever the bytes in the file are not the raw image (unsynchronised or compressed ID3
 * frames, unknown containers); callers then fall back to the platform reader.
 */
internal object EmbeddedCoverLocator {
    /** The APIC header (encoding, MIME, type, description) is parsed from at most this many bytes. */
    private const val MAX_APIC_HEADER = 4 * 1024
    private const val PICTURE_TYPE_FRONT_COVER = 3

    fun locate(source: SeekableByteSource, extension: String): EmbeddedCover? = try {
        if (extension in QUICKTIME_EXTENSIONS) locateMp4(source) ?: locateId3(source)
        else locateId3(source) ?: locateMp4(source)
    } catch (e: Exception) {
        null
    }

    private fun locateMp4(source: SeekableByteSource): EmbeddedCover? {
        val fileSize = source.size()
        if (fileSize <= 0) return null
        val moov = Mp4Boxes.children(source, 0L, fileSize).firstOrNull { it.type == "moov" } ?: return null
        // A file may carry several `udta`/`meta` boxes (tagging tools append their own); search them all.
        for (udta in Mp4Boxes.children(source, moov.start, moov.end).filter { it.type == "udta" }) {
            for (meta in Mp4Boxes.children(source, udta.start, udta.end).filter { it.type == "meta" }) {
                for (ilst in Mp4Boxes.fullBoxChildren(source, meta).filter { it.type == "ilst" }) {
                    for (covr in Mp4Boxes.children(source, ilst.start, ilst.end).filter { it.type == "covr" }) {
                        val data = Mp4Boxes.children(source, covr.start, covr.end).firstOrNull { it.type == "data" } ?: continue
                        // `data`: 4 bytes type indicator (13 JPEG, 14 PNG, 0 implicit) + 4 bytes locale, then the image.
                        val imageStart = data.start + 8
                        if (imageStart < data.end) return EmbeddedCover(imageStart, data.end - imageStart)
                    }
                }
            }
        }
        return null
    }

    private fun locateId3(source: SeekableByteSource): EmbeddedCover? {
        val tag = Id3Frames.tag(source) ?: return null
        if (tag.unsynchronised) return null
        var fallback: EmbeddedCover? = null
        for (frame in Id3Frames.frames(source, tag)) {
            if (frame.id != "APIC" || !frame.isRawPayload) continue
            val head = readExactly(source, frame.payloadStart, minOf(frame.size, MAX_APIC_HEADER.toLong()).toInt()) ?: continue
            val (imageOffset, pictureType) = parseApicHeader(head) ?: continue
            if (imageOffset >= frame.size) continue
            val cover = EmbeddedCover(frame.payloadStart + imageOffset, frame.size - imageOffset)
            if (pictureType == PICTURE_TYPE_FRONT_COVER) return cover
            if (fallback == null) fallback = cover
        }
        return fallback
    }

    /**
     * APIC payload: text encoding (1), MIME type (Latin-1, NUL-terminated), picture type (1),
     * description (NUL-terminated in the frame's encoding), then the image. Returns the image offset
     * within the payload and the picture type.
     */
    private fun parseApicHeader(head: ByteArray): Pair<Int, Int>? {
        if (head.size < 4) return null
        val encoding = u8(head, 0)
        var cursor = 1
        while (cursor < head.size && head[cursor].toInt() != 0) cursor++            // MIME type
        if (cursor >= head.size) return null
        cursor++                                                                        // its terminator
        if (cursor >= head.size) return null
        val pictureType = u8(head, cursor)
        cursor++
        val utf16 = encoding == 1 || encoding == 2                                      // 2-byte units, double NUL
        if (utf16) {
            while (cursor + 1 < head.size && !(head[cursor].toInt() == 0 && head[cursor + 1].toInt() == 0)) cursor += 2
            if (cursor + 1 >= head.size) return null
            cursor += 2
        } else {
            while (cursor < head.size && head[cursor].toInt() != 0) cursor++
            if (cursor >= head.size) return null
            cursor++
        }
        return cursor to pictureType
    }
}
