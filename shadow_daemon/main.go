package main

import (
	"flag"
	"fmt"
	"log"
	"os"
)

func main() {
	generateCerts := flag.Bool("generate-certs", false, "Generate mTLS certificates instead of starting server")
	storagePath := flag.String("storage", "./shadow_root", "Path to the root storage directory for offloaded files")
	dbPath := flag.String("db", "shadowfs.db", "Path to SQLite database")
	listenAddr := flag.String("addr", "0.0.0.0:4242", "UDP address to listen on for QUIC")

	flag.Parse()

	// 1. Generate Certificates if requested
	if *generateCerts {
		err := GenerateMTLSCertificates("certs")
		if err != nil {
			log.Fatalf("❌ Errore durante la generazione dei certificati: %v", err)
		}
		fmt.Println("✅ Certificati generati con successo nella cartella 'certs'.")
		fmt.Println("    -> Trasferisci 'certs/ca.crt', 'certs/client.crt' e 'certs/client.key' al dispositivo Android.")
		os.Exit(0)
	}

	// 2. Setup Storage
	if err := os.MkdirAll(*storagePath, 0755); err != nil {
		log.Fatalf("❌ Impossibile creare directory di storage: %v", err)
	}

	// 3. Initialize Database
	fmt.Println("📦 Inizializzazione Database SQLite...")
	db, err := InitDB(*dbPath)
	if err != nil {
		log.Fatalf("❌ Errore DB: %v", err)
	}
	defer db.Close()

	// 4. Start File Scanner (Garbage Collector e Indexer)
	fmt.Printf("🔍 Scansione iniziale della cartella %s...\n", *storagePath)
	if err := ScanFolder(db, *storagePath); err != nil {
		log.Printf("⚠️ Errore durante la scansione: %v", err)
	}
	fmt.Println("✅ Scansione completata.")

	// 5. Start QUIC mTLS Server
	fmt.Printf("🚀 Avvio ShadowDaemon in ascolto su %s (QUIC+mTLS)...\n", *listenAddr)
	if err := StartServer(*listenAddr, "certs/server.crt", "certs/server.key", "certs/ca.crt", db, *storagePath); err != nil {
		log.Fatalf("❌ Errore server QUIC: %v", err)
	}
}
