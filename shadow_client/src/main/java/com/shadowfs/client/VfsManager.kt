package com.shadowfs.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class VfsManager {

    private val TAG = "VfsManager"
    private val THUMBNAIL_SIZE = 400   // px lato massimo
    private val THUMBNAIL_QUALITY = 75 // qualità JPEG

    /**
     * Trasforma un file reale in un "Ghost".
     * Per immagini e video: scrive un thumbnail JPEG nel file originale
     * così la galleria mostra un'anteprima riconoscibile.
     * Per altri tipi (PDF, ZIP): tronca a 0 byte.
     * In entrambi i casi crea il file .shadow con i metadati originali.
     */
    fun markAsGhost(file: File) {
        if (!file.exists()) return

        val originalSize = file.length()
        val lastModified = file.lastModified()

        // 1. Genera il thumbnail prima di toccare il file originale
        val thumbnailBytes = generateThumbnail(file)

        // 2. Crea il file .shadow con i metadati
        val shadowFile = File(file.parent, file.name + ".shadow")
        shadowFile.writeText(
            "originalSize=$originalSize\nstatus=GHOST\nlastModified=$lastModified\nhasThumbnail=${thumbnailBytes != null}"
        )

        // 3. Sostituisci il contenuto con il thumbnail oppure tronca a 0
        try {
            if (thumbnailBytes != null) {
                FileOutputStream(file).use { it.write(thumbnailBytes) }
                Log.i(TAG, "Ghost con thumbnail: ${file.name} (thumbnail ${thumbnailBytes.size} bytes, originale $originalSize bytes)")
            } else {
                FileOutputStream(file).use { /* apre e chiude → 0 byte */ }
                Log.i(TAG, "Ghost (0 byte, no thumbnail): ${file.name}")
            }
            file.setLastModified(lastModified)
        } catch (e: Exception) {
            Log.e(TAG, "Errore ghosting ${file.name}: ${e.message}")
            shadowFile.delete() // rollback: rimuovi il marker se qualcosa va storto
        }
    }

    private fun generateThumbnail(file: File): ByteArray? {
        val ext = file.extension.lowercase()
        return try {
            val bitmap: Bitmap? = when (ext) {
                "jpg", "jpeg", "png" -> decodeScaledBitmap(file)
                "mp4", "mov", "mkv"  -> {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(
                        file.absolutePath,
                        MediaStore.Images.Thumbnails.MINI_KIND
                    )
                }
                else -> null
            }

            if (bitmap == null) return null

            // Ritaglia/ridimensiona al quadrato THUMBNAIL_SIZE x THUMBNAIL_SIZE
            val scaled = ThumbnailUtils.extractThumbnail(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
            if (scaled !== bitmap) bitmap.recycle()

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
            scaled.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Errore generazione thumbnail per ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * Decodifica un'immagine campionandola in modo da non saturare la RAM.
     * Carica solo la risoluzione necessaria per un thumbnail THUMBNAIL_SIZE x THUMBNAIL_SIZE.
     */
    private fun decodeScaledBitmap(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)

        opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight)
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val maxDim = maxOf(width, height)
        while (maxDim / (sampleSize * 2) >= THUMBNAIL_SIZE) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
