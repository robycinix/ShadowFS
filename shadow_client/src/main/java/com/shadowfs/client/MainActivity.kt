package com.shadowfs.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
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

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
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

        // Certificati
        val certsOk = ShadowClient.areCertsPresent()
        tvCertStatus.text = if (certsOk) "🔒 Certificati trovati in shadowfs_certs/" else "❌ Certificati mancanti"
        tvCertPath.text = "Percorso: ${ShadowClient.CERTS_DIR}"
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

    /** Mostra nella home solo il sommario (count + spazio recuperato) */
    private fun refreshGhostList() {
        val root = File(Environment.getExternalStorageDirectory().absolutePath)
        val shadowFiles = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".shadow") }
            .toList()

        if (shadowFiles.isEmpty()) {
            tvGhostSummary.text = ""
            btnGhostList.text = "👻 Vedi File Ghostati"
            return
        }

        val totalRecovered = shadowFiles.sumOf { shadowFile ->
            shadowFile.readLines()
                .firstOrNull { it.startsWith("originalSize=") }
                ?.removePrefix("originalSize=")?.toLongOrNull() ?: 0L
        }

        btnGhostList.text = "👻 Vedi File Ghostati (${shadowFiles.size})"
        tvGhostSummary.text = "Spazio recuperato: ${formatSize(totalRecovered)}"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }

    /** Controlla se il ForegroundService è in esecuzione */
    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(android.app.ActivityManager::class.java)
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == ShadowForegroundService::class.java.name }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
