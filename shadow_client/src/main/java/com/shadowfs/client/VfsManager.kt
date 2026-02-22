package com.shadowfs.client

import java.io.File
import java.io.RandomAccessFile

class VfsManager {

    /**
     * Trasforma un file reale in un "Ghost". 
     * Il Ghost appare nella memoria e ai file manager come esistente 
     * ma occupa fisicamente 0 Bytes grazie al syscall 'truncate'.
     */
    fun markAsGhost(file: File) {
        if (!file.exists()) return

        // 1. Crea il file ".shadow" con i metadati originali
        val shadowFile = File(file.parent, file.name + ".shadow")
        shadowFile.writeText("originalSize=${file.length()}\nstatus=GHOST\nlastModified=${file.lastModified()}")

        // 2. Tronca il file originale preservando data e ora, se supportato
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val lastModifiedBeforeTruncate = file.lastModified()
                
                // Troncamente fisico a 0 Bytes
                raf.channel.truncate(0)
                
                // Opzionale: Prova a mantenere il timestamp originale (Android potrebbe sovrascriverlo)
                file.setLastModified(lastModifiedBeforeTruncate)
                
                println("File ${file.name} svuotato con successo (0 bytes occupati).")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Se fallisce (es permessi file manager Scoped Storage di Android 11+), rollback.
            shadowFile.delete()
        }
    }
}
