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

class GhostListActivity : AppCompatActivity() {

    private lateinit var containerGhostList: LinearLayout
    private lateinit var tvRecoveredTotal: TextView
    private lateinit var tvGhostCount: TextView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ghost_list)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "File Ghostati"
        }

        containerGhostList = findViewById(R.id.container_ghost_list)
        tvRecoveredTotal    = findViewById(R.id.tv_recovered_total)
        tvGhostCount        = findViewById(R.id.tv_ghost_count)
        tvEmpty             = findViewById(R.id.tv_empty)

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

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }
}
