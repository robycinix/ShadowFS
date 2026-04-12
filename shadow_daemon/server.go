package main

import (
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
	"time"

	"github.com/google/uuid"
	"github.com/quic-go/quic-go"
)

const (
	ChunkSize = 4 * 1024 * 1024 // 4 MB
)

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
		go handleTCPConnection(conn, db, storagePath)
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
		log.Printf("[TCP] Errore lettura cmd: %v", err)
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

// getDeviceID estrae il CN dal certificato TLS client per identificare il dispositivo
func getDeviceID(conn net.Conn) string {
	tlsConn, ok := conn.(*tls.Conn)
	if !ok {
		return "default"
	}
	state := tlsConn.ConnectionState()
	if len(state.PeerCertificates) == 0 {
		return "default"
	}
	cn := state.PeerCertificates[0].Subject.CommonName
	if cn == "" {
		return "default"
	}
	// Sanitizza: rimuovi caratteri non sicuri per i path
	cn = strings.ReplaceAll(cn, "/", "_")
	cn = strings.ReplaceAll(cn, "..", "_")
	cn = strings.ReplaceAll(cn, " ", "_")
	return cn
}

// ============================================================
// SERVER QUIC (per uso futuro / test desktop)
// ============================================================

// StartServer avvia il server QUIC+mTLS (mantenuto per compatibilità)
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

// handleUpload: riceve un file da Android, lo salva sul disco e aggiorna il DB
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

	// 3. Calcola il percorso assoluto e crea l'albero delle directory
	fullPath := filepath.Join(storagePath, relPath)
	if err := os.MkdirAll(filepath.Dir(fullPath), 0755); err != nil {
		log.Printf("[Upload] Errore creazione directory per '%s': %v", fullPath, err)
		return
	}

	// 4. Apre il file per la scrittura
	file, err := os.Create(fullPath)
	if err != nil {
		log.Printf("[Upload] Errore creazione file '%s': %v", fullPath, err)
		return
	}
	defer file.Close()

	// 5. Trasferisce i dati calcolando l'hash SHA-256 in un unico passaggio
	hash := sha256.New()
	teeReader := io.TeeReader(rw, hash)
	writtenBytes, err := io.Copy(file, teeReader)
	if err != nil && err != io.EOF {
		log.Printf("[Upload] Errore scrittura file: %v", err)
		return
	}
	checksum := hex.EncodeToString(hash.Sum(nil))

	log.Printf("✅ [Upload] File salvato: %s (%d bytes, sha256: %s...)", fullPath, writtenBytes, checksum[:8])

	// 6. Aggiorna il database — cerca UUID esistente per evitare duplicati
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
		Size:         writtenBytes,
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

// handleDownload: invia un file fisico al client Android (Hydration)
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

	// 2. Cerca il file fisico sul Raspberry
	fullPath := filepath.Join(storagePath, relPath)
	file, err := os.Open(fullPath)
	if err != nil {
		log.Printf("[Download] ❌ File non trovato: %s", fullPath)
		rw.Write([]byte{0x00}) // Status: NOT FOUND
		return
	}
	defer file.Close()

	// 3. Segnala OK e invia il file
	rw.Write([]byte{0x01}) // Status: OK
	bytesSent, err := io.Copy(rw, file)
	if err != nil {
		log.Printf("[Download] Errore invio file: %v", err)
		return
	}

	log.Printf("✅ [Download] File inviato ad Android: %s (%d bytes)", fullPath, bytesSent)
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
	fullPath := filepath.Join(storagePath, relPath)

	if err := os.Remove(fullPath); err != nil && !os.IsNotExist(err) {
		log.Printf("[Delete] ❌ Errore eliminazione '%s': %v", fullPath, err)
		rw.Write([]byte{0x00})
		return
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

	// 2. Legge la lista di path dal telefono
	phoneFiles := make(map[string]bool)
	for i := 0; i < count; i++ {
		lenBuf := make([]byte, 2)
		if _, err := io.ReadFull(rw, lenBuf); err != nil {
			return
		}
		pathLen := int(lenBuf[0])<<8 | int(lenBuf[1])
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

	// 4. Invia la lista degli orfani al telefono
	orphanCount := len(orphans)
	log.Printf("[SyncIndex] Orfani trovati: %d su %d file totali nel DB", orphanCount, len(phoneFiles))

	resp := []byte{
		byte(orphanCount >> 24), byte(orphanCount >> 16),
		byte(orphanCount >> 8), byte(orphanCount),
	}
	for _, path := range orphans {
		pathBytes := []byte(path)
		resp = append(resp, byte(len(pathBytes)>>8), byte(len(pathBytes)))
		resp = append(resp, pathBytes...)
	}
	rw.Write(resp)
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
