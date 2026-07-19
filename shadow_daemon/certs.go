package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"
)

// GenerateMTLSCertificates crea CA, certificato Server e certificato Client per il pairing.
// serverIPs: lista di IP da aggiungere al certificato server (es. IP Tailscale del Raspberry).
func GenerateMTLSCertificates(outDir string, serverIPs []net.IP) error {
	if err := os.MkdirAll(outDir, 0755); err != nil {
		return err
	}

	// 1. Genera la CA (Certificate Authority)
	caTemplate := &x509.Certificate{
		SerialNumber:          big.NewInt(2023),
		Subject:               pkix.Name{Organization: []string{"ShadowFS CA"}},
		NotBefore:             time.Now(),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		IsCA:                  true,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth, x509.ExtKeyUsageServerAuth},
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		BasicConstraintsValid: true,
	}

	caPrivKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return err
	}
	caBytes, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caPrivKey.PublicKey, caPrivKey)
	if err != nil {
		return err
	}
	if err := savePEM(filepath.Join(outDir, "ca.crt"), "CERTIFICATE", caBytes); err != nil {
		return err
	}
	// Salva anche la CA key — necessaria per generare certificati per nuovi dispositivi
	caKeyBytes, err := x509.MarshalPKCS8PrivateKey(caPrivKey)
	if err != nil {
		return err
	}
	if err := saveKeyPEM(filepath.Join(outDir, "ca.key"), "PRIVATE KEY", caKeyBytes); err != nil {
		return err
	}

	// 2. Genera il Certificato Server
	// Includiamo "localhost", "shadowdaemon" come DNS names
	// E tutti gli IP forniti (+ 127.0.0.1 di default)
	allServerIPs := append([]net.IP{net.IPv4(127, 0, 0, 1)}, serverIPs...)

	serverTemplate := &x509.Certificate{
		SerialNumber: big.NewInt(2024),
		Subject:      pkix.Name{Organization: []string{"ShadowDaemon Server"}, CommonName: "shadowdaemon"},
		NotBefore:    time.Now(),
		NotAfter:     time.Now().AddDate(5, 0, 0),
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		DNSNames:     []string{"localhost", "shadowdaemon"},
		IPAddresses:  allServerIPs,
	}
	serverPrivKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return err
	}
	serverBytes, err := x509.CreateCertificate(rand.Reader, serverTemplate, caTemplate, &serverPrivKey.PublicKey, caPrivKey)
	if err != nil {
		return err
	}
	if err := savePEM(filepath.Join(outDir, "server.crt"), "CERTIFICATE", serverBytes); err != nil {
		return err
	}
	// PKCS#8 è compatibile con Android natively (no BouncyCastle necessario)
	serverKeyBytes, err := x509.MarshalPKCS8PrivateKey(serverPrivKey)
	if err != nil {
		return err
	}
	if err := saveKeyPEM(filepath.Join(outDir, "server.key"), "PRIVATE KEY", serverKeyBytes); err != nil {
		return err
	}

	// 3. Genera il Certificato Client (per l'app Android)
	clientTemplate := &x509.Certificate{
		SerialNumber: big.NewInt(2025),
		Subject:      pkix.Name{Organization: []string{"ShadowClient Android"}, CommonName: "shadowclient"},
		NotBefore:    time.Now(),
		NotAfter:     time.Now().AddDate(5, 0, 0),
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
	}
	clientPrivKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return err
	}
	clientBytes, err := x509.CreateCertificate(rand.Reader, clientTemplate, caTemplate, &clientPrivKey.PublicKey, caPrivKey)
	if err != nil {
		return err
	}
	if err := savePEM(filepath.Join(outDir, "client.crt"), "CERTIFICATE", clientBytes); err != nil {
		return err
	}
	// PKCS#8: compatibile con Android SSLSocket senza librerie extra
	clientKeyBytes, err := x509.MarshalPKCS8PrivateKey(clientPrivKey)
	if err != nil {
		return err
	}
	if err := saveKeyPEM(filepath.Join(outDir, "client.key"), "PRIVATE KEY", clientKeyBytes); err != nil {
		return err
	}

	return nil
}

// GenerateDeviceCertificate genera un certificato client per un nuovo dispositivo
// usando la CA già esistente. deviceID diventa il CN del certificato.
func GenerateDeviceCertificate(certsDir, deviceID string) error {
	outDir := filepath.Join(certsDir, "..", "certs_for_android_"+deviceID)
	if err := os.MkdirAll(outDir, 0755); err != nil {
		return err
	}

	// Carica la CA esistente
	caKeyPEM, err := os.ReadFile(filepath.Join(certsDir, "ca.key"))
	if err != nil {
		return fmt.Errorf("impossibile leggere ca.key: %v", err)
	}
	caCertPEM, err := os.ReadFile(filepath.Join(certsDir, "ca.crt"))
	if err != nil {
		return fmt.Errorf("impossibile leggere ca.crt: %v", err)
	}

	// Parsing difensivo: pem.Decode può restituire nil e il cast a RSA può
	// fallire su file corrotti/di tipo diverso — meglio un errore chiaro
	// che un panic del daemon.
	caKeyBlock, _ := pem.Decode(caKeyPEM)
	if caKeyBlock == nil {
		return fmt.Errorf("ca.key non è un PEM valido")
	}
	caKeyIface, err := x509.ParsePKCS8PrivateKey(caKeyBlock.Bytes)
	if err != nil {
		return fmt.Errorf("impossibile parsare ca.key: %v", err)
	}
	caPrivKey, ok := caKeyIface.(*rsa.PrivateKey)
	if !ok {
		return fmt.Errorf("ca.key non è una chiave RSA (tipo: %T)", caKeyIface)
	}

	caCertBlock, _ := pem.Decode(caCertPEM)
	if caCertBlock == nil {
		return fmt.Errorf("ca.crt non è un PEM valido")
	}
	caCert, err := x509.ParseCertificate(caCertBlock.Bytes)
	if err != nil {
		return fmt.Errorf("impossibile parsare ca.crt: %v", err)
	}

	// Genera certificato client con CN=deviceID
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	clientTemplate := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{Organization: []string{"ShadowClient Android"}, CommonName: deviceID},
		NotBefore:    time.Now(),
		NotAfter:     time.Now().AddDate(5, 0, 0),
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
	}

	clientPrivKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return err
	}
	clientBytes, err := x509.CreateCertificate(rand.Reader, clientTemplate, caCert, &clientPrivKey.PublicKey, caPrivKey)
	if err != nil {
		return err
	}

	if err := savePEM(filepath.Join(outDir, "client.crt"), "CERTIFICATE", clientBytes); err != nil {
		return err
	}
	clientKeyBytes, err := x509.MarshalPKCS8PrivateKey(clientPrivKey)
	if err != nil {
		return err
	}
	if err := saveKeyPEM(filepath.Join(outDir, "client.key"), "PRIVATE KEY", clientKeyBytes); err != nil {
		return err
	}
	// Copia la CA (stessa per tutti i dispositivi) — caCertPEM è già stato
	// letto e validato sopra: riusarlo evita una seconda lettura non controllata.
	if err := os.WriteFile(filepath.Join(outDir, "ca.crt"), caCertPEM, 0644); err != nil {
		return err
	}

	return nil
}

// savePEM scrive certificati pubblici con permessi 0644.
func savePEM(filename, pemType string, bytes []byte) error {
	return savePEMWithMode(filename, pemType, bytes, 0644)
}

// saveKeyPEM scrive chiavi private con permessi 0600: una chiave
// world-readable permette a qualsiasi processo locale di impersonare
// server o client mTLS.
func saveKeyPEM(filename, pemType string, bytes []byte) error {
	return savePEMWithMode(filename, pemType, bytes, 0600)
}

func savePEMWithMode(filename, pemType string, bytes []byte, mode os.FileMode) error {
	f, err := os.OpenFile(filename, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	defer f.Close()
	// Chmod esplicito: O_CREATE applica mode solo ai file NUOVI; su un file
	// esistente (rigenerazione certificati) i vecchi permessi resterebbero.
	if err := f.Chmod(mode); err != nil {
		return err
	}
	return pem.Encode(f, &pem.Block{Type: pemType, Bytes: bytes})
}
