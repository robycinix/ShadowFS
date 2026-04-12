package com.shadowfs.client

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
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

    private fun loadGhostFiles() {
        containerGhostList.removeAllViews()

        val root = File(Environment.getExternalStorageDirectory().absolutePath)
        val shadowFiles = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".shadow") }
            .sortedByDescending { it.lastModified() }
            .toList()

        if (shadowFiles.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            containerGhostList.visibility = View.GONE
            tvRecoveredTotal.text = ""
            tvGhostCount.text = "Nessun file ghostato"
            return
        }

        tvEmpty.visibility = View.GONE
        containerGhostList.visibility = View.VISIBLE
        tvGhostCount.text = "${shadowFiles.size} file ghostati"

        var totalRecovered = 0L

        shadowFiles.forEach { shadowFile ->
            val originalName = shadowFile.name.removeSuffix(".shadow")
            val lines = shadowFile.readLines().associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else "" to ""
            }
            val originalSize = lines["originalSize"]?.toLongOrNull() ?: 0L
            val hasThumbnail = lines["hasThumbnail"] == "true"
            totalRecovered += originalSize

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
                setBackgroundColor(Color.parseColor("#1A1A2E"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, 8) }
                layoutParams = lp
            }

            // Riga superiore: nome + pulsante elimina
            val rowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(this).apply {
                text = "👻  $originalName"
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
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
            }

            val localFile = File(
                shadowFile.parent,
                shadowFile.name.removeSuffix(".shadow")
            )
            val relPath = localFile.absolutePath
                .removePrefix(Environment.getExternalStorageDirectory().absolutePath)
                .trimStart('/')

            btnDelete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Elimina file")
                    .setMessage("Elimini '$originalName' anche dal Raspberry Pi?\n\nL'operazione è irreversibile.")
                    .setPositiveButton("Elimina") { _, _ ->
                        btnDelete.isEnabled = false
                        ShadowClient.delete(this, relPath, localFile) { success ->
                            runOnUiThread {
                                if (success) {
                                    Toast.makeText(this, "✅ '$originalName' eliminato", Toast.LENGTH_SHORT).show()
                                    loadGhostFiles() // aggiorna la lista
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

            rowTop.addView(tvName)
            rowTop.addView(btnDelete)

            // Dettagli
            val tvDetails = TextView(this).apply {
                val thumbInfo = if (hasThumbnail) "anteprima disponibile" else "nessuna anteprima"
                text = "Originale: ${formatSize(originalSize)}  •  $thumbInfo"
                textSize = 12f
                setTextColor(Color.parseColor("#606080"))
                setPadding(0, 4, 0, 0)
            }

            card.addView(rowTop)
            card.addView(tvDetails)
            containerGhostList.addView(card)
        }

        tvRecoveredTotal.text = "Recuperati: ${formatSize(totalRecovered)}"
    }

    /** Confronta i file .shadow sul telefono con quelli sul Raspberry e mostra gli orfani */
    private fun checkOrphans() {
        btnSync.isEnabled = false
        btnSync.text = "🔄 Controllo..."
        containerOrphans.removeAllViews()
        btnDeleteOrphans.visibility = View.GONE

        // Raccoglie tutti i relPath dei file ghostati sul telefono
        val root = File(Environment.getExternalStorageDirectory().absolutePath)
        val phoneFiles = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".shadow") }
            .map { shadowFile ->
                shadowFile.absolutePath
                    .removePrefix(root.absolutePath)
                    .trimStart('/')
                    .removeSuffix(".shadow")
            }
            .toList()

        ShadowClient.syncIndex(this, phoneFiles) { orphans ->
            runOnUiThread {
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
    }

    /** Elimina tutti gli orfani dal Raspberry uno per uno */
    private fun deleteAllOrphans() {
        btnDeleteOrphans.isEnabled = false
        var deleted = 0
        var failed = 0
        val total = currentOrphans.size

        fun deleteNext(index: Int) {
            if (index >= total) {
                runOnUiThread {
                    Toast.makeText(this, "✅ Eliminati $deleted/$total file dal Raspberry", Toast.LENGTH_LONG).show()
                    btnDeleteOrphans.isEnabled = true
                    checkOrphans() // aggiorna la lista
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
