package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"strings"
)

func main() {
	generateCerts := flag.Bool("generate-certs", false, "Genera i certificati mTLS invece di avviare il server")
	storagePath := flag.String("storage", "./shadow_root", "Percorso directory storage per i file offloaded")
	dbPath := flag.String("db", "shadowfs.db", "Percorso database SQLite")
	quicAddr := flag.String("addr", "0.0.0.0:4242", "Indirizzo UDP per QUIC (futuro)")
	tcpAddr := flag.String("tcp-addr", "0.0.0.0:4243", "Indirizzo TCP+TLS per il client Android")
	serverIPsStr := flag.String("server-ip", "", "IP del server da aggiungere al certificato TLS (es: 100.80.33.12). Più IP separati da virgola.")
	_ = quicAddr // QUIC disponibile ma TCP è la via principale per Android

	flag.Parse()

	// 1. Genera i certificati se richiesto
	if *generateCerts {
		var serverIPs []net.IP
		if *serverIPsStr != "" {
			for _, ipStr := range strings.Split(*serverIPsStr, ",") {
				ipStr = strings.TrimSpace(ipStr)
				if ip := net.ParseIP(ipStr); ip != nil {
					serverIPs = append(serverIPs, ip)
					fmt.Printf("   -> Aggiungendo IP al certificato server: %s\n", ip)
				} else {
					fmt.Printf("   ⚠️ IP non valido ignorato: %s\n", ipStr)
				}
			}
		}

		err := GenerateMTLSCertificates("certs", serverIPs)
		if err != nil {
			log.Fatalf("❌ Errore generazione certificati: %v", err)
		}
		fmt.Println("✅ Certificati generati con successo nella cartella 'certs/'.")
		fmt.Println()
		fmt.Println("   File da copiare sullo smartphone Android:")
		fmt.Println("     certs/ca.crt     → /sdcard/Download/shadowfs_certs/ca.crt")
		fmt.Println("     certs/client.crt → /sdcard/Download/shadowfs_certs/client.crt")
		fmt.Println("     certs/client.key → /sdcard/Download/shadowfs_certs/client.key")
		fmt.Println()
		fmt.Println("   (crea la cartella 'shadowfs_certs' nella Download del telefono)")
		os.Exit(0)
	}

	// 2. Crea directory storage
	if err := os.MkdirAll(*storagePath, 0755); err != nil {
		log.Fatalf("❌ Impossibile creare directory di storage: %v", err)
	}

	// 3. Inizializza il Database
	fmt.Println("📦 Inizializzazione Database SQLite...")
	db, err := InitDB(*dbPath)
	if err != nil {
		log.Fatalf("❌ Errore DB: %v", err)
	}
	defer db.Close()

	// 4. Scansione iniziale della cartella storage
	fmt.Printf("🔍 Scansione iniziale di '%s'...\n", *storagePath)
	if err := ScanFolder(db, *storagePath); err != nil {
		log.Printf("⚠️ Errore durante la scansione: %v", err)
	}
	fmt.Println("✅ Scansione completata.")

	// 5. Avvia il server TCP+TLS (principale — compatibile con Android)
	fmt.Printf("🚀 Avvio ShadowDaemon TCP+mTLS su %s...\n", *tcpAddr)
	fmt.Println("   (In attesa di connessioni dall'app Android...)")
	fmt.Println()
	fmt.Println("   Per connettere Android: configura l'IP di questo Raspberry nell'app.")
	fmt.Println("   Se usi Tailscale, usa l'IP 100.x.x.x. Se sei in LAN, usa l'IP locale.")

	if err := StartTCPServer(*tcpAddr, "certs/server.crt", "certs/server.key", "certs/ca.crt", db, *storagePath); err != nil {
		log.Fatalf("❌ Errore server TCP+mTLS: %v", err)
	}
}
