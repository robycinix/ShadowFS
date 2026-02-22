package com.shadowfs.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.FileObserver
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

class HydrationManager(private val context: Context, private val rootDir: String) {

    private val CHANNEL_ID = "HydrationChannel"
    private val notificationManager: NotificationManager = 
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val activeHydrations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val observers = mutableListOf<FileObserver>()

    init {
        createNotificationChannel()
    }

    fun start() {
        // Avviamo l'osservatore ricorsivo
        watchDirectoryRecursive(File(rootDir))
    }

    private fun watchDirectoryRecursive(directory: File) {
        if (!directory.exists() || !directory.isDirectory) return

        // Nessun bisogno di ascoltare le cartelle di sistema Android nascoste per idratazione
        if (directory.name.startsWith(".") || directory.absolutePath.contains("/Android/")) return

        val observer = object : FileObserver(directory.absolutePath, FileObserver.OPEN or FileObserver.CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                val file = File(directory, path)

                // Se è stata creata una nuova cartella, aggiungiamo un observer dinamicamente
                if (event == FileObserver.CREATE && file.isDirectory) {
                    watchDirectoryRecursive(file)
                    return
                }

                if (event == FileObserver.OPEN) {
                    // Intercetta solo se è un file svuotato da 0 byte con il metadato .shadow associato
                    if (file.exists() && file.isFile && file.length() == 0L && File(file.parent, file.name + ".shadow").exists()) {
                        
                        // ANTI-SPAM (Debouncer): Controlla se il file è già in fase di download
                        val now = System.currentTimeMillis()
                        val lastRequest = activeHydrations[file.absolutePath] ?: 0L
                        
                        // Ignora i mille eventi OPEN del Sistema Operativo se già in processo (Cooldown di 10 secondi)
                        if (now - lastRequest > 10000) {
                            activeHydrations[file.absolutePath] = now
                            triggerHydration(file)
                        }
                    }
                }
            }
        }
        
        observer.startWatching()
        observers.add(observer)

        // Ricorsione per le Sottocartelle già esistenti
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                watchDirectoryRecursive(child)
            }
        }
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        activeHydrations.clear()
    }

    private fun triggerHydration(file: File) {
        val notificationId = file.name.hashCode()
        showHydratingNotification(notificationId, file.name)
        
        QuicClientMock.requestChunk(file.name) { chunkBytes ->
            if (chunkBytes != null) {
                val shadowMeta = File(file.parent, file.name + ".shadow")
                try {
                    FileOutputStream(file).use { fos ->
                        fos.write(chunkBytes)
                    }
                    shadowMeta.delete() // Rimosso il metadato
                    
                    // Segna come ripristinato temporaneamente per il mietitore automatico
                    File(file.parent, file.name + ".reghost").writeText(System.currentTimeMillis().toString())
                    
                    showCompletionNotification(notificationId, file.name)
                } catch (e: Exception) {
                    showFailureNotification(notificationId, file.name)
                } finally {
                    activeHydrations.remove(file.absolutePath) // Sblocco del debouncer
                }
            } else {
                showFailureNotification(notificationId, file.name)
                activeHydrations.remove(file.absolutePath)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ShadowFS Idratazione", NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showHydratingNotification(id: Int, fileName: String) {
        val build = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ShadowFS")
            .setContentText("Scaricando '$fileName' in background...")
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        notificationManager.notify(id, build.build())
    }

    private fun showCompletionNotification(id: Int, fileName: String) {
        val build = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ShadowFS")
            .setContentText("File ripristinato e disponibile offline.")
            .setProgress(0, 0, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        notificationManager.notify(id, build.build())
    }

    private fun showFailureNotification(id: Int, fileName: String) {
        val build = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Errore ShadowFS")
            .setContentText("Magazzino irraggiungibile.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        notificationManager.notify(id, build.build())
    }
}
