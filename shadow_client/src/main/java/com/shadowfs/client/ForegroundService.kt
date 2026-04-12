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

class ShadowForegroundService : Service() {

    private val CHANNEL_ID = "ShadowFS_Channel"
    private val MONITORED_DIR = "/storage/emulated/0"

    private lateinit var vfsManager: VfsManager
    private lateinit var hydrationManager: HydrationManager
    private val scanTimer = Timer()

    // --- REGOLE INTELLIGENTI DI GHOSTING ---

    // Cartelle da non toccare mai (Pinned)
    private val PINNED_FOLDERS = listOf(
        "/storage/emulated/0/Download/shadowfs_certs", // Mai ghostare i certificati!
        "/storage/emulated/0/Android"                 // Cartella di sistema visibile
    )

    // Whitelist: ghostiamo SOLO questi tipi di file (media e documenti grandi)
    private val ALLOWED_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".mp4", ".mov", ".mkv", ".pdf", ".zip")

    // Blacklist: estensioni di sistema/database da ignorare sempre
    private val IGNORED_EXTENSIONS = listOf(".apk", ".nomedia", ".db", ".ini", ".temp", ".shadow", ".reghost")

    // Soglia minima: file sotto i 512 KB non vengono ghostati
    private val MIN_FILE_SIZE_BYTES = 1024 * 512L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())

        vfsManager = VfsManager()
        hydrationManager = HydrationManager(this, MONITORED_DIR)
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
        val root = File(MONITORED_DIR)
        val usableSpace = root.usableSpace
        val totalSpace = root.totalSpace
        if (totalSpace == 0L) return
        val freePercentage = (usableSpace.toDouble() / totalSpace.toDouble()) * 100

        // Avvia il ghosting se lo spazio è sotto il 15% OPPURE se forzato dall'utente
        if (force || freePercentage < 15.0) {
            val thresholdTime = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000) // 3 giorni fa

            root.walkTopDown()
                .onEnter { dir ->
                    PINNED_FOLDERS.none { pinned -> dir.absolutePath.startsWith(pinned) }
                }
                .filter { file -> file.isFile }
                .filter { file ->
                    val ext = "." + file.extension.lowercase()
                    ALLOWED_EXTENSIONS.contains(ext) &&
                    IGNORED_EXTENSIONS.none { file.name.endsWith(it) } &&
                    file.length() > MIN_FILE_SIZE_BYTES &&
                    (force || file.lastModified() < thresholdTime)
                }
                .forEach { file ->
                    // Calcola il percorso relativo rispetto alla root monitorata
                    val relPath = file.absolutePath.removePrefix(MONITORED_DIR).trimStart('/')

                    // Sopprime l'idratazione durante il ghosting (evita loop upload→ghost→hydrate)
                    hydrationManager.suppressHydration(file.absolutePath)

                    // Invia il file al Raspberry Pi via TLS mTLS REALE
                    ShadowClient.upload(this, file, relPath) { success ->
                        if (success) {
                            vfsManager.markAsGhost(file)
                        } else {
                            android.util.Log.w("ShadowFS", "Upload fallito per $relPath — file non ghostato")
                            notifyRaspberryUnreachable()
                        }
                    }
                }
        }

        // --- RE-GHOSTING: ri-ghosta i file idratati temporaneamente dopo 1 ora ---
        val now = System.currentTimeMillis()
        val oneHourMillis = 60L * 60 * 1000

        root.walkTopDown()
            .onEnter { dir -> PINNED_FOLDERS.none { p -> dir.absolutePath.startsWith(p) } }
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
                                    .removePrefix(MONITORED_DIR).trimStart('/')

                                hydrationManager.suppressHydration(originalFile.absolutePath)
                                ShadowClient.upload(this, originalFile, relPath) { success ->
                                    if (success) {
                                        vfsManager.markAsGhost(originalFile)
                                        markerFile.delete()
                                        android.util.Log.i("ShadowFS", "Re-Ghost completato: $originalFileName")
                                    }
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

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("ShadowFS Attivo")
        .setContentText("Memoria ottimizzata in background")
        .setSmallIcon(android.R.drawable.sym_def_app_icon)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scanTimer.cancel()
        hydrationManager.stop()
    }
}
