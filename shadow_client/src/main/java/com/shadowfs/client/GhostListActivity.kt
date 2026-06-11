package com.shadowfs.client

import android.app.AlertDialog
import android.media.MediaScannerConnection
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class GhostListActivity : AppCompatActivity() {

    private lateinit var containerGhostList: LinearLayout
    private lateinit var tvRecoveredTotal: TextView
    private lateinit var tvGhostCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnSync: Button
    private lateinit var containerOrphans: LinearLayout
    private lateinit var btnDeleteOrphans: Button
    private var currentOrphans: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ghost_list)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "File Ghostati"
        }

        containerGhostList = findViewById(R.id.container_ghost_list)
        tvRecoveredTotal   = findViewById(R.id.tv_recovered_total)
        tvGhostCount       = findViewById(R.id.tv_ghost_count)
        tvEmpty            = findViewById(R.id.tv_empty)
        btnSync            = findViewById(R.id.btn_sync)
        containerOrphans   = findViewById(R.id.container_orphans)
        btnDeleteOrphans   = findViewById(R.id.btn_delete_orphans)

        btnSync.setOnClickListener { checkOrphans() }

        btnDeleteOrphans.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Elimina orfani dal Raspberry")
                .setMessage("Eliminare ${currentOrphans.size} file dal Raspberry?\n\nSono file che hai già cancellato dal telefono. L'operazione è irreversibile.")
                .setPositiveButton("Elimina tutto") { _, _ -> deleteAllOrphans() }
                .setNegativeButton("Annulla", null)
                .show()
        }

        loadGhostFiles()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // Dati pre-calcolati per ogni file ghostato — lettura su background thread, UI su main thread
    private data class GhostEntry(
        val shadowFile: File,
        val originalName: String,
        val originalSize: Long,
        val hasThumbnail: Boolean,
        val localFile: File,
        val relPath: String
    )

    /** Avvia la scansione in background per evitare ANR su storage pieno */
    private fun loadGhostFiles() {
        containerGhostList.removeAllViews()
        tvGhostCount.text = "⏳ Caricamento..."
        tvEmpty.visibility = View.GONE
        containerGhostList.visibility = View.GONE

        Thread {
            val root = File(Environment.getExternalStorageDirectory().absolutePath)
            val entries = root.walkTopDown()
                .filter { isUserVisibleShadow(it) }
                .sortedByDescending { it.lastModified() }
                .map { shadowFile ->
                    val originalName = shadowFile.name.removeSuffix(".shadow")
                    val lines = try {
                        shadowFile.readLines().associate {
                            val parts = it.split("=", limit = 2)
                            if (parts.size == 2) parts[0] to parts[1] else "" to ""
                        }
                    } catch (_: Exception) { emptyMap() }
                    val originalSize = lines["originalSize"]?.toLongOrNull() ?: 0L
                    val hasThumbnail = lines["hasThumbnail"] == "true"
                    val localFile = File(shadowFile.parent, originalName)
                    val relPath = localFile.absolutePath
                        .removePrefix(root.absolutePath)
                        .trimStart('/')
                    GhostEntry(shadowFile, originalName, originalSize, hasThumbnail, localFile, relPath)
                }
                .toList()

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                buildGhostUI(entries)
            }
        }.start()
    }

    private fun buildGhostUI(entries: List<GhostEntry>) {
        containerGhostList.removeAllViews()

        if (entries.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            containerGhostList.visibility = View.GONE
            tvRecoveredTotal.text = ""
            tvGhostCount.text = "Nessun file ghostato"
            return
        }

        tvEmpty.visibility = View.GONE
        containerGhostList.visibility = View.VISIBLE
        tvGhostCount.text = "${entries.size} file ghostati"

        val totalRecovered = entries.sumOf { it.originalSize }

        val thumbSizePx = (64 * resources.displayMetrics.density).toInt()

        entries.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12, 12, 12, 12)
                setBackgroundColor(Color.parseColor("#1A1A2E"))
                gravity = android.view.Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, 8) }
                layoutParams = lp
            }

            // ── Thumbnail (colonna sinistra) ────────────────────────────────
            // Il file fisico ghost esiste ancora su disco (IS_PENDING non lo elimina),
            // quindi BitmapFactory può leggere il thumbnail JPEG direttamente.
            val imgThumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(thumbSizePx, thumbSizePx).also {
                    it.setMargins(0, 0, 12, 0)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#0D0D1A"))
            }
            if (entry.hasThumbnail) {
                Thread {
                    val bmp = runCatching {
                        BitmapFactory.decodeFile(entry.localFile.absolutePath)
                    }.getOrNull()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            if (bmp != null) imgThumb.setImageBitmap(bmp)
                            else imgThumb.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    }
                }.start()
            } else {
                imgThumb.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            // ── Colonna destra (nome + pulsante + dettagli) ─────────────────
            val colRight = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val rowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(this).apply {
                text = entry.originalName
                textSize = 14f
                setTextColor(Color.parseColor("#C0C0E0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnDelete = Button(this).apply {
                text = "🗑"
                textSize = 16f
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.parseColor("#FF6060"))
                setPadding(8, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val btnRestore = Button(this).apply {
                text = "Ripristina"
                textSize = 12f
                setTextColor(Color.parseColor("#80D0A0"))
                setPadding(8, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(8, 0, 8, 0) }
            }

            btnDelete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Elimina file")
                    .setMessage("Elimini '${entry.originalName}' anche dal Raspberry Pi?\n\nL'operazione è irreversibile.")
                    .setPositiveButton("Elimina") { _, _ ->
                        btnDelete.isEnabled = false
                        ShadowClient.delete(this, entry.relPath, entry.localFile) { success ->
                            runOnUiThread {
                                if (success) {
                                    Toast.makeText(this, "✅ '${entry.originalName}' eliminato", Toast.LENGTH_SHORT).show()
                                    loadGhostFiles()
                                } else {
                                    Toast.makeText(this, "❌ Errore durante l'eliminazione", Toast.LENGTH_LONG).show()
                                    btnDelete.isEnabled = true
                                }
                            }
                        }
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }

            btnRestore.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Ripristina file")
                    .setMessage("Scaricare '${entry.originalName}' dal Raspberry e renderlo di nuovo disponibile sul telefono?")
                    .setPositiveButton("Ripristina") { _, _ ->
                        restoreGhost(entry, btnRestore)
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }

            rowTop.addView(tvName)
            rowTop.addView(btnRestore)
            rowTop.addView(btnDelete)

            val tvDetails = TextView(this).apply {
                text = formatSize(entry.originalSize)
                textSize = 12f
                setTextColor(Color.parseColor("#606080"))
                setPadding(0, 4, 0, 0)
            }

            colRight.addView(rowTop)
            colRight.addView(tvDetails)
            card.addView(imgThumb)
            card.addView(colRight)
            containerGhostList.addView(card)
        }

        tvRecoveredTotal.text = "Recuperati: ${formatSize(totalRecovered)}"
    }

    private fun restoreGhost(entry: GhostEntry, button: Button) {
        button.isEnabled = false
        button.text = "..."
        ShadowClient.download(this, entry.relPath, entry.localFile) { success ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (success) {
                    entry.shadowFile.delete()
                    File(entry.localFile.parentFile, entry.localFile.name + ".reghost")
                        .writeText(System.currentTimeMillis().toString())
                    VfsManager.setIsPending(this, entry.localFile, pending = false)
                    MediaScannerConnection.scanFile(
                        this, arrayOf(entry.localFile.absolutePath), null, null
                    )
                    Toast.makeText(this, "✅ '${entry.originalName}' ripristinato", Toast.LENGTH_SHORT).show()
                    loadGhostFiles()
                } else {
                    Toast.makeText(this, "❌ Ripristino fallito", Toast.LENGTH_LONG).show()
                    button.text = "Ripristina"
                    button.isEnabled = true
                }
            }
        }
    }

    /** Confronta i file .shadow sul telefono con quelli sul Raspberry e mostra gli orfani.
     *  La scansione avviene in background per non bloccare il main thread. */
    private fun checkOrphans() {
        btnSync.isEnabled = false
        btnSync.text = "🔄 Controllo..."
        containerOrphans.removeAllViews()
        btnDeleteOrphans.visibility = View.GONE

        Thread {
            val root = File(Environment.getExternalStorageDirectory().absolutePath)
            // File "presenti" sul telefono = ghost (.shadow) + file idratati (.reghost).
            // Senza i .reghost, un file appena idratato apparirebbe come "orfano"
            // e l'utente potrebbe eliminarlo dal Raspberry mentre è ancora nel
            // ciclo di re-ghosting (la copia server è quella che salverà lo spazio).
            val phoneFiles = root.walkTopDown()
                .filter { isUserVisibleShadow(it) || isReghostMarker(it) }
                .map { marker ->
                    marker.absolutePath
                        .removePrefix(root.absolutePath)
                        .trimStart('/')
                        .removeSuffix(".shadow")
                        .removeSuffix(".reghost")
                }
                .distinct()
                .toList()

            ShadowClient.syncIndex(this, phoneFiles) { orphans ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    btnSync.isEnabled = true
                    btnSync.text = "🔍 Controlla"
                    currentOrphans = orphans

                    if (orphans.isEmpty()) {
                        val tv = TextView(this).apply {
                            text = "✅ Nessun orfano — il Raspberry è in sync con il telefono."
                            textSize = 13f
                            setTextColor(Color.parseColor("#80D0A0"))
                            setPadding(8, 8, 8, 8)
                        }
                        containerOrphans.addView(tv)
                    } else {
                        val header = TextView(this).apply {
                            text = "${orphans.size} file trovati sul Raspberry ma non sul telefono:"
                            textSize = 12f
                            setTextColor(Color.parseColor("#FF8080"))
                            setPadding(8, 4, 8, 8)
                        }
                        containerOrphans.addView(header)
                        orphans.forEach { relPath ->
                            val tv = TextView(this).apply {
                                text = "🗑  ${relPath.substringAfterLast('/')}"
                                textSize = 12f
                                setTextColor(Color.parseColor("#C0A0A0"))
                                setPadding(16, 4, 8, 4)
                            }
                            containerOrphans.addView(tv)
                        }
                        btnDeleteOrphans.visibility = View.VISIBLE
                    }
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

    /** Marker di un file idratato in attesa di re-ghosting: il file è sul telefono
     *  E sul Raspberry — non è un orfano. */
    private fun isReghostMarker(file: File): Boolean =
        file.isFile && file.name.endsWith(".reghost") && !file.name.startsWith(".pending-")

    /** Elimina tutti gli orfani dal Raspberry uno per uno */
    private fun deleteAllOrphans() {
        btnDeleteOrphans.isEnabled = false
        var deleted = 0
        var failed = 0
        val total = currentOrphans.size

        fun deleteNext(index: Int) {
            if (index >= total) {
                runOnUiThread {
                    val msg = if (failed == 0)
                        "✅ Eliminati $deleted/$total file dal Raspberry"
                    else
                        "⚠️ Eliminati $deleted/$total — $failed falliti (Raspberry irraggiungibile?)"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    btnDeleteOrphans.isEnabled = true
                    checkOrphans()
                }
                return
            }
            val relPath = currentOrphans[index]
            // File locale virtuale (non esiste sul telefono, serve solo il path)
            val dummyFile = File(Environment.getExternalStorageDirectory(), relPath)
            ShadowClient.delete(this, relPath, dummyFile) { success ->
                if (success) deleted++ else failed++
                deleteNext(index + 1)
            }
        }
        deleteNext(0)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }
}
