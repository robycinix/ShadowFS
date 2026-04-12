package com.shadowfs.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * HydrationManager — monitora gli accessi ai file "ghost" (0 byte con .shadow marker)
 * e avvia il download automatico dal Raspberry Pi quando l'utente tenta di aprirli.
 *
 * Usa FileObserver (API nativa Android) per intercettare gli eventi OPEN sul filesystem.
 * Quando rileva un accesso ad un file ghost, chiama ShadowClient.download() con il
 * percorso relativo corretto.
 */
class HydrationManager(private val context: Context, private val rootDir: String) {

    private val TAG = "HydrationManager"
    private val CHANNEL_ID = "HydrationChannel"

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Mappa anti-spam: evita di inviare richieste duplicate per lo stesso file
    private val activeHydrations = ConcurrentHashMap<String, Long>()

    // File in fase di ghosting: l'idratazione è soppressa finché non scade il timer
    private val suppressedFiles = ConcurrentHashMap.newKeySet<String>()

    /** Sopprime l'idratazione per [path] per [durationMs] ms (chiamare prima di markAsGhost). */
    fun suppressHydration(path: String, durationMs: Long = 60_000L) {
        suppressedFiles.add(path)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            suppressedFiles.remove(path)
        }, durationMs)
    }

    private val observers = mutableListOf<FileObserver>()

    init {
        createNotificationChannel()
    }

    fun start() {
        Log.i(TAG, "Avvio osservatori su: $rootDir")
        watchDirectoryRecursive(File(rootDir))
    }

    private fun watchDirectoryRecursive(directory: File) {
        if (!directory.exists() || !directory.isDirectory) return

        // Salta cartelle di sistema e nascoste
        if (directory.name.startsWith(".") || directory.absolutePath.contains("/Android/")) return

        val observer = object : FileObserver(directory.absolutePath, OPEN or CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                val file = File(directory, path)

                // Nuova cartella creata dinamicamente → aggiungi osservatore
                if (event == CREATE && file.isDirectory) {
                    watchDirectoryRecursive(file)
                    return
                }

                if (event == OPEN) {
                    // Controlla se è un file ghost: ha il marker .shadow
                    // (il file può avere 0 byte oppure contenere un thumbnail — entrambi sono ghost)
                    val shadowMarker = File(file.parent, file.name + ".shadow")
                    val originalSize = readOriginalSize(shadowMarker)
                    if (file.exists() && file.isFile && shadowMarker.exists() &&
                        file.length() < originalSize && !suppressedFiles.contains(file.absolutePath)) {

                        val now = System.currentTimeMillis()
                        val lastRequest = activeHydrations[file.absolutePath] ?: 0L

                        // Debouncer: ignora eventi ripetuti entro 10 secondi
                        if (now - lastRequest > 10_000) {
                            activeHydrations[file.absolutePath] = now
                            triggerHydration(file)
                        }
                    }
                }
            }
        }

        observer.startWatching()
        observers.add(observer)

        // Ricursione per sottocartelle già esistenti
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) watchDirectoryRecursive(child)
        }
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        activeHydrations.clear()
        Log.i(TAG, "Osservatori fermati.")
    }

    private fun triggerHydration(file: File) {
        val notificationId = file.absolutePath.hashCode()
        showHydratingNotification(notificationId, file.name)

        Log.i(TAG, "Idratazione avviata per: ${file.absolutePath}")

        // Calcola il percorso relativo rispetto alla root monitorata
        val relPath = file.absolutePath.removePrefix(rootDir).trimStart('/')

        // CONNESSIONE REALE al Raspberry Pi tramite TLS+mTLS
        ShadowClient.download(context, relPath, file) { success ->
            if (success) {
                // Rimuovi il marker .shadow (file idratato con successo)
                val shadowMeta = File(file.parent, file.name + ".shadow")
                shadowMeta.delete()

                // Crea il marker .reghost: il daemon ri-ghosterà questo file tra 1 ora
                File(file.parent, file.name + ".reghost")
                    .writeText(System.currentTimeMillis().toString())

                Log.i(TAG, "✅ Idratazione completata: ${file.name} (${file.length()} bytes)")
                showCompletionNotification(notificationId, file.name)
            } else {
                Log.e(TAG, "❌ Idratazione fallita: ${file.name} — Raspberry non raggiungibile?")
                showFailureNotification(notificationId, file.name)
            }
            activeHydrations.remove(file.absolutePath) // Sblocca il debouncer
        }
    }

    /**
     * Legge la dimensione originale del file dal marker .shadow.
     * Ritorna Long.MAX_VALUE se il marker non esiste o non è leggibile
     * (così la condizione file.length() < originalSize non scatta mai per errore).
     */
    private fun readOriginalSize(shadowFile: File): Long {
        if (!shadowFile.exists()) return Long.MAX_VALUE
        return try {
            shadowFile.readLines()
                .firstOrNull { it.startsWith("originalSize=") }
                ?.removePrefix("originalSize=")
                ?.toLong()
                ?: Long.MAX_VALUE
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ShadowFS Idratazione",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showHydratingNotification(id: Int, fileName: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ShadowFS")
            .setContentText("Scaricando '$fileName' dal Raspberry Pi...")
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(id, notif)
    }

    private fun showCompletionNotification(id: Int, fileName: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ShadowFS")
            .setContentText("'$fileName' ripristinato e disponibile.")
            .setProgress(0, 0, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(id, notif)
    }

    private fun showFailureNotification(id: Int, fileName: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Errore ShadowFS")
            .setContentText("Impossibile recuperare '$fileName' — Raspberry irraggiungibile.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(id, notif)
    }
}
