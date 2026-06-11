package com.shadowfs.client

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.*
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    // Cartella legacy usata dal setup manuale via adb. Al primo uso i certificati
    // vengono copiati nello storage privato dell'app.
    const val CERTS_DIR = "/storage/emulated/0/Download/shadowfs_certs"
    private const val PRIVATE_CERTS_DIR = "shadowfs_certs"
    const val PREFS_NAME = "shadowfs_config"
    const val KEY_SERVER_IP = "server_ip"
    const val KEY_SERVER_PORT = "server_port"

    // Un singolo worker thread serializza tutte le operazioni di rete.
    // Rispetto a Semaphore(1) + Thread per chiamata, questo approccio evita
    // di creare N thread bloccati che consumano ~512 KB di stack ciascuno.
    // shutdownAndReset() permette al ForegroundService di liberare risorse
    // on onDestroy e ripartire pulito al prossimo onCreate.
    @Volatile private var networkExecutor: ExecutorService = newNetworkExecutor()

    private fun newNetworkExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "ShadowNetwork") }

    // CMD bytes
    private const val CMD_UPLOAD: Byte = 0x01
    private const val CMD_DOWNLOAD: Byte = 0x02
    private const val CMD_DELETE: Byte = 0x03
    private const val CMD_SYNC_INDEX: Byte = 0x04

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

    // ----------------------------------------------------------------
    // Onboarding — traccia il passo corrente del wizard al primo avvio
    // 0=notifiche, 1=storage, 2=batteria, 3=cloud warning, 4=done
    // ----------------------------------------------------------------

    private const val KEY_ONBOARDING_STEP = "onboarding_step"

    fun getOnboardingStep(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_ONBOARDING_STEP, 0)

    fun setOnboardingStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ONBOARDING_STEP, step)
            .apply()
    }

    // ----------------------------------------------------------------
    // Pinned folders — cartelle che non vengono mai ghostate
    // ----------------------------------------------------------------

    private const val KEY_PINNED_FOLDERS = "pinned_folders"

    /** Cartelle sempre protette (non modificabili dall'utente) */
    val DEFAULT_PINNED = setOf(
        "/storage/emulated/0/Download/shadowfs_certs",
        "/storage/emulated/0/Android"
    )

    fun getPinnedFolders(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userPinned = prefs.getStringSet(KEY_PINNED_FOLDERS, emptySet()) ?: emptySet()
        return DEFAULT_PINNED + userPinned
    }

    fun addPinnedFolder(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_PINNED_FOLDERS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        current.add(path)
        prefs.edit().putStringSet(KEY_PINNED_FOLDERS, current).apply()
    }

    fun removePinnedFolder(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_PINNED_FOLDERS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        current.remove(path)
        prefs.edit().putStringSet(KEY_PINNED_FOLDERS, current).apply()
    }

    fun getUserPinnedFolders(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_PINNED_FOLDERS, emptySet()) ?: emptySet()
    }

    // ----------------------------------------------------------------
    // File contesi — file che un'app cloud (Google Photos, Amazon Photos...)
    // continua a ripristinare sopra il ghost. Ghostarli di nuovo innescherebbe
    // una reazione a catena (ghost → restore → ghost → ...): vengono esclusi
    // definitivamente dal ghosting finché l'utente non risolve il conflitto
    // disattivando il backup cloud per quella cartella.
    // ----------------------------------------------------------------

    private const val KEY_CONTESTED_FILES = "contested_files"

    fun getContestedFiles(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_CONTESTED_FILES, emptySet()) ?: emptySet()
    }

    fun addContestedFile(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_CONTESTED_FILES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        current.add(path)
        prefs.edit().putStringSet(KEY_CONTESTED_FILES, current).apply()
    }

    fun clearContestedFiles(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_CONTESTED_FILES).apply()
    }

    /**
     * Interrompe il worker thread corrente e ne crea uno nuovo pulito.
     * Va chiamato da ForegroundService.onDestroy() per evitare che il worker
     * sopravviva alla morte del Service e tenga bloccato l'executor al restart.
     */
    fun shutdownAndReset() {
        networkExecutor.shutdownNow()
        networkExecutor = newNetworkExecutor()
    }

    fun getCertsDir(context: Context): File =
        File(context.filesDir, PRIVATE_CERTS_DIR)

    fun getCertsDisplayPath(context: Context): String =
        getCertsDir(context).absolutePath

    /**
     * Migra i certificati dalla vecchia cartella pubblica Download allo storage
     * privato dell'app. I file legacy vengono lasciati intatti per compatibilità:
     * l'utente può rimuoverli manualmente dopo aver verificato che il pairing funzioni.
     */
    fun migrateLegacyCerts(context: Context) {
        val privateDir = getCertsDir(context)
        if (hasCertTriplet(privateDir)) return

        val legacyDir = File(CERTS_DIR)
        if (!hasCertTriplet(legacyDir)) return

        privateDir.mkdirs()
        listOf("ca.crt", "client.crt", "client.key").forEach { name ->
            File(legacyDir, name).copyTo(File(privateDir, name), overwrite = true)
        }
        Log.i(TAG, "Certificati migrati nello storage privato: ${privateDir.absolutePath}")
    }

    /** Verifica che tutti e tre i file certificato esistano nello storage privato */
    fun areCertsPresent(context: Context): Boolean {
        migrateLegacyCerts(context)
        return hasCertTriplet(getCertsDir(context))
    }

    private fun hasCertTriplet(dir: File): Boolean {
        return File(dir, "ca.crt").exists() &&
               File(dir, "client.crt").exists() &&
               File(dir, "client.key").exists()
    }

    // ----------------------------------------------------------------
    // Upload: Android → Raspberry Pi (Ghosting)
    // ----------------------------------------------------------------

    /**
     * Invia [file] al daemon con supporto al **resume automatico**.
     *
     * Protocollo v3 (resume + verifica + ACK):
     *   Client → Server: [CMD_UPLOAD][2B path_len][path bytes][8B file_size][32B sha256]
     *   Server → Client: [8B resume_offset]   ← server indica da dove riprendere
     *   Client → Server: [file bytes da resume_offset...]
     *   Server → Client: [1B ack]             ← 0x01 = verificato e salvato, altro = errore
     *
     * CRITICO: il successo viene riportato SOLO dopo l'ACK del server. Senza ACK
     * il chiamante potrebbe ghostare (troncare) il file locale mentre il server
     * ha scartato l'upload per checksum mismatch → perdita dati irreversibile.
     *
     * Se un upload precedente era stato interrotto, il server restituisce la dimensione
     * del file parziale già presente su disco: il client salta quei byte e riprende
     * dall'offset ricevuto. Se il file è già completo o non esiste nessun parziale,
     * il server risponde 0 e il client invia l'intero file dall'inizio.
     *
     * [relPath] è il percorso relativo alla root monitorata
     * (es. "DCIM/Camera/video.mp4", NON il percorso assoluto Android).
     * Il callback viene eseguito in un thread di background.
     */
    fun upload(
        context: Context,
        file: File,
        relPath: String,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null,
        precomputedSha: ByteArray? = null,
        onResult: (success: Boolean, sha256Hex: String?) -> Unit
    ) {
        networkExecutor.submit {
            try {
                val ip = getServerIp(context)
                val port = getServerPort(context)
                if (ip.isEmpty()) {
                    Log.e(TAG, "IP server non configurato.")
                    onResult(false, null)
                    return@submit
                }

                val fileSize = file.length()
                // Il checksum può arrivare precomputato (es. dalla riconciliazione
                // dei ghost ripristinati) per evitare di hashare due volte file grandi.
                val checksum = precomputedSha ?: sha256Bytes(file)
                Log.i(TAG, "Upload → $ip:$port | $relPath ($fileSize bytes)")

                createSSLSocket(context, ip, port).use { ssl ->
                    val out = BufferedOutputStream(ssl.outputStream)
                    val dataOut = DataOutputStream(out)
                    val inp = DataInputStream(ssl.inputStream)
                    val pathBytes = relPath.toByteArray(Charsets.UTF_8)

                    // ── Fase 1: invia header (cmd + path + size + checksum) ────────────
                    dataOut.write(CMD_UPLOAD.toInt())
                    dataOut.write((pathBytes.size shr 8) and 0xFF)
                    dataOut.write(pathBytes.size and 0xFF)
                    dataOut.write(pathBytes)
                    dataOut.writeLong(fileSize)
                    dataOut.write(checksum)
                    dataOut.flush() // CRITICO: il server deve ricevere il path prima di rispondere

                    // ── Fase 2: leggi l'offset di ripresa dal server ───────────────────
                    // Il server risponde con 8 byte big-endian: quanti byte ha già su disco.
                    // 0 = inizio da capo; N = riprendi dall'offset N.
                    val resumeOffset = inp.readLong().coerceIn(0L, fileSize)

                    if (resumeOffset > 0) {
                        Log.i(TAG, "🔄 Resume upload da offset $resumeOffset/$fileSize bytes per $relPath")
                    }

                    // ── Fase 3: invia i byte del file a partire dall'offset ────────────
                    FileInputStream(file).use { fis ->
                        // Seek diretto tramite NIO channel: più affidabile di skip() per offset grandi
                        if (resumeOffset > 0L) {
                            fis.channel.position(resumeOffset)
                        }

                        val buffer = ByteArray(65536)
                        var transferred = resumeOffset // la progress parte dall'offset
                        var read: Int
                        while (fis.read(buffer).also { read = it } != -1) {
                            dataOut.write(buffer, 0, read)
                            transferred += read
                            onProgress?.invoke(transferred, fileSize)
                        }
                    }
                    dataOut.flush()

                    // ── Fase 4: attendi l'ACK di verifica dal server ───────────────────
                    // 0x01 = il server ha verificato il checksum e pubblicato il file.
                    // Qualsiasi altro valore (o EOF) = upload NON valido: il chiamante
                    // non deve ghostare il file locale.
                    val ack = inp.read()
                    if (ack != 1) {
                        throw IOException("Server ha rifiutato l'upload (ack=$ack) — file locale NON ghostato")
                    }
                }

                Log.i(TAG, "✅ Upload completato e verificato dal server: $relPath")
                onResult(true, checksum.joinToString("") { "%02x".format(it) })
            } catch (e: Exception) {
                Log.e(TAG, "❌ Upload fallito per '$relPath': ${e.message}", e)
                onResult(false, null)
            }
        }
    }

    // ----------------------------------------------------------------
    // Download: Raspberry Pi → Android (Hydration)
    // ----------------------------------------------------------------

    /**
     * Scarica il file identificato da [relPath] in modo atomico.
     *
     * Il contenuto viene scritto in [destFile].shadowdl.tmp, con resume automatico.
     * [destFile] viene sostituito solo dopo verifica di dimensione e SHA-256: se la
     * connessione cade a metà, il ghost locale resta intatto.
     */
    fun download(context: Context, relPath: String, destFile: File, onResult: (Boolean) -> Unit) {
        networkExecutor.submit {
            try {
                val ip = getServerIp(context)
                val port = getServerPort(context)
                if (ip.isEmpty()) {
                    Log.e(TAG, "IP server non configurato.")
                    onResult(false)
                    return@submit
                }

                Log.i(TAG, "Download ← $ip:$port | $relPath")

                val tmpFile = File(destFile.parentFile, destFile.name + ".shadowdl.tmp")
                val resumeOffset = if (tmpFile.exists()) tmpFile.length() else 0L
                if (resumeOffset > 0L) {
                    Log.i(TAG, "🔄 Resume download da offset $resumeOffset per $relPath")
                }

                createSSLSocket(context, ip, port).use { ssl ->
                    val out = BufferedOutputStream(ssl.outputStream)
                    val dataOut = DataOutputStream(out)
                    val inp = DataInputStream(ssl.inputStream)
                    val pathBytes = relPath.toByteArray(Charsets.UTF_8)

                    dataOut.write(CMD_DOWNLOAD.toInt())
                    dataOut.write((pathBytes.size shr 8) and 0xFF)
                    dataOut.write(pathBytes.size and 0xFF)
                    dataOut.write(pathBytes)
                    dataOut.writeLong(resumeOffset)
                    dataOut.flush()
                    // Non chiamiamo shutdownOutput: il server risponde subito dopo aver letto

                    val status = inp.read()
                    if (status == 1) {
                        val fileSize = inp.readLong()
                        if (fileSize < 0L) {
                            Log.e(TAG, "❌ Dimensione download non valida per $relPath: $fileSize")
                            tmpFile.delete()
                            onResult(false)
                            return@use
                        }
                        val expectedChecksum = ByteArray(32)
                        inp.readFully(expectedChecksum)

                        val safeOffset = if (resumeOffset <= fileSize) resumeOffset else 0L
                        if (safeOffset == 0L && tmpFile.exists()) {
                            tmpFile.delete()
                        }

                        FileOutputStream(tmpFile, safeOffset > 0L).buffered(65536).use { fos ->
                            copyExactly(inp, fos, fileSize - safeOffset)
                        }

                        if (tmpFile.length() != fileSize) {
                            Log.e(TAG, "❌ Download incompleto: ${tmpFile.length()} / $fileSize bytes per $relPath")
                            onResult(false)
                            return@use
                        }

                        val actualChecksum = sha256Bytes(tmpFile)
                        if (!actualChecksum.contentEquals(expectedChecksum)) {
                            Log.e(TAG, "❌ Checksum download non valido per $relPath")
                            tmpFile.delete()
                            onResult(false)
                            return@use
                        }

                        if (!replaceFile(tmpFile, destFile)) {
                            Log.e(TAG, "❌ Impossibile pubblicare download verificato per $relPath")
                            onResult(false)
                            return@use
                        }

                        Log.i(TAG, "✅ Download completato: $relPath (${destFile.length()} bytes)")
                        onResult(true)
                    } else {
                        Log.e(TAG, "❌ File non trovato sul daemon: $relPath")
                        tmpFile.delete()
                        onResult(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Download fallito per '$relPath': ${e.message}", e)
                onResult(false)
            }
        }
    }

    // ----------------------------------------------------------------
    // Delete: elimina il file dal Raspberry Pi e dal telefono
    // ----------------------------------------------------------------

    /**
     * Elimina [relPath] dal Raspberry Pi (file fisico + DB).
     * Poi elimina dal telefono: il file ghost e il marker .shadow.
     * Il callback viene eseguito in un thread di background.
     */
    fun delete(context: Context, relPath: String, localFile: File, onResult: (Boolean) -> Unit) {
        networkExecutor.submit {
            try {
                val ip = getServerIp(context)
                val port = getServerPort(context)
                if (ip.isEmpty()) { onResult(false); return@submit }

                Log.i(TAG, "Delete → $ip:$port | $relPath")

                val serverOk = createSSLSocket(context, ip, port).use { ssl ->
                    val out = ssl.outputStream
                    val pathBytes = relPath.toByteArray(Charsets.UTF_8)

                    out.write(CMD_DELETE.toInt())
                    out.write((pathBytes.size shr 8) and 0xFF)
                    out.write(pathBytes.size and 0xFF)
                    out.write(pathBytes)
                    out.flush()

                    ssl.inputStream.read() == 1
                }

                if (serverOk) {
                    // Elimina file ghost + marker .shadow dal telefono
                    localFile.delete()
                    File(localFile.parent, localFile.name + ".shadow").delete()
                    File(localFile.parent, localFile.name + ".reghost").delete()
                    Log.i(TAG, "✅ Delete completato: $relPath")
                    onResult(true)
                } else {
                    Log.e(TAG, "❌ Delete rifiutato dal server: $relPath")
                    onResult(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Delete fallito per '$relPath': ${e.message}", e)
                onResult(false)
            }
        }
    }

    // ----------------------------------------------------------------
    // SyncIndex: confronta i file sul telefono con quelli sul Raspberry
    // Ritorna la lista di relPath che esistono sul Raspberry ma non sul telefono (orfani)
    // ----------------------------------------------------------------

    fun syncIndex(context: Context, phoneFiles: List<String>, onResult: (List<String>) -> Unit) {
        networkExecutor.submit {
            try {
                val ip = getServerIp(context)
                val port = getServerPort(context)
                if (ip.isEmpty()) { onResult(emptyList()); return@submit }

                Log.i(TAG, "SyncIndex → $ip:$port | ${phoneFiles.size} file sul telefono")

                val orphans = mutableListOf<String>()

                createSSLSocket(context, ip, port).use { ssl ->
                    val out = ssl.outputStream
                    val inp = ssl.inputStream

                    // Invia comando + count (4 byte big-endian)
                    val count = phoneFiles.size
                    out.write(CMD_SYNC_INDEX.toInt())
                    out.write(count shr 24 and 0xFF)
                    out.write(count shr 16 and 0xFF)
                    out.write(count shr 8 and 0xFF)
                    out.write(count and 0xFF)

                    // Invia ogni path
                    for (path in phoneFiles) {
                        val pathBytes = path.toByteArray(Charsets.UTF_8)
                        out.write(pathBytes.size shr 8 and 0xFF)
                        out.write(pathBytes.size and 0xFF)
                        out.write(pathBytes)
                    }
                    out.flush()

                    // Legge count orfani (4 byte).
                    // CRITICO: inp.read() può restituire meno byte del richiesto su TCP.
                    // DataInputStream.readFully garantisce la lettura completa.
                    val din = DataInputStream(inp)
                    val orphanCount = din.readInt()

                    // Legge ogni path orfano
                    repeat(orphanCount) {
                        val pathLen = din.readUnsignedShort()
                        val pathBuf = ByteArray(pathLen)
                        din.readFully(pathBuf)
                        orphans.add(String(pathBuf, Charsets.UTF_8))
                    }
                }

                Log.i(TAG, "✅ SyncIndex completato: ${orphans.size} orfani trovati")
                onResult(orphans)
            } catch (e: Exception) {
                Log.e(TAG, "❌ SyncIndex fallito: ${e.message}", e)
                onResult(emptyList())
            }
        }
    }

    // ----------------------------------------------------------------
    // Test connessione (ping TCP senza protocollo)
    // ----------------------------------------------------------------

    fun testConnection(context: Context, onResult: (Boolean, String) -> Unit) {
        networkExecutor.submit {
            try {
                val ip = getServerIp(context)
                val port = getServerPort(context)
                if (ip.isEmpty()) { onResult(false, "IP non configurato"); return@submit }
                if (!areCertsPresent(context)) {
                    onResult(false, "Certificati mancanti in ${getCertsDisplayPath(context)}")
                    return@submit
                }

                createSSLSocket(context, ip, port).use { ssl ->
                    // Basta aprire la connessione TLS per verificare che funzioni
                    ssl.outputStream.flush()
                }
                onResult(true, "Connesso a $ip:$port ✓")
            } catch (e: Exception) {
                onResult(false, "Errore: ${e.message}")
            }
        }
    }

    // ----------------------------------------------------------------
    // Creazione socket SSL con mTLS
    // ----------------------------------------------------------------

    private fun createSSLSocket(context: Context, host: String, port: Int): SSLSocket {
        migrateLegacyCerts(context)
        val certDir = getCertsDir(context)

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
        val inMemoryKeyPassword = newInMemoryKeyPassword()

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("shadowfs-client", clientKey, inMemoryKeyPassword, arrayOf(clientCert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, inMemoryKeyPassword)
        }

        // SSLContext TLS 1.3
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, null)
        }

        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), 10_000) // timeout connessione: 10s
        socket.soTimeout = 120_000 // timeout lettura: 2 min

        // Verifica anche che l'IP/host usato per connettersi sia presente nei SAN
        // del certificato server. Se l'IP cambia, rigenera i certificati lato Raspberry.
        socket.sslParameters = socket.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
        }

        return socket
    }

    /** SHA-256 di un file, a chunk da 64 KB (non satura la RAM su file grandi).
     *  Pubblico: usato anche da ForegroundService per la riconciliazione dei
     *  ghost ripristinati dalle app cloud. */
    fun sha256Bytes(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun newInMemoryKeyPassword(): CharArray {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP).toCharArray()
    }

    private fun copyExactly(input: InputStream, output: OutputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(65536)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) {
                throw EOFException("Stream chiuso con $remaining byte ancora attesi")
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun replaceFile(source: File, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        if (source.renameTo(dest)) return true
        if (dest.exists() && !dest.delete()) return false
        return source.renameTo(dest)
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
