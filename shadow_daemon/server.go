package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"database/sql"
	"fmt"
	"io"
	"log"
	"os"

	"github.com/quic-go/quic-go"
)

const (
	ChunkSize = 4 * 1024 * 1024 // 4 MB Chunk limit
)

// StartServer binds QUIC server enforcing mTLS
func StartServer(addr, certFile, keyFile, caFile string, db *sql.DB, storagePath string) error {
	tlsConf, err := loadMTLSConfig(certFile, keyFile, caFile)
	if err != nil {
		return fmt.Errorf("TLS Config Error: %v", err)
	}

	listener, err := quic.ListenAddr(addr, tlsConf, nil)
	if err != nil {
		return err
	}

	for {
		conn, err := listener.Accept(context.Background())
		if err != nil {
			log.Printf("Errore accettazione connessione QUIC: %v", err)
			continue
		}

		go handleConnection(conn, db, storagePath)
	}
}

func loadMTLSConfig(certFile, keyFile, caFile string) (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, fmt.Errorf("Impossibile caricare certificati server: %v", err)
	}

	caCert, err := os.ReadFile(caFile)
	if err != nil {
		return nil, fmt.Errorf("Impossibile leggere CA per mTLS: %v", err)
	}

	caCertPool := x509.NewCertPool()
	caCertPool.AppendCertsFromPEM(caCert)

	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		ClientAuth:   tls.RequireAndVerifyClientCert,
		ClientCAs:    caCertPool,
		NextProtos:   []string{"shadowfs-quic-v1"},
	}, nil
}

func handleConnection(conn quic.Connection, db *sql.DB, storagePath string) {
	log.Printf("🔒 Nuova connessione mTLS sicura da: %s", conn.RemoteAddr())

	for {
		stream, err := conn.AcceptStream(context.Background())
		if err != nil {
			log.Printf("Stream chiuso per %s: %v", conn.RemoteAddr(), err)
			return
		}

		go handleStream(stream, db, storagePath)
	}
}

func handleStream(stream quic.Stream, db *sql.DB, storagePath string) {
	defer stream.Close()

	// Implementazione di un protocollo base: 
	// 1 byte che definisce l'azione (1 = Push Chunk da Android a Pi, 2 = Pull Chunk da Pi ad Android)
	cmdBuf := make([]byte, 1)
	if _, err := io.ReadFull(stream, cmdBuf); err != nil {
		log.Printf("Read err: %v", err)
		return
	}

	switch cmdBuf[0] {
	case 1:
		handleUpload(stream, db, storagePath)
	case 2:
		handleDownload(stream, db, storagePath)
	default:
		log.Printf("⚠️ Comando sconosciuto ricevuto over-QUIC.")
	}
}

// handleUpload gestisce la ricezione del file da Android e lo salva
// replicando l'esatto albero delle cartelle del telefono.
func handleUpload(stream quic.Stream, db *sql.DB, storagePath string) {
	log.Println("Ricezione file (Cantierizzazione in corso...)")

	// 1. Legge la lunghezza del percorso relativo (2 bytes)
	pathLenBuf := make([]byte, 2)
	if _, err := io.ReadFull(stream, pathLenBuf); err != nil {
		log.Printf("Errore lettura lunghezza path: %v", err)
		return
	}
	pathLen := int(pathLenBuf[0])<<8 | int(pathLenBuf[1])

	// 2. Legge il percorso relativo (es. "DCIM/Camera/foto.jpg")
	relPathBuf := make([]byte, pathLen)
	if _, err := io.ReadFull(stream, relPathBuf); err != nil {
		log.Printf("Errore lettura path: %v", err)
		return
	}
	relPath := string(relPathBuf)

	// 3. Calcola il percorso assoluto sul Raspberry e crea l'albero delle directory
	// Così sul Raspberry avrai: /storage/shadow_root/DCIM/Camera/foto.jpg
	fullPath := storagePath + "/" + relPath
	if err := os.MkdirAll(getDir(fullPath), 0755); err != nil {
		log.Printf("Errore creazione albero cartelle '%s': %v", getDir(fullPath), err)
		return
	}

	// 4. Apre il file per la scrittura (creandolo o sovrascrivendolo)
	file, err := os.Create(fullPath)
	if err != nil {
		log.Printf("Errore creazione file fisico '%s': %v", fullPath, err)
		return
	}
	defer file.Close()

	// 5. Trasferisce i blocchi di byte dal flusso QUIC direttamente al disco rigido
	// io.Copy si ferma automaticamente quando il client chiude lo stream
	writtenBytes, err := io.Copy(file, stream)
	if err != nil && err != io.EOF {
		log.Printf("Errore durante la scrittura del file: %v", err)
		return
	}

	log.Printf("✅ File salvato fisicamente: %s (%d bytes)", fullPath, writtenBytes)
}

// handleDownload gestisce la richiesta di Idratazione inviando il file fisico
func handleDownload(stream quic.Stream, db *sql.DB, storagePath string) {
	log.Println("Richiesta file (Idratazione in corso...)")

	// 1. Legge quale file vuole Android
	pathLenBuf := make([]byte, 2)
	if _, err := io.ReadFull(stream, pathLenBuf); err != nil {
		return
	}
	pathLen := int(pathLenBuf[0])<<8 | int(pathLenBuf[1])

	relPathBuf := make([]byte, pathLen)
	if _, err := io.ReadFull(stream, relPathBuf); err != nil {
		return
	}
	relPath := string(relPathBuf)

	// 2. Cerca il file fisico sul Raspberry
	fullPath := storagePath + "/" + relPath
	file, err := os.Open(fullPath)
	if err != nil {
		log.Printf("❌ Errore, file richiesto non trovato: %s", fullPath)
		// Inviamo un byte di errore
		stream.Write([]byte{0x00}) 
		return
	}
	defer file.Close()

	// Segnala che il file esiste
	stream.Write([]byte{0x01}) 

	// 3. Trasferisce il file dal disco al flusso QUIC
	io.Copy(stream, file)
	log.Printf("✅ File re-inviato ad Android: %s", fullPath)
}

// Helper per ottenere la cartella padre di un path
func getDir(path string) string {
	for i := len(path) - 1; i >= 0; i-- {
		if path[i] == '/' {
			return path[:i]
		}
	}
	return path
}
