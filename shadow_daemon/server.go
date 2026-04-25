package main

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"database/sql"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/google/uuid"
	"github.com/quic-go/quic-go"
)

const (
	ChunkSize       = 4 * 1024 * 1024 // 4 MB
	MaxConnections  = 50              // max connessioni TCP simultanee
	MinFreeBytesGB  = 1              // ghosting rifiutato se Raspberry ha meno di 1 GB libero
)

var activeConnections int32 // contatore atomico connessioni attive

// ============================================================
// SERVER TCP+TLS (usato dal client Android via SSLSocket)
// ============================================================

// StartTCPServer avvia un server TCP+mTLS su addr (es. "0.0.0.0:4243")
func StartTCPServer(addr, certFile, keyFile, caFile string, db *sql.DB, storagePath string) error {
	tlsConf, err := buildTLSConfig(certFile, keyFile, caFile, false)
	if err != nil {
		return fmt.Errorf("TCP TLS Config Error: %v", err)
	}

	listener, err := tls.Listen("tcp", addr, tlsConf)
	if err != nil {
		return fmt.Errorf("TCP Listen Error: %v", err)
	}
	defer listener.Close()

	log.Printf("🔌 TCP+mTLS Server in ascolto su %s", addr)

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("TCP accept error: %v", err)
			continue
		}
		// Rate limiting: max connessioni simultanee
		if atomic.LoadInt32(&activeConnections) >= MaxConnections {
			log.Printf("⚠️ Troppe connessioni (%d/%d) — rifiutata connessione da %s",
				atomic.LoadInt32(&activeConnections), MaxConnections, conn.RemoteAddr())
			conn.Close()
			continue
		}
		atomic.AddInt32(&activeConnections, 1)
		go func(c net.Conn) {
			defer atomic.AddInt32(&activeConnections, -1)
			handleTCPConnection(c, db, storagePath)
		}(conn)
	}
}

func handleTCPConnection(conn net.Conn, db *sql.DB, storagePath string) {
	defer conn.Close()

	deviceID := getDeviceID(conn)
	log.Printf("🔒 [TCP] Nuova connessione mTLS da: %s (device: %s)", conn.RemoteAddr(), deviceID)

	// Ogni dispositivo ha la sua sottocartella di storage isolata
	deviceStorage := filepath.Join(storagePath, deviceID)
	if err := os.MkdirAll(deviceStorage, 0755); err != nil {
		log.Printf("[TCP] Errore creazione storage per device '%s': %v", deviceID, err)
		return
	}

	cmdBuf := make([]byte, 1)
	if _, err := io.ReadFull(conn, cmdBuf); err != nil {
		if err != io.EOF && err != io.ErrUnexpectedEOF {
			log.Printf("[TCP] Errore lettura cmd: %v", err)
		}
		// EOF immediato = test connessione dall'app (verifica mTLS senza inviare comandi)
		return
	}

	switch cmdBuf[0] {
	case 1:
		handleUpload(conn, db, deviceStorage, deviceID)
	case 2:
		handleDownload(conn, db, deviceStorage, deviceID)
	case 3:
		handleDelete(conn, db, deviceStorage, deviceID)
	case 4:
		handleSyncIndex(conn, db, deviceID)
	default:
		log.Printf("[TCP] ⚠️ Comando sconosciuto: %d", cmdBuf[0])
	}
}

// getDeviceID estrae il CN dal certificato TLS client per identificare il dispositivo.
// NOTA: tls.Accept() NON completa l'handshake automaticamente — avviene alla prima Read().
// Chiamiamo Handshake() esplicitamente per garantire che PeerCertificates sia popolato
// prima di qualsiasi altra operazione. Senza questo, tutti i device risultano "default".
func getDeviceID(conn net.Conn) string {
	tlsConn, ok := conn.(*tls.Conn)
	if !ok {
		return "default"
	}
	if err := tlsConn.Handshake(); err != nil {
		log.Printf("⚠️ [TLS] Handshake fallito: %v", err)
		return "default"
	}
	state := tlsConn.ConnectionState()
	if len(state.PeerCertificates) == 0 {
		return "default"
	}
	return sanitizeCN(state.PeerCertificates[0].Subject.CommonName)
}

// sanitizeCN applica una whitelist al CN del certificato per renderlo sicuro come componente di path.
// Solo caratteri alfanumerici, '-' e '_' sono ammessi; tutto il resto diventa '_'.
func sanitizeCN(cn string) string {
	if cn == "" {
		return "default"
	}
	var b strings.Builder
	for _, r := range cn {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') ||
			(r >= '0' && r <= '9') || r == '-' || r == '_' {
			b.WriteRune(r)
		} else {
			b.WriteRune('_')
		}
	}
	if b.Len() == 0 {
		return "default"
	}
	return b.String()
}

// ============================================================
// SERVER QUIC SPERIMENTALE (per uso futuro / test desktop)
// ============================================================

// StartServer avvia il server QUIC+mTLS sperimentale.
// Non è avviato dal main e non implementa ancora tutti i comandi TCP.
func StartServer(addr, certFile, keyFile, caFile string, db *sql.DB, storagePath string) error {
	tlsConf, err := buildTLSConfig(certFile, keyFile, caFile, true)
	if err != nil {
		return fmt.Errorf("QUIC TLS Config Error: %v", err)
	}

	listener, err := quic.ListenAddr(addr, tlsConf, nil)
	if err != nil {
		return err
	}

	log.Printf("🚀 QUIC+mTLS Server in ascolto su %s (UDP)", addr)

	for {
		conn, err := listener.Accept(context.Background())
		if err != nil {
			log.Printf("Errore accettazione connessione QUIC: %v", err)
			continue
		}
		go handleQUICConnection(conn, db, storagePath)
	}
}

func handleQUICConnection(conn quic.Connection, db *sql.DB, storagePath string) {
	log.Printf("🔒 [QUIC] Nuova connessione mTLS da: %s", conn.RemoteAddr())
	for {
		stream, err := conn.AcceptStream(context.Background())
		if err != nil {
			log.Printf("[QUIC] Stream chiuso per %s: %v", conn.RemoteAddr(), err)
			return
		}
		go handleStream(stream, db, storagePath)
	}
}

func handleStream(stream quic.Stream, db *sql.DB, storagePath string) {
	defer stream.Close()

	cmdBuf := make([]byte, 1)
	if _, err := io.ReadFull(stream, cmdBuf); err != nil {
		log.Printf("[QUIC] Read err: %v", err)
		return
	}

	switch cmdBuf[0] {
	case 1:
		handleUpload(stream, db, storagePath, "quic-client")
	case 2:
		handleDownload(stream, db, storagePath, "quic-client")
	default:
		log.Printf("[QUIC] ⚠️ Comando sconosciuto: %d", cmdBuf[0])
	}
}

// ============================================================
// HANDLERS CONDIVISI (funzionano sia con TCP net.Conn che QUIC Stream)
// ============================================================

// handleUpload: riceve un file da Android, lo salva sul disco e aggiorna il DB.
//
// Protocollo resume (v2):
//   Android → Raspberry: [2B path_len][path bytes][8B file_size][32B sha256]
//   Raspberry → Android: [8B resume_offset]   ← server dice da dove riprendere
//   Android → Raspberry: [file bytes da resume_offset...]
//
// Il server determina l'offset in autonomia:
//   - 0 se non esiste nessun parziale valido su disco
//   - N se esiste un file .part da un upload precedentemente interrotto
//
// Il file viene scritto in <path>.part, verificato con SHA-256 e rinominato sul
// path finale solo dopo una ricezione completa. Così un download non vede mai
// un file parzialmente caricato.
func handleUpload(rw io.ReadWriter, db *sql.DB, storagePath, deviceID string) {
	log.Println("[Upload] Ricezione file in corso...")

	// 1. Legge lunghezza percorso relativo (2 bytes big-endian)
	pathLenBuf := make([]byte, 2)
	if _, err := io.ReadFull(rw, pathLenBuf); err != nil {
		log.Printf("[Upload] Errore lettura lunghezza path: %v", err)
		return
	}
	pathLen := int(pathLenBuf[0])<<8 | int(pathLenBuf[1])
	if pathLen == 0 || pathLen > 4096 {
		log.Printf("[Upload] Path length non valido: %d", pathLen)
		return
	}

	// 2. Legge percorso relativo (es. "DCIM/Camera/foto.jpg")
	relPathBuf := make([]byte, pathLen)
	if _, err := io.ReadFull(rw, relPathBuf); err != nil {
		log.Printf("[Upload] Errore lettura path: %v", err)
		return
	}
	relPath := string(relPathBuf)

	// 3. Legge dimensione attesa e checksum dell'intero file.
	expectedSize, err := readInt64BE(rw)
	if err != nil {
		log.Printf("[Upload] Errore lettura dimensione attesa per '%s': %v", relPath, err)
		return
	}
	if expectedSize < 0 {
		log.Printf("[Upload] Dimensione negativa non valida per '%s': %d", relPath, expectedSize)
		return
	}
	expectedChecksumBuf := make([]byte, sha256.Size)
	if _, err := io.ReadFull(rw, expectedChecksumBuf); err != nil {
		log.Printf("[Upload] Errore lettura checksum atteso per '%s': %v", relPath, err)
		return
	}
	expectedChecksum := hex.EncodeToString(expectedChecksumBuf)

	// 4. Valida il percorso (anti path traversal) e calcola il path assoluto
	fullPath, err := validatePath(storagePath, relPath)
	if err != nil {
		log.Printf("[Upload] ⛔ Path non valido da %s: %v", deviceID, err)
		return
	}
	partPath := fullPath + ".part"

	// 4b. Controlla spazio libero sul disco (min 1 GB)
	if freeBytes, spaceErr := diskFreeBytes(storagePath); spaceErr == nil {
		if freeBytes < uint64(MinFreeBytesGB)*1024*1024*1024 {
			log.Printf("[Upload] ❌ Spazio insufficiente sul Raspberry (%d MB liberi)", freeBytes/1024/1024)
			return
		}
	}

	if err := os.MkdirAll(filepath.Dir(fullPath), 0755); err != nil {
		log.Printf("[Upload] Errore creazione directory per '%s': %v", fullPath, err)
		return
	}

	// 5. Determina l'offset di ripresa usando solo il file .part.
	var resumeOffset int64
	if info, statErr := os.Stat(partPath); statErr == nil {
		resumeOffset = info.Size()
		if resumeOffset > expectedSize {
			log.Printf("[Upload] File parziale più grande dell'atteso (%d > %d), riparto da zero: %s",
				resumeOffset, expectedSize, relPath)
			if err := os.Remove(partPath); err != nil {
				log.Printf("[Upload] Errore rimozione parziale non valido '%s': %v", partPath, err)
				return
			}
			resumeOffset = 0
		}
		log.Printf("[Upload] 🔄 File parziale trovato: %s (%d bytes) — invio offset al client", relPath, resumeOffset)
	}

	// 6. Invia l'offset di ripresa al client (8 byte big-endian)
	//    Il client salterà i primi resumeOffset byte prima di inviare i dati.
	if err := writeInt64BE(rw, resumeOffset); err != nil {
		log.Printf("[Upload] Errore invio resume offset: %v", err)
		return
	}

	// 7. Apre il file parziale alla posizione corretta
	var file *os.File
	if resumeOffset == 0 {
		file, err = os.Create(partPath)
	} else {
		file, err = os.OpenFile(partPath, os.O_WRONLY, 0644)
		if err == nil {
			if _, seekErr := file.Seek(resumeOffset, io.SeekStart); seekErr != nil {
				file.Close()
				log.Printf("[Upload] Errore seek a offset %d: %v", resumeOffset, seekErr)
				return
			}
			// Tronca eventuali byte corrotti oltre l'offset (protezione contro riprese spurie)
			if truncErr := file.Truncate(resumeOffset); truncErr != nil {
				file.Close()
				log.Printf("[Upload] Errore truncate a offset %d: %v", resumeOffset, truncErr)
				return
			}
		}
	}
	if err != nil {
		log.Printf("[Upload] Errore apertura file '%s': %v", fullPath, err)
		return
	}

	// 8. Trasferisce esattamente i byte mancanti. Se la connessione si interrompe,
	// mantiene il .part per un futuro resume.
	bytesToReceive := expectedSize - resumeOffset
	newBytes, copyErr := io.CopyN(file, rw, bytesToReceive)
	file.Close()

	if copyErr != nil {
		// NON rimuovere il file parziale: sarà il punto di ripresa al prossimo tentativo.
		log.Printf("[Upload] ❌ Connessione interrotta — file parziale mantenuto per resume (%d+%d bytes): %v",
			resumeOffset, newBytes, copyErr)
		return
	}

	totalBytes := resumeOffset + newBytes
	if totalBytes != expectedSize {
		log.Printf("[Upload] ❌ Dimensione finale non valida per %s: ricevuti %d, attesi %d",
			relPath, totalBytes, expectedSize)
		return
	}

	// 9. Calcola checksum SHA-256 dell'intero .part e confrontalo con quello
	// inviato dal client. Su mismatch il parziale viene eliminato: non è affidabile
	// per un resume successivo.
	checksum, err := calculateFileSHA256(partPath)
	if err != nil {
		log.Printf("[Upload] Errore checksum per '%s': %v", partPath, err)
		return
	}
	if checksum != expectedChecksum {
		log.Printf("[Upload] ❌ Checksum mismatch per %s: ottenuto %s, atteso %s",
			relPath, checksum, expectedChecksum)
		if rmErr := os.Remove(partPath); rmErr != nil {
			log.Printf("[Upload] Errore rimozione parziale corrotto '%s': %v", partPath, rmErr)
		}
		return
	}

	// 10. Pubblica il file finale solo dopo verifica completa.
	if err := replaceFileAtomically(partPath, fullPath); err != nil {
		log.Printf("[Upload] Errore rename atomico '%s' -> '%s': %v", partPath, fullPath, err)
		return
	}

	if resumeOffset > 0 {
		log.Printf("✅ [Upload] Resume completato: %s (%d+%d = %d bytes, sha256: %s...)",
			fullPath, resumeOffset, newBytes, totalBytes, checksum[:8])
	} else {
		log.Printf("✅ [Upload] File salvato: %s (%d bytes, sha256: %s...)", fullPath, totalBytes, checksum[:8])
	}

	// 11. Aggiorna il database — cerca UUID esistente per evitare duplicati
	existingFile, dbErr := GetFileByRelPath(db, deviceID, relPath)
	var fileUUID string
	if dbErr == nil {
		fileUUID = existingFile.UUID
	} else {
		fileUUID = uuid.New().String()
	}

	f := &FileData{
		UUID:         fileUUID,
		Filename:     filepath.Base(relPath),
		DeviceID:     deviceID,
		RelPath:      relPath,
		Size:         totalBytes,
		Status:       "FULL",
		Checksum:     checksum,
		LastModified: time.Now(),
		LastAccess:   time.Now(),
	}
	if updateErr := UpdateOrInsertFile(db, f); updateErr != nil {
		log.Printf("[Upload] ⚠️ Errore aggiornamento DB per '%s': %v", relPath, updateErr)
	} else {
		log.Printf("[Upload] 📦 DB aggiornato per '%s' (uuid: %s...)", relPath, fileUUID[:8])
	}
}

// readInt64BE legge un intero a 8 byte big-endian da r.
func readInt64BE(r io.Reader) (int64, error) {
	var b [8]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return int64(b[0])<<56 | int64(b[1])<<48 | int64(b[2])<<40 | int64(b[3])<<32 |
		int64(b[4])<<24 | int64(b[5])<<16 | int64(b[6])<<8 | int64(b[7]), nil
}

// writeInt64BE scrive v come intero a 8 byte big-endian su w.
func writeInt64BE(w io.Writer, v int64) error {
	b := [8]byte{
		byte(v >> 56), byte(v >> 48), byte(v >> 40), byte(v >> 32),
		byte(v >> 24), byte(v >> 16), byte(v >> 8), byte(v),
	}
	_, err := w.Write(b[:])
	return err
}

func calculateFileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()

	hash := sha256.New()
	if _, err := io.Copy(hash, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func replaceFileAtomically(partPath, finalPath string) error {
	if err := os.Rename(partPath, finalPath); err == nil {
		return nil
	}
	if err := os.Remove(finalPath); err != nil && !os.IsNotExist(err) {
		return err
	}
	return os.Rename(partPath, finalPath)
}

// handleDownload: invia un file fisico al client Android (Hydration).
//
// Protocollo v2:
//   Android → Raspberry: [2B path_len][path bytes][8B resume_offset]
//   Raspberry → Android: [1B status][8B file_size][32B sha256][file bytes da resume_offset...]
//
// Il client scarica su .tmp, verifica size/checksum e sostituisce il ghost solo
// dopo una ricezione completa.
func handleDownload(rw io.ReadWriter, db *sql.DB, storagePath, deviceID string) {
	log.Println("[Download] Richiesta file in corso...")

	// 1. Legge quale file vuole Android
	pathLenBuf := make([]byte, 2)
	if _, err := io.ReadFull(rw, pathLenBuf); err != nil {
		return
	}
	pathLen := int(pathLenBuf[0])<<8 | int(pathLenBuf[1])
	if pathLen == 0 || pathLen > 4096 {
		log.Printf("[Download] Path length non valido: %d", pathLen)
		return
	}

	relPathBuf := make([]byte, pathLen)
	if _, err := io.ReadFull(rw, relPathBuf); err != nil {
		return
	}
	relPath := string(relPathBuf)

	// 2. Legge l'offset di resume richiesto dal client.
	resumeOffset, err := readInt64BE(rw)
	if err != nil {
		log.Printf("[Download] Errore lettura resume offset per '%s': %v", relPath, err)
		rw.Write([]byte{0x00})
		return
	}
	if resumeOffset < 0 {
		log.Printf("[Download] Resume offset negativo non valido per '%s': %d", relPath, resumeOffset)
		rw.Write([]byte{0x00})
		return
	}

	// 3. Valida il percorso (anti path traversal) e cerca il file fisico
	fullPath, err := validatePath(storagePath, relPath)
	if err != nil {
		log.Printf("[Download] ⛔ Path non valido da %s: %v", deviceID, err)
		rw.Write([]byte{0x00})
		return
	}
	file, err := os.Open(fullPath)
	if err != nil {
		log.Printf("[Download] ❌ File non trovato: %s", fullPath)
		rw.Write([]byte{0x00}) // Status: NOT FOUND
		return
	}
	defer file.Close()

	info, err := file.Stat()
	if err != nil {
		log.Printf("[Download] ❌ Stat fallito: %s: %v", fullPath, err)
		rw.Write([]byte{0x00})
		return
	}
	fileSize := info.Size()
	if resumeOffset > fileSize {
		log.Printf("[Download] Offset oltre EOF per %s (%d > %d), riparto da zero",
			relPath, resumeOffset, fileSize)
		resumeOffset = 0
	}

	checksum, err := calculateFileSHA256(fullPath)
	if err != nil {
		log.Printf("[Download] ❌ Checksum fallito: %s: %v", fullPath, err)
		rw.Write([]byte{0x00})
		return
	}

	if _, err := file.Seek(resumeOffset, io.SeekStart); err != nil {
		log.Printf("[Download] ❌ Seek fallito per %s a %d: %v", relPath, resumeOffset, err)
		rw.Write([]byte{0x00})
		return
	}

	// 4. Segnala OK, invia metadati e poi i byte richiesti.
	if _, err := rw.Write([]byte{0x01}); err != nil { // Status: OK
		log.Printf("[Download] Errore invio status a %s: %v", deviceID, err)
		return
	}
	if err := writeInt64BE(rw, fileSize); err != nil {
		log.Printf("[Download] Errore invio dimensione a %s: %v", deviceID, err)
		return
	}
	checksumBytes, err := hex.DecodeString(checksum)
	if err != nil {
		log.Printf("[Download] Checksum interno non decodificabile per %s: %v", relPath, err)
		return
	}
	if _, err := rw.Write(checksumBytes); err != nil {
		log.Printf("[Download] Errore invio checksum a %s: %v", deviceID, err)
		return
	}

	bytesSent, err := io.Copy(rw, file)
	if err != nil {
		log.Printf("[Download] Errore invio file: %v", err)
		return
	}

	log.Printf("✅ [Download] File inviato ad Android: %s (%d/%d bytes da offset %d)",
		fullPath, bytesSent, fileSize, resumeOffset)
}

// handleDelete: elimina un file fisico dal Raspberry e lo rimuove dal DB
func handleDelete(rw io.ReadWriter, db *sql.DB, storagePath, deviceID string) {
	pathLenBuf := make([]byte, 2)
	if _, err := io.ReadFull(rw, pathLenBuf); err != nil {
		return
	}
	pathLen := int(pathLenBuf[0])<<8 | int(pathLenBuf[1])
	if pathLen == 0 || pathLen > 4096 {
		rw.Write([]byte{0x00})
		return
	}

	relPathBuf := make([]byte, pathLen)
	if _, err := io.ReadFull(rw, relPathBuf); err != nil {
		rw.Write([]byte{0x00})
		return
	}
	relPath := string(relPathBuf)

	// Valida percorso (anti path traversal)
	fullPath, err := validatePath(storagePath, relPath)
	if err != nil {
		log.Printf("[Delete] ⛔ Path non valido da %s: %v", deviceID, err)
		rw.Write([]byte{0x00})
		return
	}

	if err := os.Remove(fullPath); err != nil && !os.IsNotExist(err) {
		log.Printf("[Delete] ❌ Errore eliminazione '%s': %v", fullPath, err)
		rw.Write([]byte{0x00})
		return
	}
	if err := os.Remove(fullPath + ".part"); err != nil && !os.IsNotExist(err) {
		log.Printf("[Delete] ⚠️ Errore eliminazione parziale '%s.part': %v", fullPath, err)
	}

	if err := DeleteFileByRelPath(db, deviceID, relPath); err != nil {
		log.Printf("[Delete] ⚠️ Errore rimozione dal DB '%s': %v", relPath, err)
	}

	log.Printf("🗑️  [Delete] File eliminato: %s", fullPath)
	rw.Write([]byte{0x01}) // OK
}

// handleSyncIndex: riceve la lista di relPath presenti sul telefono,
// risponde con i relPath che esistono sul Raspberry ma NON nella lista → orfani.
// Protocollo:
//   Android → Raspberry: [4B count][per ogni file: 2B len + path UTF-8]
//   Raspberry → Android: [4B count_orfani][per ogni orfano: 2B len + path UTF-8]
func handleSyncIndex(rw io.ReadWriter, db *sql.DB, deviceID string) {
	log.Printf("[SyncIndex] Sincronizzazione indice per device: %s", deviceID)

	// 1. Legge quanti file manda il telefono
	countBuf := make([]byte, 4)
	if _, err := io.ReadFull(rw, countBuf); err != nil {
		log.Printf("[SyncIndex] Errore lettura count: %v", err)
		return
	}
	count := int(countBuf[0])<<24 | int(countBuf[1])<<16 | int(countBuf[2])<<8 | int(countBuf[3])

	// Bound check: previene loop/memoria eccessiva su count manipolato.
	if count > 500_000 {
		log.Printf("[SyncIndex] ⛔ Count fuori range da %s: %d", deviceID, count)
		return
	}

	// 2. Legge la lista di path dal telefono
	phoneFiles := make(map[string]bool, count)
	for i := 0; i < count; i++ {
		lenBuf := make([]byte, 2)
		if _, err := io.ReadFull(rw, lenBuf); err != nil {
			return
		}
		pathLen := int(lenBuf[0])<<8 | int(lenBuf[1])
		if pathLen == 0 || pathLen > 4096 {
			log.Printf("[SyncIndex] ⛔ pathLen non valido da %s: %d", deviceID, pathLen)
			return
		}
		pathBuf := make([]byte, pathLen)
		if _, err := io.ReadFull(rw, pathBuf); err != nil {
			return
		}
		phoneFiles[string(pathBuf)] = true
	}

	// 3. Recupera dal DB tutti i file del dispositivo
	rows, err := db.Query("SELECT rel_path FROM files WHERE device_id = ?", deviceID)
	if err != nil {
		log.Printf("[SyncIndex] Errore query DB: %v", err)
		return
	}
	defer rows.Close()

	var orphans []string
	for rows.Next() {
		var relPath string
		if err := rows.Scan(&relPath); err != nil {
			continue
		}
		if !phoneFiles[relPath] {
			orphans = append(orphans, relPath)
		}
	}

	// 4. Invia la lista degli orfani al telefono in streaming.
	// Usiamo bufio.Writer per battere le piccole Write in chunk da 64 KB senza
	// dover allocare un unico buffer proporzionale al numero di orfani.
	orphanCount := len(orphans)
	log.Printf("[SyncIndex] Orfani trovati: %d su %d file totali nel DB", orphanCount, len(phoneFiles))

	bw := bufio.NewWriterSize(rw, 65536)

	// Scrivi il count (4 byte big-endian)
	countBytes := [4]byte{
		byte(orphanCount >> 24), byte(orphanCount >> 16),
		byte(orphanCount >> 8), byte(orphanCount),
	}
	if _, err := bw.Write(countBytes[:]); err != nil {
		log.Printf("[SyncIndex] Errore scrittura count a %s: %v", deviceID, err)
		return
	}

	// Scrivi ogni orfano: 2B lunghezza + path UTF-8
	for _, path := range orphans {
		pathBytes := []byte(path)
		lenBytes := [2]byte{byte(len(pathBytes) >> 8), byte(len(pathBytes))}
		if _, err := bw.Write(lenBytes[:]); err != nil {
			log.Printf("[SyncIndex] Errore scrittura len orfano a %s: %v", deviceID, err)
			return
		}
		if _, err := bw.Write(pathBytes); err != nil {
			log.Printf("[SyncIndex] Errore scrittura path orfano a %s: %v", deviceID, err)
			return
		}
	}

	if err := bw.Flush(); err != nil {
		log.Printf("[SyncIndex] Errore flush risposta a %s: %v", deviceID, err)
	}
}

// ============================================================
// HELPER: sicurezza path
// ============================================================

// validatePath verifica che il percorso risolto resti all'interno di storagePath.
// Previene directory traversal (es. "../../etc/passwd").
func validatePath(storagePath, relPath string) (string, error) {
	// Rimuovi eventuali slash iniziali per sicurezza
	relPath = strings.TrimPrefix(relPath, "/")

	absStorage, err := filepath.Abs(storagePath)
	if err != nil {
		return "", fmt.Errorf("impossibile risolvere storage path: %v", err)
	}

	fullPath := filepath.Join(absStorage, relPath)
	absFile, err := filepath.Abs(fullPath)
	if err != nil {
		return "", fmt.Errorf("impossibile risolvere file path: %v", err)
	}

	// Il percorso assoluto deve iniziare con storagePath + separatore
	if !strings.HasPrefix(absFile, absStorage+string(filepath.Separator)) &&
		absFile != absStorage {
		return "", fmt.Errorf("path traversal rilevato: '%s' esce da '%s'", relPath, absStorage)
	}

	return absFile, nil
}

// diskFreeBytes restituisce i byte liberi sulla partizione che contiene il path dato.
func diskFreeBytes(path string) (uint64, error) {
	var stat syscall.Statfs_t
	if err := syscall.Statfs(path, &stat); err != nil {
		return 0, err
	}
	return stat.Bavail * uint64(stat.Bsize), nil
}

// ============================================================
// HELPER TLS CONFIG
// ============================================================

func buildTLSConfig(certFile, keyFile, caFile string, forQUIC bool) (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, fmt.Errorf("Impossibile caricare certificati: %v", err)
	}

	caCert, err := os.ReadFile(caFile)
	if err != nil {
		return nil, fmt.Errorf("Impossibile leggere CA: %v", err)
	}

	caCertPool := x509.NewCertPool()
	caCertPool.AppendCertsFromPEM(caCert)

	conf := &tls.Config{
		Certificates: []tls.Certificate{cert},
		ClientAuth:   tls.RequireAndVerifyClientCert,
		ClientCAs:    caCertPool,
		MinVersion:   tls.VersionTLS13,
	}

	if forQUIC {
		conf.NextProtos = []string{"shadowfs-quic-v1"}
	}

	return conf, nil
}
