package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync/atomic"
	"time"
)

// PairingPayload è il JSON restituito dal server di pairing
type PairingPayload struct {
	IP   string `json:"ip"`
	Port int    `json:"port"`
	CA   string `json:"ca"`
	CRT  string `json:"crt"`
	Key  string `json:"key"`
}

// pairingToken è il token one-time generato all'avvio. Valido per pairingWindowSeconds.
var (
	pairingToken         string
	pairingTokenExpiry   time.Time
	pairingWindowSeconds = 5 * 60 // 5 minuti
	pairingUsed          int32    // atomic: 1 = già usato
)

// generatePairingToken genera un token esadecimale casuale da 16 byte.
func generatePairingToken() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic("impossibile generare token di pairing: " + err.Error())
	}
	return hex.EncodeToString(b)
}

// InitPairingToken genera il token one-time e imposta la finestra temporale.
// Deve essere chiamato PRIMA di avviare StartPairingServer in una goroutine,
// così PrintPairingInfo può leggere il token senza race condition.
func InitPairingToken() {
	pairingToken = generatePairingToken()
	pairingTokenExpiry = time.Now().Add(time.Duration(pairingWindowSeconds) * time.Second)
}

// StartPairingServer avvia un server HTTP su pairingAddr (es. "0.0.0.0:4244").
// Il payload è accessibile SOLO con il token one-time entro 5 minuti dall'avvio.
// Chiama InitPairingToken() prima di questa funzione.
// URL: http://<ip>:4244/pair?token=<token>
func StartPairingServer(pairingAddr, tcpAddr, certsDir string) {
	mux := http.NewServeMux()

	mux.HandleFunc("/pair", func(w http.ResponseWriter, r *http.Request) {
		// 1. Verifica token
		token := r.URL.Query().Get("token")
		if token == "" || token != pairingToken {
			log.Printf("⚠️ Pairing: token non valido da %s", r.RemoteAddr)
			http.Error(w, "Accesso non autorizzato", http.StatusUnauthorized)
			return
		}

		// 2. Verifica finestra temporale (5 minuti)
		if time.Now().After(pairingTokenExpiry) {
			log.Printf("⚠️ Pairing: token scaduto — riavvia il daemon per generarne uno nuovo")
			http.Error(w, "Token scaduto — riavvia il daemon", http.StatusGone)
			return
		}

		// 3. One-shot: il token funziona una sola volta
		if !atomic.CompareAndSwapInt32(&pairingUsed, 0, 1) {
			log.Printf("⚠️ Pairing: token già usato — riavvia il daemon per generarne uno nuovo")
			http.Error(w, "Token già utilizzato — riavvia il daemon", http.StatusGone)
			return
		}

		// 4. Costruisci e invia il payload
		payload, err := buildPairingPayload(tcpAddr, certsDir)
		if err != nil {
			atomic.StoreInt32(&pairingUsed, 0) // rollback se errore
			http.Error(w, "Errore interno: "+err.Error(), http.StatusInternalServerError)
			log.Printf("⚠️ Pairing: errore costruzione payload: %v", err)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(w).Encode(payload); err != nil {
			log.Printf("⚠️ Pairing: errore JSON encode: %v", err)
			return
		}
		log.Printf("✅ Pairing: configurazione servita con successo a %s", r.RemoteAddr)
	})

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "ShadowFS Pairing Server — accesso non autorizzato", http.StatusUnauthorized)
	})

	log.Printf("📷 Pairing HTTP server in ascolto su %s (token valido %d min)", pairingAddr, pairingWindowSeconds/60)
	if err := http.ListenAndServe(pairingAddr, mux); err != nil {
		log.Printf("⚠️ Pairing server errore: %v", err)
	}
}

// buildPairingPayload legge i certificati da disco e costruisce il payload JSON
func buildPairingPayload(tcpAddr, certsDir string) (*PairingPayload, error) {
	readB64 := func(filename string) (string, error) {
		data, err := os.ReadFile(filename)
		if err != nil {
			return "", fmt.Errorf("impossibile leggere %s: %v", filename, err)
		}
		return base64.StdEncoding.EncodeToString(data), nil
	}

	ca, err := readB64(certsDir + "/ca.crt")
	if err != nil {
		return nil, err
	}
	crt, err := readB64(certsDir + "/client.crt")
	if err != nil {
		return nil, err
	}
	key, err := readB64(certsDir + "/client.key")
	if err != nil {
		return nil, err
	}

	ip, portStr, err := net.SplitHostPort(tcpAddr)
	if err != nil {
		return nil, fmt.Errorf("indirizzo TCP non valido: %v", err)
	}
	// Se l'IP è 0.0.0.0, preferisci l'IP Tailscale (100.x.x.x) se disponibile,
	// altrimenti rileva l'IP LAN — così il pairing funziona anche da remoto.
	if ip == "0.0.0.0" || ip == "" {
		ip = DetectBestIP()
	}

	port := 4243
	fmt.Sscanf(portStr, "%d", &port)

	return &PairingPayload{
		IP:  ip,
		Port: port,
		CA:  ca,
		CRT: crt,
		Key: key,
	}, nil
}

// detectOutboundIP rileva l'IP locale usato per connessioni verso internet
func detectOutboundIP() string {
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return "127.0.0.1"
	}
	defer conn.Close()
	localAddr := conn.LocalAddr().(*net.UDPAddr)
	return localAddr.IP.String()
}

// PrintPairingInfo stampa l'URL di pairing (con token) e, se qrencode è disponibile, anche il QR ASCII
func PrintPairingInfo(pairingAddr string) {
	ip := detectOutboundIP()
	_, portStr, _ := net.SplitHostPort(pairingAddr)
	if portStr == "" {
		portStr = "4244"
	}
	pairingURL := fmt.Sprintf("http://%s:%s/pair?token=%s", ip, portStr, pairingToken)

	fmt.Println()
	fmt.Println("════════════════════════════════════════════════")
	fmt.Println("  📷  PAIRING QR CODE — scansiona dall'app")
	fmt.Println("════════════════════════════════════════════════")
	fmt.Printf("  URL: %s\n\n", pairingURL)

	// Prova qrencode (apt install qrencode)
	qrBin := findQREncode()
	if qrBin != "" {
		out, err := exec.Command(qrBin, "-t", "ANSIUTF8", pairingURL).Output()
		if err == nil {
			fmt.Println(string(out))
			fmt.Println("════════════════════════════════════════════════")
			return
		}
	}

	// Fallback: solo istruzioni
	fmt.Println("  (installa qrencode per visualizzare il QR: sudo apt install qrencode)")
	fmt.Printf("  Oppure apri manualmente: %s\n", pairingURL)
	fmt.Println("════════════════════════════════════════════════")
}

func findQREncode() string {
	candidates := []string{"/usr/bin/qrencode", "/usr/local/bin/qrencode"}
	for _, p := range candidates {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	// Prova anche via PATH
	if path, err := exec.LookPath("qrencode"); err == nil {
		return path
	}
	return ""
}

// DetectBestIP restituisce il Tailscale IP (100.x.x.x) se disponibile, altrimenti l'IP LAN
func DetectBestIP() string {
	// Prova a ottenere IP Tailscale
	out, err := exec.Command("tailscale", "ip", "-4").Output()
	if err == nil {
		ip := strings.TrimSpace(string(out))
		if net.ParseIP(ip) != nil {
			return ip
		}
	}
	return detectOutboundIP()
}
