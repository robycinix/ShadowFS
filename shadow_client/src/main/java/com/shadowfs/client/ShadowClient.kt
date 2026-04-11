package com.shadowfs.client

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.*
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.*

/**
 * ShadowClient — client TLS+mTLS reale per comunicare con il ShadowDaemon sul Raspberry Pi.
 *
 * Utilizza SSLSocket standard di Android (nessuna libreria esterna necessaria).
 * I certificati (PKCS#8 PEM) vengono letti dalla cartella:
 *   /storage/emulated/0/Download/shadowfs_certs/
 *
 * Protocollo binario:
 *   Upload:   [0x01][2B path_len][path bytes][file bytes...]  → EOF
 *   Download: [0x02][2B path_len][path bytes]  ← [0x01][file bytes...] oppure [0x00] (not found)
 */
object ShadowClient {

    private const val TAG = "ShadowClient"

    // Cartella certificati sull'Android (utente la crea manualmente)
    const val CERTS_DIR = "/storage/emulated/0/Download/shadowfs_certs"
    const val PREFS_NAME = "shadowfs_config"
    const val KEY_SERVER_IP = "server_ip"
    const val KEY_SERVER_PORT = "server_port"

    // CMD bytes
    private const val CMD_UPLOAD: Byte = 0x01
    private const val CMD_DOWNLOAD: Byte = 0x02

    // ----------------------------------------------------------------
    // Configurazione
    // ----------------------------------------------------------------

    fun getServerIp(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_IP, "") ?: ""

    fun getServerPort(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SERVER_PORT, 4243)

    fun saveConfig(context: Context, ip: String, port: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVER_IP, ip)
            .putInt(KEY_SERVER_PORT, port)
            .apply()
        Log.i(TAG, "Configurazione salvata: $ip:$port")
    }

    fun isConfigured(context: Context): Boolean = getServerIp(context).isNotEmpty()

    /** Verifica che tutti e tre i file certificato esistano */
    fun areCertsPresent(): Boolean {
        val dir = File(CERTS_DIR)
        return File(dir, "ca.crt").exists() &&
               File(dir, "client.crt").exists() &&
               File(dir, "client.key").exists()
    }

    // ----------------------------------------------------------------
    // Upload: Android → Raspberry Pi (Ghosting)
    // ----------------------------------------------------------------

    /**
     * Invia [file] al daemon. [relPath] è il percorso relativo alla root monitorata
     * (es. "DCIM/Camera/video.mp4", NON il percorso assoluto Android).
     * Il callback viene eseguito in un thread di background.
     */
    fun upload(context: Context, file: File, relPath: String, onResult: (Boolean) -> Unit) {
        Thread(Thread.currentThread().threadGroup, {
            try {
                val ip = getServerIp(context)
                val port = getServerPort(context)
                if (ip.isEmpty()) {
                    Log.e(TAG, "IP server non configurato.")
                    onResult(false)
                    return@Thread
                }

                Log.i(TAG, "Upload → $ip:$port | $relPath (${file.length()} bytes)")

                createSSLSocket(ip, port).use { ssl ->
                    val out = BufferedOutputStream(ssl.outputStream)
                    val pathBytes = relPath.toByteArray(Charsets.UTF_8)

                    out.write(CMD_UPLOAD.toInt())                          // comando
                    out.write((pathBytes.size shr 8) and 0xFF)             // path len HIGH
                    out.write(pathBytes.size and 0xFF)                     // path len LOW
                    out.write(pathBytes)                                   // path

                    FileInputStream(file).buffered(65536).use { fis ->     // file content
                        fis.copyTo(out, bufferSize = 65536)
                    }
                    out.flush()
                    // La chiusura del socket (via use{}) invia close_notify TLS → il server vede EOF
                }

                Log.i(TAG, "✅ Upload completato: $relPath")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Upload fallito per '$relPath': ${e.message}", e)
                onResult(false)
            }
        }, "ShadowUpload-${file.name}").start()
    }

    // ----------------------------------------------------------------
    // Download: Raspberry Pi → Android (Hydration)
    // ----------------------------------------------------------------

    /**
     * Scarica il file identificato da [relPath] e lo scrive in [destFile].
     * Il file destinazione viene sovrascritto se esiste.
     */
    fun download(context: Context, relPath: String, destFile: File, onResult: (Boolean) -> Unit) {
        Thread(Thread.currentThread().threadGroup, {
            try {
                val ip = getServerIp(context)
                val port = getServerPort(context)
                if (ip.isEmpty()) {
                    Log.e(TAG, "IP server non configurato.")
                    onResult(false)
                    return@Thread
                }

                Log.i(TAG, "Download ← $ip:$port | $relPath")

                createSSLSocket(ip, port).use { ssl ->
                    val out = BufferedOutputStream(ssl.outputStream)
                    val inp = ssl.inputStream
                    val pathBytes = relPath.toByteArray(Charsets.UTF_8)

                    out.write(CMD_DOWNLOAD.toInt())
                    out.write((pathBytes.size shr 8) and 0xFF)
                    out.write(pathBytes.size and 0xFF)
                    out.write(pathBytes)
                    out.flush()
                    // Non chiamiamo shutdownOutput: il server risponde subito dopo aver letto

                    val status = inp.read()
                    if (status == 1) {
                        FileOutputStream(destFile).buffered(65536).use { fos ->
                            inp.copyTo(fos, bufferSize = 65536)
                        }
                        Log.i(TAG, "✅ Download completato: $relPath (${destFile.length()} bytes)")
                        onResult(true)
                    } else {
                        Log.e(TAG, "❌ File non trovato sul daemon: $relPath")
                        onResult(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Download fallito per '$relPath': ${e.message}", e)
                onResult(false)
            }
        }, "ShadowDownload-$relPath").start()
    }

    // ----------------------------------------------------------------
    // Test connessione (ping TCP senza protocollo)
    // ----------------------------------------------------------------

    fun testConnection(context: Context, onResult: (Boolean, String) -> Unit) {
        Thread {
            try {
                val ip = getServerIp(context)
                val port = getServerPort(context)
                if (ip.isEmpty()) { onResult(false, "IP non configurato"); return@Thread }
                if (!areCertsPresent()) { onResult(false, "Certificati mancanti in $CERTS_DIR"); return@Thread }

                createSSLSocket(ip, port).use { ssl ->
                    // Basta aprire la connessione TLS per verificare che funzioni
                    ssl.outputStream.flush()
                }
                onResult(true, "Connesso a $ip:$port ✓")
            } catch (e: Exception) {
                onResult(false, "Errore: ${e.message}")
            }
        }.start()
    }

    // ----------------------------------------------------------------
    // Creazione socket SSL con mTLS
    // ----------------------------------------------------------------

    private fun createSSLSocket(host: String, port: Int): SSLSocket {
        val certDir = File(CERTS_DIR)

        // TrustManager: verifica che il server usi un certificato firmato dalla nostra CA
        val caCert = CertificateFactory.getInstance("X.509")
            .generateCertificate(FileInputStream(File(certDir, "ca.crt")))
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("shadowfs-ca", caCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }

        // KeyManager: identità del client (certificato + chiave privata PKCS#8)
        val clientCert = CertificateFactory.getInstance("X.509")
            .generateCertificate(FileInputStream(File(certDir, "client.crt")))
        val clientKey = loadPkcs8PrivateKey(File(certDir, "client.key"))

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("shadowfs-client", clientKey, "shadowfs".toCharArray(), arrayOf(clientCert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "shadowfs".toCharArray())
        }

        // SSLContext TLS 1.3
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, null)
        }

        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), 10_000) // timeout connessione: 10s
        socket.soTimeout = 120_000 // timeout lettura: 2 min

        // Disabilita endpoint identification: il cert server usa "shadowdaemon" come CN,
        // ma noi ci connettiamo tramite IP (Tailscale o LAN). La sicurezza è garantita
        // dalla verifica della catena CA (il server DEVE avere il nostro ca.crt).
        socket.sslParameters = socket.sslParameters.apply {
            endpointIdentificationAlgorithm = ""
        }

        return socket
    }

    /**
     * Carica una chiave privata RSA in formato PKCS#8 PEM.
     * Compatibile con le chiavi generate da certs.go (MarshalPKCS8PrivateKey).
     * Non richiede BouncyCastle.
     */
    private fun loadPkcs8PrivateKey(keyFile: File): PrivateKey {
        val pem = keyFile.readText()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\r\n", "")
            .replace("\n", "")
            .trim()
        val keyBytes = Base64.decode(pem, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }
}
