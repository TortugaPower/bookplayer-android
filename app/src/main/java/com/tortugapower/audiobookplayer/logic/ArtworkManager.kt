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
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                val bytes = input.readBytes()
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
            } ?: false
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
