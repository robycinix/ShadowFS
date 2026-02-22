package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"time"
)

// GenerateMTLSCertificates creates a CA, Server, and Client certs for pairing
func GenerateMTLSCertificates(outDir string) error {
	if err := os.MkdirAll(outDir, 0755); err != nil {
		return err
	}

	// 1. Generate CA First
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

	// 2. Generate Server Cert
	serverTemplate := &x509.Certificate{
		SerialNumber: big.NewInt(2024),
		Subject:      pkix.Name{Organization: []string{"ShadowDaemon Server"}},
		NotBefore:    time.Now(),
		NotAfter:     time.Now().AddDate(5, 0, 0),
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		DNSNames:     []string{"localhost"},
		IPAddresses:  nil, // Accept from anywhere
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
	if err := savePEM(filepath.Join(outDir, "server.key"), "RSA PRIVATE KEY", x509.MarshalPKCS1PrivateKey(serverPrivKey)); err != nil {
		return err
	}

	// 3. Generate Client Cert (Android)
	clientTemplate := &x509.Certificate{
		SerialNumber: big.NewInt(2025),
		Subject:      pkix.Name{Organization: []string{"ShadowClient Fast"}},
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
	if err := savePEM(filepath.Join(outDir, "client.key"), "RSA PRIVATE KEY", x509.MarshalPKCS1PrivateKey(clientPrivKey)); err != nil {
		return err
	}

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
