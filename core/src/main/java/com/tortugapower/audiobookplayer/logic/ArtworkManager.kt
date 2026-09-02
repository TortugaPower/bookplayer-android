package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ArtworkManager {
    /** Remote covers above this are skipped rather than fetched (see [saveEmbeddedArtwork]). */
    private const val MAX_REMOTE_COVER_BYTES = 8 * 1024 * 1024

    /** Outcome of resolving a file's embedded cover into the artwork store. */
    sealed interface EmbeddedArtwork {
        /** The destination file was written. */
        data object Saved : EmbeddedArtwork
        /** The container was read and holds no usable picture — definitive, safe to remember. */
        data object None : EmbeddedArtwork
        /**
         * Nothing was written, but the picture may well exist: I/O error, timeout, a heap too small for
         * the platform reader, or a remote cover skipped for size. Transient — try again later (e.g. once
         * the file is local); never remember it as "no art".
         */
        data object Failed : EmbeddedArtwork
    }

    fun compressAndSaveImage(context: Context, imageUri: Uri, destFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                val bytes = input.readBytes()
                saveProcessedBitmap(bytes, destFile)
            } ?: false
        } catch (e: Exception) {
            android.util.Log.e("ArtworkManager", "Error compressing and saving image: ${e.message}")
            false
        }
    }

    /**
     * Save a local file's embedded cover into [destFile]. The cover is located as a byte range and
     * decoded straight from it ([EmbeddedCoverLocator] + [ByteRangeInputStream]): a cover is the largest
     * thing in an audiobook file after the audio (29 MB in the field), and `MediaMetadataRetriever.embeddedPicture`
     * hands it back as ONE allocation — fatal on a nearly full heap, right before chapter extraction in
     * `ImportManager.createBookItem` (Sentry ANDROID-BOOKPLAYER-17's neighbour). Containers the locator
     * doesn't parse fall back to the platform reader.
     */
    fun extractAndSaveArtwork(audioFile: File, destFile: File): Boolean =
        saveEmbeddedArtwork(audioFile, destFile) == EmbeddedArtwork.Saved

    /** [extractAndSaveArtwork] with the outcome kept apart: callers that remember "no art" need [EmbeddedArtwork.None] vs [EmbeddedArtwork.Failed]. */
    fun saveEmbeddedArtwork(audioFile: File, destFile: File): EmbeddedArtwork {
        val extension = audioFile.extension.lowercase()
        val located = try {
            FileByteSource(audioFile).use { EmbeddedCoverLocator.locate(it, extension) }
        } catch (e: Exception) {
            null
        }
        if (located != null) {
            return decodeAndSave(destFile) { ByteRangeInputStream(FileByteSource(audioFile), located.start, located.length) }.toEmbeddedArtwork()
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioFile.absolutePath)
            val picture = retriever.embeddedPicture ?: return EmbeddedArtwork.None
            decodeAndSave(destFile) { ByteArrayInputStream(picture) }.toEmbeddedArtwork()
        } catch (e: Exception) {
            android.util.Log.e("ArtworkManager", "Error extracting and saving artwork: ${e.message}")
            EmbeddedArtwork.Failed
        } catch (e: OutOfMemoryError) {
            // The platform reader materializes the whole picture; losing the cover beats losing the import.
            android.util.Log.e("ArtworkManager", "Embedded picture too large to read on this heap: ${e.message}")
            EmbeddedArtwork.Failed
        } finally {
            retriever.release()
        }
    }

    /**
     * Extract embedded artwork from a REMOTE audio URL (streaming just the metadata via range requests,
     * with optional auth [headers]) and save it. Used by Android Auto browse to show covers for cloud
     * items that aren't downloaded. Returns false if there's no embedded art or the stream fails.
     */
    fun extractAndSaveArtworkFromUri(uri: String, headers: Map<String, String>?, destFile: File): Boolean =
        saveEmbeddedArtwork(uri, headers, destFile) == EmbeddedArtwork.Saved

    /** Remote counterpart of [saveEmbeddedArtwork]: the same locate-then-read approach over HTTP `Range` requests. */
    fun saveEmbeddedArtwork(uri: String, headers: Map<String, String>?, destFile: File): EmbeddedArtwork {
        // Capped: a browse thumbnail is not worth pulling a 30 MB cover over the network. The skip is
        // [EmbeddedArtwork.Failed], not None — the cover exists and the local path will extract it once
        // the book is downloaded. Servers that ignore `Range` make the locator return null, which lands
        // in the platform-reader fallback below (the previous behaviour).
        val extension = Uri.parse(uri).lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
        var oversized = false
        val bytes = try {
            HttpRangeByteSource(uri, headers).use { source ->
                EmbeddedCoverLocator.locate(source, extension)?.let { cover ->
                    if (cover.length > MAX_REMOTE_COVER_BYTES) {
                        android.util.Log.w("ArtworkManager", "Skipping ${cover.length}-byte remote cover (cap $MAX_REMOTE_COVER_BYTES)")
                        oversized = true
                        null
                    } else {
                        readExactly(source, cover.start, cover.length.toInt())
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
        if (oversized) return EmbeddedArtwork.Failed
        if (bytes != null) return decodeAndSave(destFile) { ByteArrayInputStream(bytes) }.toEmbeddedArtwork()

        val retriever = MediaMetadataRetriever()
        return try {
            if (headers != null) retriever.setDataSource(uri, headers) else retriever.setDataSource(uri)
            val picture = retriever.embeddedPicture ?: return EmbeddedArtwork.None
            decodeAndSave(destFile) { ByteArrayInputStream(picture) }.toEmbeddedArtwork()
        } catch (e: Exception) {
            android.util.Log.e("ArtworkManager", "Error extracting remote artwork: ${e.message}")
            EmbeddedArtwork.Failed
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("ArtworkManager", "Remote embedded picture too large to read on this heap: ${e.message}")
            EmbeddedArtwork.Failed
        } finally {
            retriever.release()
        }
    }

    private fun saveProcessedBitmap(bytes: ByteArray, destFile: File): Boolean =
        decodeAndSave(destFile) { ByteArrayInputStream(bytes) } == DecodeOutcome.SAVED

    private enum class DecodeOutcome { SAVED, UNDECODABLE, OUT_OF_MEMORY }

    private fun DecodeOutcome.toEmbeddedArtwork(): EmbeddedArtwork = when (this) {
        DecodeOutcome.SAVED -> EmbeddedArtwork.Saved
        DecodeOutcome.UNDECODABLE -> EmbeddedArtwork.None   // a picture that won't decode is as good as none
        DecodeOutcome.OUT_OF_MEMORY -> EmbeddedArtwork.Failed // the picture is fine; this heap wasn't — retry later
    }

    /**
     * Two-pass decode (bounds, then sampled) from streams that [open] produces fresh for each pass, so
     * the image is never held whole in memory — only the downsampled bitmap is. Decoding can still
     * exhaust the heap (an extreme aspect ratio keeps `inSampleSize` at 1); that is reported, never thrown,
     * so an oversized picture costs the cover and not the import that asked for it.
     */
    private fun decodeAndSave(destFile: File, open: () -> InputStream): DecodeOutcome {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            open().use { BitmapFactory.decodeStream(it, null, options) }
            
            val width = options.outWidth
            val height = options.outHeight
            
            val maxSize = 512
            var inSampleSize = 1
            
            if (width > maxSize || height > maxSize) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while (halfHeight / inSampleSize >= maxSize && halfWidth / inSampleSize >= maxSize) {
                    inSampleSize *= 2
                }
            }
            
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            
            val bitmap = open().use { BitmapFactory.decodeStream(it, null, decodeOptions) } ?: return DecodeOutcome.UNDECODABLE
            
            // Final precision scaling if needed
            val finalBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
                val scale = maxSize.toFloat() / kotlin.math.max(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap, 
                    (bitmap.width * scale).toInt(), 
                    (bitmap.height * scale).toInt(), 
                    true
                )
            } else {
                bitmap
            }
            
            FileOutputStream(destFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            
            if (finalBitmap != bitmap) {
                finalBitmap.recycle()
            }
            bitmap.recycle()
            DecodeOutcome.SAVED
        } catch (e: Exception) {
            android.util.Log.e("ArtworkManager", "Error saving processed bitmap: ${e.message}")
            DecodeOutcome.UNDECODABLE
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("ArtworkManager", "Not enough heap to decode artwork: ${e.message}")
            DecodeOutcome.OUT_OF_MEMORY
        }
    }

    fun downloadAndSaveArtwork(context: Context, urlString: String, destFile: File): Boolean {
        return try {
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doInput = true
            connection.connect()
            connection.inputStream.use { input ->
                val bytes = input.readBytes()
                saveProcessedBitmap(bytes, destFile)
            }
        } catch (e: Exception) {
            android.util.Log.e("ArtworkManager", "Error downloading and saving artwork: ${e.message}")
            false
        }
    }

    fun deleteArtwork(path: String?) {
        if (path == null || path.startsWith("http")) return
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            android.util.Log.e("ArtworkManager", "Error deleting artwork: ${e.message}")
        }
    }
}
