package com.shadowfs.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvScanStatus: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var alreadyProcessed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)

        supportActionBar?.title = getString(R.string.qr_scan_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        previewView  = findViewById(R.id.preview_view)
        tvScanStatus = findViewById(R.id.tv_scan_status)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalyzer
            )
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (alreadyProcessed) { imageProxy.close(); return }

        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        BarcodeScanning.getClient().process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                    ?.rawValue
                    ?.let { processQrPayload(it) }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun processQrPayload(raw: String) {
        if (alreadyProcessed) return
        alreadyProcessed = true

        runOnUiThread { tvScanStatus.setText(R.string.qr_status_detected) }

        // Esegui il parsing in background (può richiedere una chiamata HTTP)
        cameraExecutor.execute {
            try {
                val jsonStr = if (raw.startsWith("http://") || raw.startsWith("https://")) {
                    // URL-based QR: valida che sia un IP locale/Tailscale prima di aprire
                    val url = URL(raw)
                    val host = url.host
                    if (!isLocalOrTailscaleHost(host)) {
                        throw SecurityException(getString(R.string.qr_invalid_url, host))
                    }
                    runOnUiThread { tvScanStatus.setText(R.string.qr_downloading_config) }
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 8_000
                    conn.requestMethod = "GET"
                    try {
                        conn.connect()
                        if (conn.responseCode != 200) {
                            throw Exception(getString(R.string.qr_server_error, conn.responseCode))
                        }
                        conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    } finally {
                        conn.disconnect()
                    }
                } else {
                    raw // JSON diretto nel QR (legacy)
                }

                applyPairingPayload(jsonStr)

            } catch (e: Exception) {
                alreadyProcessed = false
                runOnUiThread {
                    tvScanStatus.text = "❌ ${e.message}"
                }
            }
        }
    }

    private fun applyPairingPayload(jsonStr: String) {
        val json   = JSONObject(jsonStr)
        val ip     = json.getString("ip")
        val port   = json.getInt("port")
        val caB64  = json.getString("ca")
        val crtB64 = json.getString("crt")
        val keyB64 = json.getString("key")

        // Salva i certificati in modo atomico: scrivi su tmp, poi rinomina.
        // Evita che un crash a metà lasci certificati corrotti/incompleti.
        val certsDir = ShadowClient.getCertsDir(this).also { it.mkdirs() }
        val tmpCa  = File(certsDir, "ca.crt.tmp")
        val tmpCrt = File(certsDir, "client.crt.tmp")
        val tmpKey = File(certsDir, "client.key.tmp")
        try {
            tmpCa.writeBytes(Base64.decode(caB64, Base64.DEFAULT))
            tmpCrt.writeBytes(Base64.decode(crtB64, Base64.DEFAULT))
            tmpKey.writeBytes(Base64.decode(keyB64, Base64.DEFAULT))
            // Rinomina atomica: solo se tutti e tre i write hanno avuto successo
            tmpCa.renameTo(File(certsDir, "ca.crt"))
            tmpCrt.renameTo(File(certsDir, "client.crt"))
            tmpKey.renameTo(File(certsDir, "client.key"))
        } catch (e: Exception) {
            tmpCa.delete(); tmpCrt.delete(); tmpKey.delete()
            throw e
        }

        // Salva IP e porta
        ShadowClient.saveConfig(this, ip, port)

        runOnUiThread {
            tvScanStatus.setText(R.string.qr_config_complete)
            Toast.makeText(this, getString(R.string.qr_pairing_complete, ip, port), Toast.LENGTH_LONG).show()
        }

        // Torna alla MainActivity dopo 1.5s
        previewView.postDelayed({ finish() }, 1500)
    }

    /**
     * Verifica che l'host sia un IP locale o Tailscale, non un server pubblico.
     * IP ammessi: 192.168.x.x, 10.x.x.x, 172.16-31.x.x, 127.x.x.x
     * Tailscale: 100.64.0.0/10 → da 100.64.x.x a 100.127.x.x
     *   (il range 100.0-63.x è CGNAT pubblico e NON è accettato)
     */
    private fun isLocalOrTailscaleHost(host: String): Boolean {
        if (host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            host.startsWith("127.") ||
            host == "localhost" ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)) {
            return true
        }
        // Tailscale usa 100.64.0.0/10: secondo ottetto 64–127
        val tailscaleMatch = Regex("""^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.""").containsMatchIn(host)
        return tailscaleMatch
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
