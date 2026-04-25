package com.shadowfs.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * MainActivity — schermata di configurazione e controllo di ShadowFS.
 *
 * Funzioni:
 *  1. Richiedere il permesso MANAGE_EXTERNAL_STORAGE (richiesto per Android 11+)
 *  2. Configurare IP e porta del Raspberry Pi
 *  3. Avviare/fermare il servizio in background
 *  4. Testare la connessione
 *  5. Forzare l'offload immediato (Ghosting)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Passi dell'onboarding in ordine
        private const val STEP_NOTIFICATIONS = 0
        private const val STEP_STORAGE       = 1
        private const val STEP_BATTERY       = 2
        private const val STEP_CLOUD         = 3
        private const val STEP_DONE          = 4
        private const val REQ_NOTIFICATIONS  = 200
    }

    /** true se l'app si è sospesa per aprire le Settings di sistema (storage o battery).
     *  Usato in onResume per avanzare all'onboarding step successivo al ritorno. */
    private var waitingForSettings = false

    // --- Views ---
    private lateinit var tvStatus: TextView
    private lateinit var tvSpace: TextView
    private lateinit var tvCertStatus: TextView
    private lateinit var etServerIp: EditText
    private lateinit var etServerPort: EditText
    private lateinit var btnSaveConfig: Button
    private lateinit var btnPermission: Button
    private lateinit var btnStartService: Button
    private lateinit var btnForceOffload: Button
    private lateinit var btnTestConnection: Button
    private lateinit var tvCertPath: TextView
    private lateinit var btnGhostList: Button
    private lateinit var tvGhostSummary: TextView
    private lateinit var btnDiscover: Button
    private lateinit var btnQrScan: Button
    private lateinit var btnPinnedFolders: Button
    private lateinit var btnBatteryOpt: Button

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        refreshUI()
        startOnboarding()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
        // Torna dalle Settings di sistema (storage / battery): avanza all'onboarding
        if (waitingForSettings) {
            waitingForSettings = false
            advanceOnboarding()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) advanceOnboarding()
    }

    private fun bindViews() {
        tvStatus        = findViewById(R.id.tv_status)
        tvSpace         = findViewById(R.id.tv_space)
        tvCertStatus    = findViewById(R.id.tv_cert_status)
        etServerIp      = findViewById(R.id.et_server_ip)
        etServerPort    = findViewById(R.id.et_server_port)
        btnSaveConfig   = findViewById(R.id.btn_save_config)
        btnPermission   = findViewById(R.id.btn_permission)
        btnStartService = findViewById(R.id.btn_start_service)
        btnForceOffload = findViewById(R.id.btn_force_offload)
        btnTestConnection = findViewById(R.id.btn_test_connection)
        tvCertPath        = findViewById(R.id.tv_cert_path)
        btnGhostList      = findViewById(R.id.btn_ghost_list)
        tvGhostSummary    = findViewById(R.id.tv_ghost_summary)
        btnDiscover       = findViewById(R.id.btn_discover)
        btnQrScan         = findViewById(R.id.btn_qr_scan)
        btnPinnedFolders  = findViewById(R.id.btn_pinned_folders)
        btnBatteryOpt     = findViewById(R.id.btn_battery_opt)
    }

    private fun setupListeners() {

        // Salva la configurazione IP:porta
        btnSaveConfig.setOnClickListener {
            val ip = etServerIp.text.toString().trim()
            val portStr = etServerPort.text.toString().trim()
            val port = portStr.toIntOrNull() ?: 4243

            if (ip.isEmpty()) {
                toast("Inserisci l'IP del Raspberry Pi")
                return@setOnClickListener
            }
            ShadowClient.saveConfig(this, ip, port)
            toast("✅ Configurazione salvata: $ip:$port")
            refreshUI()
        }

        // Apre la schermata di sistema per il permesso "Gestisci tutti i file"
        btnPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(fallback)
                }
            } else {
                toast("Permesso già disponibile su Android < 11")
            }
        }

        // Avvia (o ferma) il servizio in background
        btnStartService.setOnClickListener {
            val intent = Intent(this, ShadowForegroundService::class.java)
            if (!isServiceRunning()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                toast("▶ Shadow Daemon avviato")
            } else {
                stopService(intent)
                toast("⏹ Shadow Daemon fermato")
            }
            // Breve delay prima di aggiornare UI per dare tempo al servizio di avviarsi
            btnStartService.postDelayed({ refreshUI() }, 500)
        }

        // Apri la lista file ghostati
        btnGhostList.setOnClickListener {
            startActivity(Intent(this, GhostListActivity::class.java))
        }

        // Scopri il Raspberry tramite mDNS
        btnDiscover.setOnClickListener {
            startMdnsDiscovery()
        }

        // Pairing via QR Code
        btnQrScan.setOnClickListener {
            startActivity(Intent(this, QrScanActivity::class.java))
        }

        // Forza lo svuotamento immediato dei file idonei
        btnForceOffload.setOnClickListener {
            if (!isServiceRunning()) {
                toast("Avvia prima il Shadow Daemon")
                return@setOnClickListener
            }
            val intent = Intent(this, ShadowForegroundService::class.java)
            intent.action = "FORCE_OFFLOAD"
            startService(intent)
            toast("⚡ Offload forzato in corso...")
        }

        // Gestione cartelle protette (mai ghostate)
        btnPinnedFolders.setOnClickListener {
            showPinnedFoldersDialog()
        }

        // Richiedi esenzione da Doze/battery optimization.
        // Alcuni OEM (Xiaomi, Huawei) bloccano questo intent → try-catch obbligatorio.
        btnBatteryOpt.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback: apre le impostazioni generali della batteria
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        toast("Apri Impostazioni → Batteria → Ottimizzazione → ShadowFS → Non ottimizzare")
                    }
                }
            }
        }

        // Test connessione TCP+mTLS con il Raspberry
        btnTestConnection.setOnClickListener {
            if (!ShadowClient.isConfigured(this)) {
                toast("Configura prima l'IP del server")
                return@setOnClickListener
            }
            tvStatus.text = "🔄 Test connessione in corso..."
            btnTestConnection.isEnabled = false

            ShadowClient.testConnection(this) { success, message ->
                runOnUiThread {
                    tvStatus.text = if (success) "🟢 $message" else "🔴 $message"
                    btnTestConnection.isEnabled = true
                }
            }
        }
    }

    // ================================================================
    // ONBOARDING — guidato passo per passo al primo avvio
    // ================================================================

    /** Avvia l'onboarding se non è ancora stato completato. */
    private fun startOnboarding() {
        val step = ShadowClient.getOnboardingStep(this)
        if (step >= STEP_DONE) return
        showOnboardingStep(step)
    }

    /** Mostra il passo corrente dell'onboarding. */
    private fun showOnboardingStep(step: Int) {
        when (step) {
            STEP_NOTIFICATIONS -> showOnboardingNotifications()
            STEP_STORAGE       -> showOnboardingStorage()
            STEP_BATTERY       -> showOnboardingBattery()
            STEP_CLOUD         -> showCloudBackupWarning()
            else               -> ShadowClient.setOnboardingStep(this, STEP_DONE)
        }
    }

    /** Avanza al passo successivo dell'onboarding. */
    private fun advanceOnboarding() {
        val next = ShadowClient.getOnboardingStep(this) + 1
        ShadowClient.setOnboardingStep(this, next)
        showOnboardingStep(next)
    }

    // ── Passo 0: Notifiche ───────────────────────────────────────────

    private fun showOnboardingNotifications() {
        // Android < 13 non richiede il permesso runtime — salta
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            advanceOnboarding()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("🔔  Notifiche (1/3)")
            .setMessage(
                "ShadowFS usa le notifiche per avvisarti:\n\n" +
                "• Upload e download in corso\n" +
                "• Raspberry non raggiungibile\n" +
                "• File ripristinati con successo\n\n" +
                "Premi Consenti nella finestra che apparirà."
            )
            .setPositiveButton("Consenti") { _, _ ->
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS
                )
            }
            .setNegativeButton("Salta") { _, _ -> advanceOnboarding() }
            .setCancelable(false)
            .show()
    }

    // ── Passo 1: Accesso ai file ─────────────────────────────────────

    private fun showOnboardingStorage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            advanceOnboarding()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("📁  Accesso ai file (2/3)")
            .setMessage(
                "ShadowFS ha bisogno di accedere a TUTTI i file per ghostare " +
                "e ripristinare foto, video e documenti.\n\n" +
                "Nelle impostazioni che si apriranno:\n" +
                "  1. Trova ShadowFS nella lista\n" +
                "  2. Attiva \"Consenti accesso a tutti i file\"\n" +
                "  3. Torna qui"
            )
            .setPositiveButton("Apri Impostazioni") { _, _ ->
                waitingForSettings = true
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .apply { data = Uri.parse("package:$packageName") }
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            .setNegativeButton("Salta") { _, _ -> advanceOnboarding() }
            .setCancelable(false)
            .show()
    }

    // ── Passo 2: Ottimizzazione batteria ─────────────────────────────

    private fun showOnboardingBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm.isIgnoringBatteryOptimizations(packageName)) {
            advanceOnboarding()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("🔋  Batteria (3/3)")
            .setMessage(
                "Per ghostare i file in background senza interruzioni, ShadowFS " +
                "deve essere escluso dall'ottimizzazione batteria " +
                "(altrimenti Android lo sospende dopo pochi minuti).\n\n" +
                "Nelle impostazioni:\n" +
                "  1. Trova ShadowFS\n" +
                "  2. Seleziona \"Non ottimizzare\" o \"Nessuna restrizione\"\n" +
                "  3. Torna qui"
            )
            .setPositiveButton("Apri Impostazioni") { _, _ ->
                waitingForSettings = true
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .apply { data = Uri.parse("package:$packageName") }
                    )
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        advanceOnboarding() // OEM non supporta l'intent
                    }
                }
            }
            .setNegativeButton("Salta") { _, _ -> advanceOnboarding() }
            .setCancelable(false)
            .show()
    }

    private fun showPinnedFoldersDialog() {
        val defaultPinned = ShadowClient.DEFAULT_PINNED
        val allPinned = ShadowClient.getPinnedFolders(this).toList().sorted()

        val items = allPinned.map { path ->
            val isDefault = defaultPinned.contains(path)
            if (isDefault) "🔒 $path" else "📌 $path"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Cartelle Protette")
            .setMessage(if (allPinned.isEmpty()) "Nessuna cartella protetta.\nI file 🔒 sono predefiniti e non rimovibili." else null)
            .setItems(items) { _, index ->
                val path = allPinned[index]
                if (defaultPinned.contains(path)) {
                    toast("Le cartelle 🔒 predefinite non possono essere rimosse")
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Rimuovi cartella")
                        .setMessage("Rimuovere dalla protezione?\n$path")
                        .setPositiveButton("Rimuovi") { _, _ ->
                            ShadowClient.removePinnedFolder(this, path)
                            toast("✅ Cartella rimossa dalla protezione")
                        }
                        .setNegativeButton("Annulla", null)
                        .show()
                }
            }
            .setPositiveButton("➕ Aggiungi") { _, _ ->
                showAddPinnedFolderDialog()
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun showAddPinnedFolderDialog() {
        val input = EditText(this).apply {
            hint = "/storage/emulated/0/DCIM/Screenshots"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Aggiungi Cartella Protetta")
            .setMessage("Inserisci il percorso completo da proteggere (i file al suo interno non verranno mai ghostati).")
            .setView(input)
            .setPositiveButton("Aggiungi") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isEmpty()) {
                    toast("Percorso vuoto")
                    return@setPositiveButton
                }
                if (!path.startsWith("/storage/emulated/0/")) {
                    toast("Il percorso deve iniziare con /storage/emulated/0/")
                    return@setPositiveButton
                }
                ShadowClient.addPinnedFolder(this, path)
                toast("📌 Cartella protetta: $path")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun refreshUI() {
        // Carica configurazione salvata
        val savedIp = ShadowClient.getServerIp(this)
        val savedPort = ShadowClient.getServerPort(this)
        if (savedIp.isNotEmpty()) {
            etServerIp.setText(savedIp)
            etServerPort.setText(savedPort.toString())
        }

        // Spazio libero
        val root = File(Environment.getExternalStorageDirectory().absolutePath)
        val usable = root.usableSpace
        val total = root.totalSpace
        val freePercent = if (total > 0) (usable.toDouble() / total * 100).toInt() else 0
        val usableGB = usable / (1024.0 * 1024 * 1024)
        val totalGB = total / (1024.0 * 1024 * 1024)
        tvSpace.text = "💾 Spazio: %.1f GB liberi / %.1f GB totali (%d%%)".format(usableGB, totalGB, freePercent)

        // Permesso
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else { true }
        btnPermission.text = if (hasPermission) "✅ Permesso concesso" else "⚠️ Richiedi permesso Gestione File"
        btnPermission.isEnabled = !hasPermission

        // Battery optimization
        val pm = getSystemService(PowerManager::class.java)
        val batteryOptDisabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            pm.isIgnoringBatteryOptimizations(packageName)
        btnBatteryOpt.text = if (batteryOptDisabled)
            "✅ Ottimizzazione batteria disattivata"
        else
            "🔋 Disattiva Ottimizzazione Batteria"
        btnBatteryOpt.isEnabled = !batteryOptDisabled

        // Certificati
        val certsOk = ShadowClient.areCertsPresent(this)
        tvCertStatus.text = if (certsOk) "🔒 Certificati trovati nello storage privato dell'app" else "❌ Certificati mancanti"
        tvCertPath.text = "Percorso: ${ShadowClient.getCertsDisplayPath(this)}"
        tvCertPath.visibility = if (!certsOk) View.VISIBLE else View.GONE

        // Servizio
        val running = isServiceRunning()
        btnStartService.text = if (running) "⏹ Ferma Shadow Daemon" else "▶ Avvia Shadow Daemon"
        btnForceOffload.isEnabled = running && hasPermission && certsOk && ShadowClient.isConfigured(this)

        // Lista file ghostati
        refreshGhostList()

        // Stato generale
        if (!ShadowClient.isConfigured(this)) {
            tvStatus.text = "⚙️ Configura l'IP del Raspberry Pi per iniziare"
        } else if (!certsOk) {
            tvStatus.text = "⚙️ Copia i certificati in Download/shadowfs_certs/"
        } else if (!hasPermission) {
            tvStatus.text = "⚙️ Concedi il permesso \"Gestione file\""
        } else if (running) {
            tvStatus.text = "🟢 Shadow Daemon attivo — memoria sotto controllo"
        } else {
            tvStatus.text = "⏸ Daemon non avviato — premi Avvia per proteggere la memoria"
        }
    }

    /** Avvia la scoperta mDNS del Raspberry sulla rete locale */
    private fun startMdnsDiscovery() {
        stopMdnsDiscovery()
        btnDiscover.isEnabled = false
        btnDiscover.text = "🔍 Ricerca..."
        tvStatus.text = "🔄 Cerco il Raspberry in rete..."

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread {
                    tvStatus.text = "❌ Raspberry trovato ma indirizzo non risolvibile"
                    resetDiscoverButton()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                val port = serviceInfo.port
                runOnUiThread {
                    etServerIp.setText(host)
                    etServerPort.setText(port.toString())
                    ShadowClient.saveConfig(this@MainActivity, host, port)
                    toast("✅ Raspberry trovato: $host:$port")
                    tvStatus.text = "✅ Raspberry trovato automaticamente: $host"
                    resetDiscoverButton()
                    refreshUI()
                }
                stopMdnsDiscovery()
            }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread {
                    tvStatus.text = "❌ Impossibile avviare la ricerca mDNS (errore $errorCode)"
                    resetDiscoverButton()
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager?.resolveService(serviceInfo, resolveListener)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        nsdManager?.discoverServices("_shadowfs._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        // Timeout: se non trovato entro 10s, mostra messaggio
        btnDiscover.postDelayed({
            if (!btnDiscover.isEnabled) {
                stopMdnsDiscovery()
                tvStatus.text = "⚠️ Nessun Raspberry trovato in rete. Inserisci l'IP manualmente."
                resetDiscoverButton()
            }
        }, 10_000)
    }

    private fun stopMdnsDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        discoveryListener = null
    }

    private fun resetDiscoverButton() {
        btnDiscover.isEnabled = true
        btnDiscover.text = "🔍 Cerca in Wi-Fi"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMdnsDiscovery()
    }

    /**
     * Dialog mostrato UNA SOLA VOLTA al primo avvio.
     * Avverte l'utente di disattivare il backup cloud (Google Photos, Xiaomi Gallery,
     * Samsung Gallery, ecc.) per le cartelle gestite da ShadowFS.
     * Per ogni app cloud rilevata mostra un pulsante diretto alle impostazioni.
     */
    /**
     * Passo finale dell'onboarding: avvisa di disattivare il backup cloud
     * nelle app rilevate (Google Photos, Xiaomi, Samsung, OneDrive, ecc.).
     * Alla chiusura del dialog avanza l'onboarding → STEP_DONE.
     */
    private fun showCloudBackupWarning() {
        val knownCloudApps = listOf(
            Triple("Google Photos",   "com.google.android.apps.photos", "Backup e sincronizzazione"),
            Triple("Xiaomi Gallery",  "com.miui.gallery",               "Backup cloud MIUI"),
            Triple("Samsung Gallery", "com.sec.android.gallery3d",      "Samsung Cloud"),
            Triple("OneDrive",        "com.microsoft.skydrive",         "Caricamento automatico fotocamera"),
            Triple("Dropbox",         "com.dropbox.android",            "Caricamento automatico fotocamera"),
            Triple("Amazon Photos",   "com.amazon.clouddrive.photos",   "Auto-salvataggio foto"),
        )

        val installed = knownCloudApps.filter { (_, pkg, _) ->
            try { packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        }

        val message = buildString {
            append("ShadowFS gestisce autonomamente i tuoi file.\n\n")
            append("⚠️  Per evitare conflitti, disattiva il backup automatico delle foto nelle app cloud:\n\n")
            if (installed.isEmpty()) {
                append("Nessuna app cloud rilevata — sei a posto!")
            } else {
                installed.forEach { (name, _, setting) ->
                    append("• $name  →  $setting\n")
                }
                append("\nPuoi farlo ora dalle impostazioni di ogni app.")
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("☁️  Backup cloud")
            .setMessage(message)
            .setNegativeButton("Ho capito") { _, _ -> advanceOnboarding() }
            .setCancelable(false)

        if (installed.isNotEmpty()) {
            builder.setPositiveButton("Apri ${installed.first().first}") { _, _ ->
                openAppSettings(installed.first().second)
                advanceOnboarding()
            }
        }

        builder.show()
    }

    private fun openAppSettings(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            toast("Apri manualmente le impostazioni di $packageName")
        }
    }

    /** Mostra nella home solo il sommario (count + spazio recuperato).
     *  walkTopDown() può essere lenta su storage pieno: viene eseguita in background. */
    private fun refreshGhostList() {
        Thread {
            val root = File(Environment.getExternalStorageDirectory().absolutePath)
            val shadowFiles = root.walkTopDown()
                .filter { isUserVisibleShadow(it) }
                .toList()

            val totalRecovered = shadowFiles.sumOf { shadowFile ->
                shadowFile.readLines()
                    .firstOrNull { it.startsWith("originalSize=") }
                    ?.removePrefix("originalSize=")?.toLongOrNull() ?: 0L
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (shadowFiles.isEmpty()) {
                    tvGhostSummary.text = ""
                    btnGhostList.text = "👻 Vedi File Ghostati"
                } else {
                    btnGhostList.text = "👻 Vedi File Ghostati (${shadowFiles.size})"
                    tvGhostSummary.text = "Spazio recuperato: ${formatSize(totalRecovered)}"
                }
            }
        }.start()
    }

    private fun isUserVisibleShadow(file: File): Boolean {
        if (!file.isFile || !file.name.endsWith(".shadow")) return false
        if (file.absolutePath.contains("/.pending-")) return false
        val originalName = file.name.removeSuffix(".shadow")
        return !originalName.startsWith(".pending-")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }

    /** Controlla se il ForegroundService è in esecuzione.
     *  getRunningServices() è deprecated e inutilizzabile da Android 8+:
     *  restituisce sempre lista vuota per le app non-system. Usiamo il flag statico. */
    private fun isServiceRunning(): Boolean = ShadowForegroundService.isRunning

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
