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
    private val MONITORED_DIR = "/storage/emulated/0" // Ora monitora l'intera memoria condivisa
    private lateinit var vfsManager: VfsManager
    private lateinit var hydrationManager: HydrationManager
    private val scanTimer = Timer()

    // --- REGOLE INTELLIGENTI DI GHOSTING ---
    private val PINNED_FOLDERS = listOf(
        "/storage/emulated/0/Download/Lavoro",  // Esempio: cartella da non toccare mai
        "/storage/emulated/0/Android"           // FONDAMENTALE: Mai ghostare la cartella di sistema visibile
    )
    
    // Lista BIANCA (Whitelist): Ghostiamo SOLO questi tipi di file (Media e Documenti grossi)
    // Se vuota, applicherà solo la Blacklist. Ma in uno Smart Sync è meglio essere conservativi.
    private val ALLOWED_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".mp4", ".mov", ".mkv", ".pdf", ".zip")
    
    // Lista NERA (Blacklist): Estensioni di sistema o database da ignorare sempre
    private val IGNORED_EXTENSIONS = listOf(".apk", ".nomedia", ".db", ".ini", ".temp")
    
    private val MIN_FILE_SIZE_BYTES = 1024 * 512L // 512 KB (Sotto questa soglia non ghostiamo)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
        
        vfsManager = VfsManager()
        // Idratiamo gli accessi basandoci sulla Root, non più solo sulla cartella Camera
        hydrationManager = HydrationManager(this, MONITORED_DIR)
        hydrationManager.start()

        // Controlla la memoria ogni ora (3600000 ms)
        scanTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                checkSpaceAndOffload()
            }
        }, 0, 3600000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "FORCE_OFFLOAD") {
            Thread {
                checkSpaceAndOffload(force = true)
            }.start()
        }
        return START_STICKY
    }

    private fun checkSpaceAndOffload(force: Boolean = false) {
        val root = File(MONITORED_DIR)
        val usableSpace = root.usableSpace
        val totalSpace = root.totalSpace
        val freePercentage = (usableSpace.toDouble() / totalSpace.toDouble()) * 100

        // Se lo spazio scende sotto il 15%, O l'utente ha premuto il tasto "Forza"
        if (force || freePercentage < 15.0) {
            val thresholdTime = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000)

            // Usa "walkTopDown" per scorrere tutte le sottocartelle
            root.walkTopDown()
                .onEnter { dir -> 
                    // Se la cartella è nella lista nera, non entrarci proprio (Salva CPU)
                    !PINNED_FOLDERS.any { pinned -> dir.absolutePath.startsWith(pinned) }
                }
                .filter { file -> file.isFile } 
                .filter { file -> 
                    val ext = "." + file.extension.lowercase()
                    val isAllowedType = ALLOWED_EXTENSIONS.isEmpty() || ALLOWED_EXTENSIONS.contains(ext)
                    
                    !file.name.endsWith(".shadow") && 
                    !file.name.endsWith(".reghost") &&
                    isAllowedType &&                         // Controlla se è un tipo autorizzato (Whitelist)
                    !IGNORED_EXTENSIONS.contains(ext) &&     // Controlla le estensioni bandite (Blacklist)
                    file.length() > MIN_FILE_SIZE_BYTES &&   // Solo file sopra i 512KB
                    (force || file.lastModified() < thresholdTime)
                }
                .forEach { file ->
                    // Manda il file al Raspberry Pi tramite QUIC
                    QuicClientMock.upload(file) { success ->
                        if (success) {
                            vfsManager.markAsGhost(file)
                        }
                    }
                }
        }

        // --- RE-GHOSTING LOGIC: Pulizia dei file Idrattati temporaneamente ---
        val now = System.currentTimeMillis()
        val oneHourMillis = 60L * 60 * 1000 // Finestra di 1 ora

        root.walkTopDown()
            .onEnter { dir -> 
                !PINNED_FOLDERS.any { pinned -> dir.absolutePath.startsWith(pinned) }
            }
            .filter { file -> file.isFile && file.name.endsWith(".reghost") }
            .forEach { markerFile ->
                try {
                    val hydrationTime = markerFile.readText().toLongOrNull() ?: 0L
                    if (force || (now - hydrationTime > oneHourMillis)) {
                        val originalFileName = markerFile.name.removeSuffix(".reghost")
                        val originalFile = File(markerFile.parentFile, originalFileName) // BUG FIX: Usa la cartella del marker
                        
                        if (originalFile.exists() && originalFile.length() > 0) {
                            // SICUREZZA: Controlla se il file è bloccato da un'altra app
                            val isLocked = !originalFile.renameTo(originalFile)
                            
                            // Inoltre diamo un grace-period se il file è stato appena modificato
                            val recentlyModified = (now - originalFile.lastModified()) < 60000 
                            
                            if (!isLocked && !recentlyModified) {
                                QuicClientMock.upload(originalFile) { success ->
                                    if (success) {
                                        vfsManager.markAsGhost(originalFile)
                                        markerFile.delete()
                                        println("Re-Ghost completato per: $originalFileName")
                                    }
                                }
                            } else {
                                println("File $originalFileName ancora in uso. Re-ghosting rimandato.")
                            }
                        } else {
                            markerFile.delete() 
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shadow Daemon Core",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
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

// Simulatore Client QUIC per scopi architetturali
object QuicClientMock {
    fun upload(file: File, callback: (Boolean) -> Unit) {
        println("Simulando upload su QUIC mTLS: ${file.name}")
        callback(true)
    }

    fun requestChunk(fileName: String, callback: (ByteArray?) -> Unit) {
        println("Richiedendo file da QUIC mTLS: ${fileName}")
        // Dati fittizi simulati dal Raspberry
        callback("DATI REIDRATATI DAL MAGZZINO".toByteArray())
    }
}
