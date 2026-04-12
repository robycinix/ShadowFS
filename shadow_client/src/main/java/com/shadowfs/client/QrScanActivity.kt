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

        supportActionBar?.title = "Scansiona QR di Pairing"
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
            Toast.makeText(this, "Permesso fotocamera necessario", Toast.LENGTH_LONG).show()
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

        runOnUiThread { tvScanStatus.text = "🔄 QR rilevato — configurazione in corso..." }

        try {
            val json = JSONObject(raw)
            val ip      = json.getString("ip")
            val port    = json.getInt("port")
            val caB64   = json.getString("ca")
            val crtB64  = json.getString("crt")
            val keyB64  = json.getString("key")

            // Salva i certificati nella cartella attesa da ShadowClient
            val certsDir = File(ShadowClient.CERTS_DIR).also { it.mkdirs() }
            File(certsDir, "ca.crt").writeBytes(Base64.decode(caB64, Base64.DEFAULT))
            File(certsDir, "client.crt").writeBytes(Base64.decode(crtB64, Base64.DEFAULT))
            File(certsDir, "client.key").writeBytes(Base64.decode(keyB64, Base64.DEFAULT))

            // Salva IP e porta
            ShadowClient.saveConfig(this, ip, port)

            runOnUiThread {
                tvScanStatus.text = "✅ Configurazione completata!"
                Toast.makeText(this, "✅ Pairing completato con $ip:$port", Toast.LENGTH_LONG).show()
            }

            // Torna alla MainActivity dopo 1.5s
            previewView.postDelayed({ finish() }, 1500)

        } catch (e: Exception) {
            alreadyProcessed = false
            runOnUiThread {
                tvScanStatus.text = "❌ QR non valido — riprova"
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
