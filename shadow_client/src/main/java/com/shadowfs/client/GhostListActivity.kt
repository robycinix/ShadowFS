package com.shadowfs.client

import android.content.res.ColorStateList
import android.media.MediaScannerConnection
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File

class GhostListActivity : AppCompatActivity() {

    private lateinit var containerGhostList: LinearLayout
    private lateinit var tvRecoveredTotal: TextView
    private lateinit var tvGhostCount: TextView
    private lateinit var emptyState: View
    private lateinit var btnSync: Button
    private lateinit var containerOrphans: LinearLayout
    private lateinit var btnDeleteOrphans: Button
    private var currentOrphans: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ghost_list)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        containerGhostList = findViewById(R.id.container_ghost_list)
        tvRecoveredTotal   = findViewById(R.id.tv_recovered_total)
        tvGhostCount       = findViewById(R.id.tv_ghost_count)
        emptyState         = findViewById(R.id.empty_state)
        btnSync            = findViewById(R.id.btn_sync)
        containerOrphans   = findViewById(R.id.container_orphans)
        btnDeleteOrphans   = findViewById(R.id.btn_delete_orphans)

        btnSync.setOnClickListener { checkOrphans() }

        btnDeleteOrphans.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_orphans_title)
                .setMessage(getString(R.string.delete_orphans_message, currentOrphans.size))
                .setPositiveButton(R.string.button_delete_all) { _, _ -> deleteAllOrphans() }
                .setNegativeButton(R.string.button_cancel, null)
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
        tvGhostCount.setText(R.string.ghost_count_loading)
        emptyState.visibility = View.GONE
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
            emptyState.visibility = View.VISIBLE
            containerGhostList.visibility = View.GONE
            tvRecoveredTotal.text = ""
            tvGhostCount.setText(R.string.ghost_count_empty)
            return
        }

        emptyState.visibility = View.GONE
        containerGhostList.visibility = View.VISIBLE
        tvGhostCount.text = getString(R.string.ghost_count_value, entries.size)

        val totalRecovered = entries.sumOf { it.originalSize }

        entries.forEach { entry ->
            val card = layoutInflater.inflate(R.layout.item_ghost, containerGhostList, false)
            val imgThumb   = card.findViewById<ImageView>(R.id.img_thumb)
            val tvName     = card.findViewById<TextView>(R.id.tv_name)
            val tvDetails  = card.findViewById<TextView>(R.id.tv_details)
            val btnRestore = card.findViewById<MaterialButton>(R.id.btn_restore)
            val btnDelete  = card.findViewById<MaterialButton>(R.id.btn_delete)

            tvName.text = entry.originalName
            tvDetails.text = formatSize(entry.originalSize)

            // ── Thumbnail ───────────────────────────────────────────────────
            // Il file fisico ghost esiste ancora su disco (IS_PENDING non lo elimina),
            // quindi BitmapFactory può leggere il thumbnail JPEG direttamente.
            showThumbPlaceholder(imgThumb)
            if (entry.hasThumbnail) {
                Thread {
                    val bmp = runCatching {
                        BitmapFactory.decodeFile(entry.localFile.absolutePath)
                    }.getOrNull()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && bmp != null) {
                            imgThumb.imageTintList = null
                            imgThumb.scaleType = ImageView.ScaleType.CENTER_CROP
                            imgThumb.setPadding(0, 0, 0, 0)
                            imgThumb.setImageBitmap(bmp)
                        }
                    }
                }.start()
            }

            btnDelete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_file_title)
                    .setMessage(getString(R.string.delete_file_message, entry.originalName))
                    .setPositiveButton(R.string.button_delete) { _, _ ->
                        btnDelete.isEnabled = false
                        ShadowClient.delete(this, entry.relPath, entry.localFile) { success ->
                            runOnUiThread {
                                if (success) {
                                    Toast.makeText(this, getString(R.string.toast_file_deleted, entry.originalName), Toast.LENGTH_SHORT).show()
                                    loadGhostFiles()
                                } else {
                                    Toast.makeText(this, R.string.toast_delete_error, Toast.LENGTH_LONG).show()
                                    btnDelete.isEnabled = true
                                }
                            }
                        }
                    }
                    .setNegativeButton(R.string.button_cancel, null)
                    .show()
            }

            btnRestore.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(R.string.restore_file_title)
                    .setMessage(getString(R.string.restore_file_message, entry.originalName))
                    .setPositiveButton(R.string.button_restore) { _, _ ->
                        restoreGhost(entry, btnRestore)
                    }
                    .setNegativeButton(R.string.button_cancel, null)
                    .show()
            }

            containerGhostList.addView(card)
        }

        tvRecoveredTotal.text = getString(R.string.recovered_total, formatSize(totalRecovered))
    }

    /** Icona generica al posto del thumbnail mancante. */
    private fun showThumbPlaceholder(img: ImageView) {
        val pad = (14 * resources.displayMetrics.density).toInt()
        img.scaleType = ImageView.ScaleType.CENTER_INSIDE
        img.setPadding(pad, pad, pad, pad)
        img.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_disabled))
        img.setImageResource(R.drawable.ic_image)
    }

    private fun restoreGhost(entry: GhostEntry, button: Button) {
        button.isEnabled = false
        button.alpha = 0.4f
        ShadowClient.download(this, entry.relPath, entry.localFile) { success ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (success) {
                    // Checksum dal .shadow prima di cancellarlo → nel .reghost:
                    // il re-ghosting salterà l'upload se il contenuto è invariato.
                    val checksum = try {
                        entry.shadowFile.readLines()
                            .firstOrNull { it.startsWith("checksum=") }
                            ?.removePrefix("checksum=")
                    } catch (_: Exception) { null }
                    entry.shadowFile.delete()
                    File(entry.localFile.parentFile, entry.localFile.name + ".reghost")
                        .writeText(buildString {
                            append(System.currentTimeMillis().toString())
                            if (checksum != null) append("\nchecksum=$checksum")
                        })
                    VfsManager.setIsPending(this, entry.localFile, pending = false)
                    MediaScannerConnection.scanFile(
                        this, arrayOf(entry.localFile.absolutePath), null, null
                    )
                    Toast.makeText(this, getString(R.string.toast_file_restored, entry.originalName), Toast.LENGTH_SHORT).show()
                    loadGhostFiles()
                } else {
                    Toast.makeText(this, R.string.toast_restore_failed, Toast.LENGTH_LONG).show()
                    button.alpha = 1f
                    button.isEnabled = true
                }
            }
        }
    }

    /** Confronta i file .shadow sul telefono con quelli sul Raspberry e mostra gli orfani.
     *  La scansione avviene in background per non bloccare il main thread. */
    private fun checkOrphans() {
        btnSync.isEnabled = false
        btnSync.setText(R.string.button_checking)
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
                    btnSync.setText(R.string.button_check)
                    currentOrphans = orphans

                    if (orphans.isEmpty()) {
                        val tv = TextView(this).apply {
                            text = getString(R.string.orphans_none)
                            textSize = 13f
                            setTextColor(ContextCompat.getColor(this@GhostListActivity, R.color.success))
                            setPadding(8, 12, 8, 4)
                        }
                        containerOrphans.addView(tv)
                    } else {
                        val header = TextView(this).apply {
                            text = getString(R.string.orphans_found_header, orphans.size)
                            textSize = 12f
                            setTextColor(ContextCompat.getColor(this@GhostListActivity, R.color.danger))
                            setPadding(8, 12, 8, 8)
                        }
                        containerOrphans.addView(header)
                        orphans.forEach { relPath ->
                            val tv = TextView(this).apply {
                                text = relPath.substringAfterLast('/')
                                textSize = 12f
                                setTextColor(ContextCompat.getColor(this@GhostListActivity, R.color.text_secondary))
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
                        getString(R.string.delete_orphans_success, deleted, total)
                    else
                        getString(R.string.delete_orphans_partial, deleted, total, failed)
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
