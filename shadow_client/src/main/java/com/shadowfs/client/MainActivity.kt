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
    private lateinit var switchAutoHydration: Switch

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
        switchAutoHydration = findViewById(R.id.switch_auto_hydration)
    }

    private fun setupListeners() {

        // Salva la configurazione IP:porta
        btnSaveConfig.setOnClickListener {
            val ip = etServerIp.text.toString().trim()
            val portStr = etServerPort.text.toString().trim()
            val port = portStr.toIntOrNull() ?: 4243

            if (ip.isEmpty()) {
                toast(R.string.toast_enter_raspberry_ip)
                return@setOnClickListener
            }
            ShadowClient.saveConfig(this, ip, port)
            toast(R.string.toast_config_saved, ip, port)
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
                toast(R.string.toast_permission_already_available)
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
                toast(R.string.toast_daemon_started)
            } else {
                stopService(intent)
                toast(R.string.toast_daemon_stopped)
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
                toast(R.string.toast_start_daemon_first)
                return@setOnClickListener
            }
            val intent = Intent(this, ShadowForegroundService::class.java)
            intent.action = "FORCE_OFFLOAD"
            startService(intent)
            toast(R.string.toast_force_offload_running)
        }

        // Gestione cartelle protette (mai ghostate)
        btnPinnedFolders.setOnClickListener {
            showPinnedFoldersDialog()
        }

        // Idratazione automatica: quando un'app apre un file ghost, ShadowFS lo
        // riscarica dal Raspberry. Senza questo switch la preferenza
        // "auto_hydration_enabled" resterebbe per sempre false (default) e
        // l'idratazione trasparente non sarebbe mai attivabile.
        switchAutoHydration.isChecked = getSharedPreferences(ShadowClient.PREFS_NAME, MODE_PRIVATE)
            .getBoolean("auto_hydration_enabled", false)
        switchAutoHydration.setOnCheckedChangeListener { _, enabled ->
            getSharedPreferences(ShadowClient.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean("auto_hydration_enabled", enabled)
                .apply()
            toast(if (enabled)
                getString(R.string.toast_auto_hydration_enabled)
            else
                getString(R.string.toast_auto_hydration_disabled))
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
                        toast(R.string.toast_open_battery_settings)
                    }
                }
            }
        }

        // Test connessione TCP+mTLS con il Raspberry
        btnTestConnection.setOnClickListener {
            if (!ShadowClient.isConfigured(this)) {
                toast(R.string.toast_configure_server_first)
                return@setOnClickListener
            }
            tvStatus.setText(R.string.status_testing_connection)
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
            .setTitle(R.string.onboarding_notifications_title)
            .setMessage(R.string.onboarding_notifications_message)
            .setPositiveButton(R.string.button_allow) { _, _ ->
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS
                )
            }
            .setNegativeButton(R.string.button_skip) { _, _ -> advanceOnboarding() }
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
            .setTitle(R.string.onboarding_storage_title)
            .setMessage(R.string.onboarding_storage_message)
            .setPositiveButton(R.string.button_open_settings) { _, _ ->
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
            .setNegativeButton(R.string.button_skip) { _, _ -> advanceOnboarding() }
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
            .setTitle(R.string.onboarding_battery_title)
            .setMessage(R.string.onboarding_battery_message)
            .setPositiveButton(R.string.button_open_settings) { _, _ ->
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
            .setNegativeButton(R.string.button_skip) { _, _ -> advanceOnboarding() }
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
            .setTitle(R.string.pinned_folders_title)
            .setMessage(if (allPinned.isEmpty()) getString(R.string.pinned_folders_empty) else null)
            .setItems(items) { _, index ->
                val path = allPinned[index]
                if (defaultPinned.contains(path)) {
                    toast(R.string.toast_default_pinned_cannot_remove)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.remove_folder_title)
                        .setMessage(getString(R.string.remove_folder_message, path))
                        .setPositiveButton(R.string.button_remove) { _, _ ->
                            ShadowClient.removePinnedFolder(this, path)
                            toast(R.string.toast_folder_removed)
                        }
                        .setNegativeButton(R.string.button_cancel, null)
                        .show()
                }
            }
            .setPositiveButton(R.string.button_add) { _, _ ->
                showAddPinnedFolderDialog()
            }
            .setNegativeButton(R.string.button_close, null)
            .show()
    }

    private fun showAddPinnedFolderDialog() {
        val input = EditText(this).apply {
            hint = "/storage/emulated/0/DCIM/Screenshots"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_pinned_folder_title)
            .setMessage(R.string.add_pinned_folder_message)
            .setView(input)
            .setPositiveButton(R.string.button_add) { _, _ ->
                val path = input.text.toString().trim()
                if (path.isEmpty()) {
                    toast(R.string.toast_empty_path)
                    return@setPositiveButton
                }
                if (!path.startsWith("/storage/emulated/0/")) {
                    toast(R.string.toast_path_must_start)
                    return@setPositiveButton
                }
                ShadowClient.addPinnedFolder(this, path)
                toast(R.string.toast_folder_protected, path)
            }
            .setNegativeButton(R.string.button_cancel, null)
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
        tvSpace.text = getString(R.string.storage_space_status, usableGB, totalGB, freePercent)

        // Permesso
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else { true }
        btnPermission.text = getString(if (hasPermission) R.string.button_file_permission_granted else R.string.button_file_permission_request)
        btnPermission.isEnabled = !hasPermission

        // Battery optimization
        val pm = getSystemService(PowerManager::class.java)
        val batteryOptDisabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            pm.isIgnoringBatteryOptimizations(packageName)
        btnBatteryOpt.text = if (batteryOptDisabled)
            getString(R.string.button_battery_optimization_disabled)
        else
            getString(R.string.button_battery_optimization_disable)
        btnBatteryOpt.isEnabled = !batteryOptDisabled

        // Certificati
        val certsOk = ShadowClient.areCertsPresent(this)
        tvCertStatus.text = getString(if (certsOk) R.string.certificates_found else R.string.certificates_missing)
        tvCertPath.text = getString(R.string.certificates_path, ShadowClient.getCertsDisplayPath(this))
        tvCertPath.visibility = if (!certsOk) View.VISIBLE else View.GONE

        // Servizio
        val running = isServiceRunning()
        btnStartService.text = getString(if (running) R.string.button_stop_daemon else R.string.button_start_daemon)
        btnForceOffload.isEnabled = running && hasPermission && certsOk && ShadowClient.isConfigured(this)

        // Lista file ghostati
        refreshGhostList()

        // Stato generale
        if (!ShadowClient.isConfigured(this)) {
            tvStatus.setText(R.string.status_configure_server)
        } else if (!certsOk) {
            tvStatus.setText(R.string.status_copy_certs)
        } else if (!hasPermission) {
            tvStatus.setText(R.string.status_grant_file_permission)
        } else if (running) {
            tvStatus.setText(R.string.status_daemon_running)
        } else {
            tvStatus.setText(R.string.status_daemon_stopped)
        }
    }

    /** Avvia la scoperta mDNS del Raspberry sulla rete locale */
    private fun startMdnsDiscovery() {
        stopMdnsDiscovery()
        btnDiscover.isEnabled = false
        btnDiscover.setText(R.string.button_discover_searching)
        tvStatus.setText(R.string.status_searching_raspberry)

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread {
                    tvStatus.setText(R.string.status_raspberry_unresolvable)
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
                    toast(R.string.toast_raspberry_found, host, port)
                    tvStatus.text = getString(R.string.status_raspberry_found, host)
                    resetDiscoverButton()
                    refreshUI()
                }
                stopMdnsDiscovery()
            }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread {
                    tvStatus.text = getString(R.string.status_mdns_failed, errorCode)
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
                tvStatus.setText(R.string.status_no_raspberry_found)
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
        btnDiscover.setText(R.string.button_discover_wifi)
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
            Triple("Google Photos",   "com.google.android.apps.photos", getString(R.string.cloud_setting_backup_sync)),
            Triple("Xiaomi Gallery",  "com.miui.gallery",               getString(R.string.cloud_setting_miui_backup)),
            Triple("Samsung Gallery", "com.sec.android.gallery3d",      "Samsung Cloud"),
            Triple("OneDrive",        "com.microsoft.skydrive",         getString(R.string.cloud_setting_camera_upload)),
            Triple("Dropbox",         "com.dropbox.android",            getString(R.string.cloud_setting_camera_upload)),
            Triple("Amazon Photos",   "com.amazon.clouddrive.photos",   getString(R.string.cloud_setting_photo_auto_save)),
        )

        val installed = knownCloudApps.filter { (_, pkg, _) ->
            try { packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        }

        val message = buildString {
            append(getString(R.string.cloud_backup_intro))
            if (installed.isEmpty()) {
                append(getString(R.string.cloud_backup_none_detected))
            } else {
                installed.forEach { (name, _, setting) ->
                    append("• $name  →  $setting\n")
                }
                append(getString(R.string.cloud_backup_settings_suffix))
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.cloud_backup_title)
            .setMessage(message)
            .setNegativeButton(R.string.button_i_understand) { _, _ -> advanceOnboarding() }
            .setCancelable(false)

        if (installed.isNotEmpty()) {
            builder.setPositiveButton(getString(R.string.button_open_app, installed.first().first)) { _, _ ->
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
            toast(R.string.toast_open_app_settings_manually, packageName)
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
                    btnGhostList.setText(R.string.button_ghost_list)
                } else {
                    btnGhostList.text = getString(R.string.button_ghost_list_count, shadowFiles.size)
                    tvGhostSummary.text = getString(R.string.ghost_summary_recovered, formatSize(totalRecovered))
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
    private fun toast(resId: Int, vararg args: Any) =
        Toast.makeText(this, getString(resId, *args), Toast.LENGTH_SHORT).show()
}
