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
	if err := savePEM(filepath.Join(outDir, "ca.key"), "PRIVATE KEY", caKeyBytes); err != nil {
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
	if err := savePEM(filepath.Join(outDir, "server.key"), "PRIVATE KEY", serverKeyBytes); err != nil {
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
	if err := savePEM(filepath.Join(outDir, "client.key"), "PRIVATE KEY", clientKeyBytes); err != nil {
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

	caKeyBlock, _ := pem.Decode(caKeyPEM)
	caKeyIface, err := x509.ParsePKCS8PrivateKey(caKeyBlock.Bytes)
	if err != nil {
		return fmt.Errorf("impossibile parsare ca.key: %v", err)
	}
	caPrivKey := caKeyIface.(*rsa.PrivateKey)

	caCertBlock, _ := pem.Decode(caCertPEM)
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
	if err := savePEM(filepath.Join(outDir, "client.key"), "PRIVATE KEY", clientKeyBytes); err != nil {
		return err
	}
	// Copia la CA (stessa per tutti i dispositivi)
	caData, _ := os.ReadFile(filepath.Join(certsDir, "ca.crt"))
	os.WriteFile(filepath.Join(outDir, "ca.crt"), caData, 0644)

	return nil
}

func savePEM(filename, pemType string, bytes []byte) error {
	f, err := os.Create(filename)
	if err != nil {
		return err
	}
	defer f.Close()
	return pem.Encode(f, &pem.Block{Type: pemType, Bytes: bytes})
}
