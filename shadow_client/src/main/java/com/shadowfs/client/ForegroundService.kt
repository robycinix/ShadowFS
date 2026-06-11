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

        // --- RICONCILIAZIONE ANTI-LOOP CLOUD ---
        // Google Photos, Amazon Photos & co. possono "ripristinare" l'originale
        // sopra un ghost (riscaricandolo dal loro cloud quando notano il contenuto
        // cambiato/mancante). Il file torna grande ma il marker .shadow resta.
        // Senza gestione si innesca una reazione a catena:
        //   ghost → l'app cloud ripristina → ghost → ripristina → ... (∞ traffico)
        // Strategia:
        //   1° ripristino: re-ghost "economico" — se il contenuto è identico a
        //      quello già sul Raspberry (checksum nel marker), NESSUN upload;
        //   2° ripristino: file CONTESO → escluso per sempre dal ghosting +
        //      notifica all'utente di disattivare il backup cloud per la cartella.
        reconcileRestoredGhosts(root)

        // Avvia il ghosting se lo spazio è sotto il 15% OPPURE se forzato dall'utente
        if (force || freePercentage < 15.0) {
            val thresholdTime = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000) // 3 giorni fa
            val contested = ShadowClient.getContestedFiles(this)

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
                    !contested.contains(file.absolutePath) &&
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
    // Riconciliazione ghost ripristinati da app cloud
    // ----------------------------------------------------------------

    /** Soglia di ripristini esterni oltre la quale il file è considerato conteso. */
    private val MAX_EXTERNAL_RESTORES = 2

    private fun reconcileRestoredGhosts(root: File) {
        val contested = ShadowClient.getContestedFiles(this)

        root.walkTopDown()
            .onEnter { dir -> pinnedFolders().none { p -> dir.absolutePath.startsWith(p) } }
            .filter { it.isFile && it.name.endsWith(".shadow") }
            .forEach { marker ->
                try {
                    val original = File(marker.parentFile, marker.name.removeSuffix(".shadow"))
                    val meta = readShadowMeta(marker)
                    val origSize = meta["originalSize"]?.toLongOrNull() ?: return@forEach
                    if (origSize <= 0L || !original.exists()) return@forEach
                    // È ancora un ghost (thumbnail/0 byte)? Nulla da fare.
                    if (original.length() < origSize) return@forEach
                    if (contested.contains(original.absolutePath)) return@forEach

                    val restoredCount = (meta["restoredCount"]?.toIntOrNull() ?: 0) + 1
                    val relPath = original.absolutePath.removePrefix(monitoredDir).trimStart('/')

                    if (restoredCount >= MAX_EXTERNAL_RESTORES) {
                        // Un'app esterna insiste a ripristinare questo file:
                        // continuare a ghostarlo significherebbe loop infinito.
                        ShadowClient.addContestedFile(this, original.absolutePath)
                        marker.delete()
                        File(original.parentFile, original.name + ".reghost").delete()
                        notifyContestedFile(original.name)
                        android.util.Log.w("ShadowFS",
                            "⚠️ File conteso da un'app cloud — escluso dal ghosting: $relPath")
                        return@forEach
                    }

                    android.util.Log.i("ShadowFS",
                        "Ghost ripristinato da app esterna ($restoredCount° volta): $relPath")

                    val storedChecksum = meta["checksum"]
                    val currentSha = ShadowClient.sha256Bytes(original)
                    val currentHex = currentSha.joinToString("") { "%02x".format(it) }

                    hydrationManager.suppressHydration(original.absolutePath)
                    if (storedChecksum != null && storedChecksum.equals(currentHex, ignoreCase = true)) {
                        // Contenuto identico a quello già sul Raspberry: re-ghost
                        // locale immediato, ZERO traffico di rete.
                        vfsManager.markAsGhost(this, original, currentHex, restoredCount)
                        android.util.Log.i("ShadowFS", "Re-ghost senza upload (checksum identico): $relPath")
                    } else {
                        // Contenuto diverso (o marker legacy senza checksum):
                        // serve un upload aggiornato prima di ri-ghostare.
                        uploadFile(original, relPath, isRetry = false,
                            restoredCount = restoredCount, precomputedSha = currentSha)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ShadowFS", "Errore riconciliazione ghost: ${e.message}")
                }
            }
    }

    private fun readShadowMeta(marker: File): Map<String, String> = try {
        marker.readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
        }.toMap()
    } catch (e: Exception) { emptyMap() }

    private fun notifyContestedFile(fileName: String) {
        val text = getString(R.string.notif_contested_text, fileName)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.notif_contested_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(("contested:$fileName").hashCode(), notif)
    }

    // ----------------------------------------------------------------
    // Upload con progress bar e retry automatico
    // ----------------------------------------------------------------

    private fun uploadFile(
        file: File,
        relPath: String,
        isRetry: Boolean,
        restoredCount: Int = 0,
        precomputedSha: ByteArray? = null,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        // restoredCount > 0 = riconciliazione di un ghost ripristinato da un'app
        // cloud: il marker .shadow esiste ancora ma il file è tornato completo,
        // quindi il controllo "già ghostato" va saltato.
        if (restoredCount == 0 && File(file.parentFile, file.name + ".shadow").exists()) {
            android.util.Log.i("ShadowFS", "Skip upload: file già ghostato: $relPath")
            removeFromRetryQueue(relPath)
            // true: il file è già in stato ghost — i chiamanti (es. re-ghosting)
            // possono ripulire i loro marker invece di riprovare per sempre.
            onDone?.invoke(true)
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

        // Snapshot pre-upload: se il file cambia DURANTE l'upload (es. foto modificata
        // dall'utente), sul server è finita la versione vecchia. Ghostare adesso
        // distruggerebbe la versione nuova → in quel caso si salta il ghosting e si
        // lascia il file in coda per un upload aggiornato al prossimo ciclo.
        val sizeBeforeUpload = fileSize
        val mtimeBeforeUpload = file.lastModified()

        ShadowClient.upload(
            context = this,
            file = file,
            relPath = relPath,
            onProgress = if (showProgress) { transferred, total ->
                updateTransferNotification(file.name, transferred, total)
            } else null,
            precomputedSha = precomputedSha
        ) { success, checksumHex ->
            activeUploads.decrementAndGet()
            if (activeUploads.get() == 0) dismissTransferNotification()

            if (success) {
                if (file.length() != sizeBeforeUpload || file.lastModified() != mtimeBeforeUpload) {
                    android.util.Log.w("ShadowFS",
                        "File modificato durante l'upload — ghosting annullato, riproverò: $relPath")
                    addToRetryQueue(relPath)
                    onDone?.invoke(false)
                    return@upload
                }
                vfsManager.markAsGhost(this, file, checksumHex, restoredCount)
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
            .setContentTitle(getString(R.string.notif_upload_title))
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
            .setContentTitle(getString(R.string.notif_unreachable_title))
            .setContentText(getString(R.string.notif_unreachable_text))
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
                getString(R.string.service_channel_name),
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
                getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notif_service_title))
        .setContentText(getString(R.string.notif_service_text))
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
