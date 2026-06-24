package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

object ArtworkManager {
    
    fun compressAndSaveImage(context: Context, imageUri: Uri, destFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                val bytes = input.readBytes()
                saveProcessedBitmap(bytes, destFile)
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun extractAndSaveArtwork(audioFile: File, destFile: File): Boolean {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioFile.absolutePath)
            val picture = retriever.embeddedPicture ?: return false
            saveProcessedBitmap(picture, destFile)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            retriever.release()
        }
    }

    private fun saveProcessedBitmap(bytes: ByteArray, destFile: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            
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
            
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return false
            
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
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun downloadAndSaveArtwork(context: Context, urlString: String, destFile: File): Boolean {
        return try {
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.doInput = true
            connection.connect()
            connection.inputStream.use { input ->
                val bytes = input.readBytes()
                saveProcessedBitmap(bytes, destFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun deleteArtwork(path: String?) {
        if (path == null || path.startsWith("http")) return
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
