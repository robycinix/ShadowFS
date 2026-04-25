package com.shadowfs.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicInteger

class ShadowForegroundService : Service() {

    companion object {
        /** Flag aggiornato in onCreate/onDestroy — usato da MainActivity per sapere se il servizio è attivo.
         *  getRunningServices() è deprecated e inutilizzabile su Android 8+. */
        @Volatile
        var isRunning: Boolean = false
    }

    private val CHANNEL_ID = "ShadowFS_Channel"
    private val CHANNEL_TRANSFER_ID = "ShadowFS_Transfer"
    private val NOTIF_TRANSFER_ID = 50

    // Cartella radice monitorata. Letta dalle SharedPreferences così che in futuro
    // possa essere configurata dall'utente senza toccare il codice.
    private val monitoredDir: String
        get() = getSharedPreferences(ShadowClient.PREFS_NAME, MODE_PRIVATE)
            .getString("monitored_dir", "/storage/emulated/0") ?: "/storage/emulated/0"

    private lateinit var vfsManager: VfsManager
    private lateinit var hydrationManager: HydrationManager
    private val scanTimer = Timer()

    // Contatore upload attivi (per aggiornare la progress notification)
    private val activeUploads = AtomicInteger(0)

    // ----------------------------------------------------------------
    // Retry queue — file in storage interno, una relPath per riga
    // ----------------------------------------------------------------

    private val retryQueueFile get() = File(filesDir, "upload_retry_queue.txt")

    @Synchronized
    private fun addToRetryQueue(relPath: String) {
        val existing = if (retryQueueFile.exists())
            retryQueueFile.readLines().filter { it.isNotBlank() }.toMutableSet()
        else mutableSetOf()
        existing.add(relPath)
        retryQueueFile.writeText(existing.joinToString("\n"))
    }

    @Synchronized
    private fun removeFromRetryQueue(relPath: String) {
        if (!retryQueueFile.exists()) return
        val existing = retryQueueFile.readLines().filter { it.isNotBlank() && it != relPath }
        if (existing.isEmpty()) retryQueueFile.delete()
        else retryQueueFile.writeText(existing.joinToString("\n"))
    }

    @Synchronized
    private fun getRetryQueue(): List<String> =
        if (retryQueueFile.exists())
            retryQueueFile.readLines().filter { it.isNotBlank() }
        else emptyList()

    // --- REGOLE INTELLIGENTI DI GHOSTING ---

    // Cartelle da non toccare mai — lette dinamicamente da SharedPreferences
    private fun pinnedFolders(): Set<String> = ShadowClient.getPinnedFolders(this)

    // Whitelist: ghostiamo SOLO questi tipi di file (media e documenti grandi)
    private val ALLOWED_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".mp4", ".mov", ".mkv", ".pdf", ".zip")

    // Blacklist: estensioni di sistema/database da ignorare sempre
    private val IGNORED_EXTENSIONS = listOf(".apk", ".nomedia", ".db", ".ini", ".temp", ".shadow", ".reghost")

    // MediaStore crea nomi temporanei .pending-* quando un file e' nascosto con IS_PENDING.
    // Non sono sorgenti reali da offloadare.
    private val IGNORED_PREFIXES = listOf(".pending-")

    // Soglia minima: file sotto i 512 KB non vengono ghostati
    private val MIN_FILE_SIZE_BYTES = 1024 * 512L

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Reset preventivo: se il processo è sopravvissuto a un onDestroy anomalo
        // (es. kill dal sistema senza passare per onDestroy) l'executor potrebbe
        // essere in uno stato inconsistente. Ricrearlo garantisce un avvio pulito.
        ShadowClient.shutdownAndReset()

        createNotificationChannel()
        createTransferNotificationChannel()
        startForeground(1, createNotification())

        vfsManager = VfsManager()
        hydrationManager = HydrationManager(this, monitoredDir)
        hydrationManager.start()

        // Controlla la memoria ogni ora
        scanTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                checkSpaceAndOffload()
            }
        }, 60_000L, 3_600_000L) // Primo check dopo 1 minuto, poi ogni ora
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "FORCE_OFFLOAD" -> Thread { checkSpaceAndOffload(force = true) }.start()
        }
        return START_STICKY
    }

    private fun checkSpaceAndOffload(force: Boolean = false) {
        val root = File(monitoredDir)
        val usableSpace = root.usableSpace
        val totalSpace = root.totalSpace
        if (totalSpace == 0L) return
        val freePercentage = (usableSpace.toDouble() / totalSpace.toDouble()) * 100

        // --- RETRY degli upload falliti nei cicli precedenti ---
        val retryQueue = getRetryQueue()
        if (retryQueue.isNotEmpty()) {
            android.util.Log.i("ShadowFS", "Retry queue: ${retryQueue.size} file da riprovare")
            retryQueue.forEach { relPath ->
                val file = File("$monitoredDir/$relPath")
                if (!file.exists() || file.length() == 0L) {
                    removeFromRetryQueue(relPath) // già ghostato o eliminato
                    return@forEach
                }
                hydrationManager.suppressHydration(file.absolutePath)
                uploadFile(file, relPath, isRetry = true)
            }
        }

        // Avvia il ghosting se lo spazio è sotto il 15% OPPURE se forzato dall'utente
        if (force || freePercentage < 15.0) {
            val thresholdTime = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000) // 3 giorni fa

            root.walkTopDown()
                .onEnter { dir ->
                    pinnedFolders().none { pinned -> dir.absolutePath.startsWith(pinned) }
                }
                .filter { file -> file.isFile }
                .filter { file ->
                    val ext = "." + file.extension.lowercase()
                    ALLOWED_EXTENSIONS.contains(ext) &&
                    IGNORED_EXTENSIONS.none { file.name.endsWith(it) } &&
                    IGNORED_PREFIXES.none { file.name.startsWith(it) } &&
                    !File(file.parentFile, file.name + ".shadow").exists() &&
                    file.length() > MIN_FILE_SIZE_BYTES &&
                    (force || file.lastModified() < thresholdTime)
                }
                .forEach { file ->
                    val relPath = file.absolutePath.removePrefix(monitoredDir).trimStart('/')
                    hydrationManager.suppressHydration(file.absolutePath)
                    uploadFile(file, relPath, isRetry = false)
                }
        }

        // --- RE-GHOSTING: ri-ghosta i file idratati temporaneamente dopo 1 ora ---
        val now = System.currentTimeMillis()
        val oneHourMillis = 60L * 60 * 1000

        root.walkTopDown()
            .onEnter { dir -> pinnedFolders().none { p -> dir.absolutePath.startsWith(p) } }
            .filter { file -> file.isFile && file.name.endsWith(".reghost") }
            .forEach { markerFile ->
                try {
                    val hydrationTime = markerFile.readText().toLongOrNull() ?: 0L
                    if (force || (now - hydrationTime > oneHourMillis)) {
                        val originalFileName = markerFile.name.removeSuffix(".reghost")
                        val originalFile = File(markerFile.parentFile, originalFileName)

                        if (originalFile.exists() && originalFile.length() > 0) {
                            // Controlla se il file è stato modificato di recente (grace period di 1 min)
                            val recentlyModified = (now - originalFile.lastModified()) < 60_000L

                            if (!recentlyModified) {
                                val relPath = originalFile.absolutePath
                                    .removePrefix(monitoredDir).trimStart('/')

                                hydrationManager.suppressHydration(originalFile.absolutePath)
                                uploadFile(originalFile, relPath, isRetry = false) { success ->
                                    if (success) markerFile.delete()
                                }
                            } else {
                                android.util.Log.d("ShadowFS", "File $originalFileName recente — re-ghosting rimandato")
                            }
                        } else {
                            markerFile.delete() // Marker orfano, rimuovi
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ShadowFS", "Errore re-ghosting: ${e.message}")
                }
            }
    }

    // ----------------------------------------------------------------
    // Upload con progress bar e retry automatico
    // ----------------------------------------------------------------

    private fun uploadFile(
        file: File,
        relPath: String,
        isRetry: Boolean,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        if (File(file.parentFile, file.name + ".shadow").exists()) {
            android.util.Log.i("ShadowFS", "Skip upload: file già ghostato: $relPath")
            removeFromRetryQueue(relPath)
            onDone?.invoke(false)
            return
        }
        if (file.name.startsWith(".pending-")) {
            android.util.Log.i("ShadowFS", "Skip upload: file temporaneo MediaStore: $relPath")
            removeFromRetryQueue(relPath)
            onDone?.invoke(false)
            return
        }

        val fileSize = file.length()
        if (fileSize <= 0L) {
            android.util.Log.i("ShadowFS", "Skip upload: file vuoto: $relPath")
            removeFromRetryQueue(relPath)
            onDone?.invoke(false)
            return
        }
        val showProgress = fileSize > 5 * 1024 * 1024 // progress solo per file > 5 MB
        activeUploads.incrementAndGet()

        ShadowClient.upload(
            context = this,
            file = file,
            relPath = relPath,
            onProgress = if (showProgress) { transferred, total ->
                updateTransferNotification(file.name, transferred, total)
            } else null
        ) { success ->
            activeUploads.decrementAndGet()
            if (activeUploads.get() == 0) dismissTransferNotification()

            if (success) {
                vfsManager.markAsGhost(this, file)
                removeFromRetryQueue(relPath)
                if (isRetry) android.util.Log.i("ShadowFS", "✅ Retry riuscito: $relPath")
            } else {
                android.util.Log.w("ShadowFS", "Upload fallito per $relPath — aggiunto alla coda retry")
                addToRetryQueue(relPath)
                notifyRaspberryUnreachable()
            }
            onDone?.invoke(success)
        }
    }

    private fun updateTransferNotification(fileName: String, transferred: Long, total: Long) {
        val progress = if (total > 0) ((transferred * 100) / total).toInt() else 0
        val notif = NotificationCompat.Builder(this, CHANNEL_TRANSFER_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ShadowFS — Upload in corso")
            .setContentText("$fileName  ${formatSize(transferred)} / ${formatSize(total)}")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_TRANSFER_ID, notif)
    }

    private fun dismissTransferNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_TRANSFER_ID)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        else                    -> "%.0f KB".format(bytes / 1_024.0)
    }

    private var lastUnreachableNotifTime = 0L

    private fun notifyRaspberryUnreachable() {
        val now = System.currentTimeMillis()
        if (now - lastUnreachableNotifTime < 10 * 60 * 1000L) return // max 1 notifica ogni 10 min
        lastUnreachableNotifTime = now

        val notif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("ShadowFS — Raspberry irraggiungibile")
            .setContentText("Impossibile ghostare i file. Controlla che il Raspberry sia acceso e connesso.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        getSystemService(android.app.NotificationManager::class.java)
            .notify(99, notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shadow Daemon Core",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createTransferNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_TRANSFER_ID,
                "ShadowFS Trasferimenti",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("ShadowFS Attivo")
        .setContentText("Memoria ottimizzata in background")
        .setSmallIcon(android.R.drawable.sym_def_app_icon)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scanTimer.cancel()
        hydrationManager.stop()
        // Interrompe il worker thread di rete e ne crea uno nuovo pronto per il prossimo avvio.
        // Senza questo, un task in corso al momento del destroy potrebbe tenere l'executor
        // bloccato, impedendo qualsiasi operazione di rete al restart del service.
        ShadowClient.shutdownAndReset()
    }
}
