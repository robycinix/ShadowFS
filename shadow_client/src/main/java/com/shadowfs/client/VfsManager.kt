package com.shadowfs.client

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class VfsManager {

    private val THUMBNAIL_SIZE = 400   // px lato massimo
    private val THUMBNAIL_QUALITY = 75 // qualità JPEG

    companion object {
        private const val TAG = "VfsManager"

        /**
         * Imposta IS_PENDING nel MediaStore per [file].
         *
         * IS_PENDING = true  → il file è INVISIBILE a Gallery e Google Photos.
         *                      Il file fisico resta su disco intatto:
         *                      FileObserver funziona ancora, ShadowFS lo vede.
         * IS_PENDING = false → il file torna visibile normalmente (dopo hydration).
         *
         * No-op su Android < 10 (API 29): quei dispositivi non hanno IS_PENDING.
         */
        fun setIsPending(context: Context, file: File, pending: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val uri = findMediaStoreUri(context, file) ?: run {
                Log.d(TAG, "IS_PENDING: URI MediaStore non trovato per ${file.name} — skip")
                return
            }
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
            }
            try {
                context.contentResolver.update(uri, cv, null, null)
                Log.d(TAG, "IS_PENDING=${if (pending) 1 else 0} → ${file.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Impossibile aggiornare IS_PENDING per ${file.name}: ${e.message}")
            }
        }

        /**
         * Cerca l'URI MediaStore corrispondente al percorso assoluto di [file].
         * Prova prima il bucket immagini, poi video, poi il generico Files.
         * Ritorna null se il file non è ancora indicizzato da MediaStore.
         */
        private fun findMediaStoreUri(context: Context, file: File): Uri? {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection  = "${MediaStore.MediaColumns.DATA} = ?"
            val args       = arrayOf(file.absolutePath)

            val collections = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Files.getContentUri("external")
            )
            for (collection in collections) {
                try {
                    context.contentResolver
                        .query(collection, projection, selection, args, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(0)
                                return ContentUris.withAppendedId(collection, id)
                            }
                        }
                } catch (_: Exception) {}
            }
            return null
        }
    }

    /**
     * Trasforma un file reale in un "Ghost":
     *  1. Genera il thumbnail JPEG (immagini/video) o tronca a 0 byte (altri tipi).
     *  2. Scrive il file .shadow con i metadati originali.
     *  3. Sostituisce il contenuto del file originale.
     *  4. Preserva il timestamp di ultima modifica.
     *  5. Imposta IS_PENDING=1 su MediaStore → il file sparisce da Gallery
     *     e Google Photos finché non viene idratato. Il file fisico rimane
     *     su disco, quindi FileObserver continua a funzionare correttamente.
     */
    /**
     * [checksumHex]: SHA-256 del contenuto caricato sul Raspberry. Salvato nel marker
     * .shadow per riconoscere i ghost "ripristinati" da app cloud (Google/Amazon
     * Photos): se il contenuto ripristinato è identico, si ri-ghosta senza upload.
     * [restoredCount]: quante volte un'app esterna ha ripristinato questo file —
     * oltre la soglia il file viene marcato "conteso" e non più ghostato.
     */
    fun markAsGhost(context: Context, file: File, checksumHex: String? = null, restoredCount: Int = 0) {
        if (!file.exists()) return

        val originalSize = file.length()
        val lastModified = file.lastModified()

        // 1. Nascondi PRIMA da Gallery e Google Photos tramite IS_PENDING.
        //    In questo modo Google Photos non vede mai il thumbnail durante l'overwrite.
        //    Se il file non è ancora indicizzato nel MediaStore, è un no-op (non c'è nulla da nascondere).
        setIsPending(context, file, pending = true)

        // 2. Genera il thumbnail dall'originale (ancora intatto)
        val thumbnailBytes = generateThumbnail(file)

        // 3. Crea il file .shadow con i metadati.
        // DENTRO un try: prima questa scrittura era fuori e su errore (disco
        // pieno — lo scenario tipico dell'app!) l'eccezione propagava senza
        // rollback, lasciando il file con IS_PENDING=1 → invisibile in
        // Galleria/Google Photos a tempo indeterminato.
        val shadowFile = File(file.parent, file.name + ".shadow")
        try {
            shadowFile.writeText(buildString {
                append("originalSize=$originalSize\n")
                append("status=GHOST\n")
                append("lastModified=$lastModified\n")
                append("hasThumbnail=${thumbnailBytes != null}\n")
                if (checksumHex != null) append("checksum=$checksumHex\n")
                append("restoredCount=$restoredCount")
            })
        } catch (e: Exception) {
            Log.e(TAG, "Errore scrittura marker per ${file.name}: ${e.message}")
            shadowFile.delete() // eventuale marker parziale
            setIsPending(context, file, pending = false) // file intatto: torna visibile
            return
        }

        // 4. Sostituisci il contenuto con il thumbnail oppure tronca a 0.
        // ATTENZIONE: il costruttore di FileOutputStream TRONCA il file — da
        // quel momento il contenuto locale non esiste più. La strategia di
        // rollback dipende da quando avviene l'errore:
        //  - PRIMA del troncamento → file intatto: via marker e IS_PENDING.
        //  - DOPO il troncamento  → contenuto perso localmente ma integro sul
        //    Raspberry: TENERE marker e IS_PENDING, così il file resta
        //    idratabile e la versione corrotta non appare in galleria.
        var contentTruncated = false
        try {
            val fos = FileOutputStream(file)
            contentTruncated = true
            fos.use { if (thumbnailBytes != null) it.write(thumbnailBytes) }
            if (thumbnailBytes != null) {
                Log.i(TAG, "Ghost con thumbnail: ${file.name} (thumbnail ${thumbnailBytes.size} bytes, originale $originalSize bytes)")
            } else {
                Log.i(TAG, "Ghost (0 byte, no thumbnail): ${file.name}")
            }
            file.setLastModified(lastModified)
        } catch (e: Exception) {
            if (!contentTruncated) {
                Log.e(TAG, "Errore ghosting ${file.name} (contenuto intatto): ${e.message}")
                shadowFile.delete() // rollback marker
                setIsPending(context, file, pending = false) // rollback IS_PENDING
            } else {
                Log.e(TAG, "Errore ghosting ${file.name} DOPO il troncamento: ${e.message} " +
                        "— marker mantenuto: il file resta idratabile dal Raspberry")
                // niente rollback: marker + IS_PENDING permettono il recupero
            }
            return
        }
        // IS_PENDING rimane true: file nascosto finché non viene idratato
    }

    private fun generateThumbnail(file: File): ByteArray? {
        val ext = file.extension.lowercase()
        return try {
            val bitmap: Bitmap? = when (ext) {
                "jpg", "jpeg", "png" -> decodeScaledBitmap(file)
                "mp4", "mov", "mkv"  -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        retriever.release()
                    }
                }
                else -> null
            }

            if (bitmap == null) return null

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
