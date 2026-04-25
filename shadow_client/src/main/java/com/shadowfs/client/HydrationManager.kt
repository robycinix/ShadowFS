package com.shadowfs.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.Timer
import java.util.TimerTask
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

    // La Galleria apre spesso i file in background per anteprime/prefetch.
    // Il primo OPEN su un ghost quindi non idrata: registra solo l'intenzione.
    // Un secondo OPEN ravvicinato sullo stesso file e' un segnale piu' forte
    // di apertura esplicita da parte dell'utente.
    private val pendingHydrationOpens = ConcurrentHashMap<String, Long>()
    private val HYDRATION_CONFIRM_MIN_DELAY_MS = 1_000L
    private val HYDRATION_CONFIRM_WINDOW_MS = 30_000L
    private val AUTO_HYDRATION_PREF = "auto_hydration_enabled"

    // File in fase di ghosting: l'idratazione è soppressa finché non scade il timer.
    // Usiamo Timer (non Handler) così il cleanup avviene anche se il service viene ricreato.
    private val suppressedFiles = ConcurrentHashMap.newKeySet<String>()
    private val suppressionTimers = ConcurrentHashMap<String, Timer>()

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** Ritorna true solo se lo schermo è acceso e il telefono è in uso attivo. */
    private fun isScreenOn(): Boolean = powerManager.isInteractive

    // Rate limiter: traccia gli accessi recenti a file DISTINTI
    // Se > 3 file diversi in 3 secondi → prefetching galleria → sopprimi
    private val recentAccessedFiles = mutableListOf<Pair<Long, String>>() // timestamp + path
    private val RATE_WINDOW_MS = 3_000L
    private val RATE_THRESHOLD = 3
    @Volatile private var suppressUntil = 0L

    private fun isRateLimited(filePath: String): Boolean {
        val now = System.currentTimeMillis()

        // Se siamo in periodo di soppressione attivo, aspetta
        if (now < suppressUntil) return true

        synchronized(recentAccessedFiles) {
            // Rimuovi accessi più vecchi della finestra
            recentAccessedFiles.removeAll { it.first < now - RATE_WINDOW_MS }

            // Aggiungi solo se file non già presente nella finestra
            if (recentAccessedFiles.none { it.second == filePath }) {
                recentAccessedFiles.add(Pair(now, filePath))
            }

            // Se troppi file distinti in poco tempo → prefetching → sopprimi per 30s
            if (recentAccessedFiles.size > RATE_THRESHOLD) {
                suppressUntil = now + 30_000L
                recentAccessedFiles.clear()
                Log.d(TAG, "Prefetching rilevato — idratazione soppressa per 30s")
                return true
            }
        }
        return false
    }

    /** Sopprime l'idratazione per [path] per [durationMs] ms (chiamare prima di markAsGhost).
     *  Usa Timer invece di Handler: il cleanup avviene anche se il thread principale è morto. */
    fun suppressHydration(path: String, durationMs: Long = 60_000L) {
        suppressedFiles.add(path)
        // Cancella eventuale timer precedente per lo stesso path
        suppressionTimers.remove(path)?.cancel()
        val t = Timer(true) // daemon timer: non blocca la JVM
        suppressionTimers[path] = t
        t.schedule(object : TimerTask() {
            override fun run() {
                suppressedFiles.remove(path)
                suppressionTimers.remove(path)
            }
        }, durationMs)
    }

    // CopyOnWriteArrayList: onEvent(CREATE) può aggiungere osservatori da thread FileObserver
    // mentre stop() itera la lista — mutableListOf causerebbe ConcurrentModificationException.
    private val observers = java.util.concurrent.CopyOnWriteArrayList<FileObserver>()

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
                    // Filtro 1: schermo spento = processo di sistema
                    if (!isScreenOn()) return

                    // Filtro 2: troppi file diversi in poco tempo = prefetching galleria
                    if (isRateLimited(file.absolutePath)) return

                    val shadowMarker = File(file.parent, file.name + ".shadow")
                    val originalSize = readOriginalSize(shadowMarker)
                    if (file.exists() && file.isFile && shadowMarker.exists() &&
                        file.length() < originalSize && !suppressedFiles.contains(file.absolutePath)) {

                        if (!isAutoHydrationEnabled()) {
                            Log.d(TAG, "Auto-idratazione disattivata, ignoro OPEN da app esterna: ${file.name}")
                            return
                        }

                        if (!isConfirmedUserOpen(file.absolutePath)) {
                            Log.d(TAG, "Ghost access armato, attendo conferma utente: ${file.name}")
                            return
                        }

                        val now = System.currentTimeMillis()
                        val lastRequest = activeHydrations[file.absolutePath] ?: 0L

                        // Debouncer: ignora eventi ripetuti entro 30 secondi
                        if (now - lastRequest > 30_000) {
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
        pendingHydrationOpens.clear()
        suppressionTimers.values.forEach { it.cancel() }
        suppressionTimers.clear()
        Log.i(TAG, "Osservatori fermati.")
    }

    private fun isAutoHydrationEnabled(): Boolean =
        context.getSharedPreferences(ShadowClient.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(AUTO_HYDRATION_PREF, false)

    private fun isConfirmedUserOpen(filePath: String): Boolean {
        val now = System.currentTimeMillis()
        val firstOpen = pendingHydrationOpens[filePath]
        if (firstOpen == null || now - firstOpen > HYDRATION_CONFIRM_WINDOW_MS) {
            pendingHydrationOpens[filePath] = now
            return false
        }
        if (now - firstOpen < HYDRATION_CONFIRM_MIN_DELAY_MS) {
            return false
        }
        pendingHydrationOpens.remove(filePath)
        return true
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

                // Rendi il file nuovamente visibile a Gallery e Google Photos.
                // IS_PENDING=0 → la foto riappare in Gallery con il contenuto completo.
                // MediaScannerConnection aggiorna dimensione e thumb nel MediaStore.
                VfsManager.setIsPending(context, file, pending = false)
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), null, null
                )

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
