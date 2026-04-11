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
	log.Printf("🔒 [TCP] Nuova connessione mTLS da: %s", conn.RemoteAddr())

	cmdBuf := make([]byte, 1)
	if _, err := io.ReadFull(conn, cmdBuf); err != nil {
		log.Printf("[TCP] Errore lettura cmd: %v", err)
		return
	}

	switch cmdBuf[0] {
	case 1:
		handleUpload(conn, db, storagePath)
	case 2:
		handleDownload(conn, db, storagePath)
	default:
		log.Printf("[TCP] ⚠️ Comando sconosciuto: %d", cmdBuf[0])
	}
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
		handleUpload(stream, db, storagePath)
	case 2:
		handleDownload(stream, db, storagePath)
	default:
		log.Printf("[QUIC] ⚠️ Comando sconosciuto: %d", cmdBuf[0])
	}
}

// ============================================================
// HANDLERS CONDIVISI (funzionano sia con TCP net.Conn che QUIC Stream)
// ============================================================

// handleUpload: riceve un file da Android, lo salva sul disco e aggiorna il DB
func handleUpload(rw io.ReadWriter, db *sql.DB, storagePath string) {
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
	existingFile, dbErr := GetFileByRelPath(db, relPath)
	var fileUUID string
	if dbErr == nil {
		fileUUID = existingFile.UUID
	} else {
		fileUUID = uuid.New().String()
	}

	f := &FileData{
		UUID:         fileUUID,
		Filename:     filepath.Base(relPath),
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
func handleDownload(rw io.ReadWriter, db *sql.DB, storagePath string) {
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
