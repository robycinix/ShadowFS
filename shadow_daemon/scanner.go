package main

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"io"
	"log"
	"os"
	"path/filepath"
	"time"

	"github.com/google/uuid"
)

// ScanFolder scansiona la directory e aggiorna i metadati nel DB.
// Non genera UUID duplicati: riusa l'UUID esistente se il file è già noto.
func ScanFolder(db *sql.DB, rootPath string) error {
	return filepath.Walk(rootPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}
		if err := analyzeFile(db, rootPath, path, info); err != nil {
			log.Printf("⚠️ Errore analisi file %s: %v", path, err)
		}
		return nil
	})
}

func analyzeFile(db *sql.DB, rootPath, filePath string, info os.FileInfo) error {
	relPath, err := filepath.Rel(rootPath, filePath)
	if err != nil {
		return err
	}

	// FIX CRITICO: controlla se il file esiste già nel DB tramite rel_path.
	// Se esiste, riusa il suo UUID — evita di creare record duplicati ad ogni scansione.
	existingFile, dbErr := GetFileByRelPath(db, relPath)
	var fileUUID string
	if dbErr == nil {
		// File già noto: riusa l'UUID esistente
		fileUUID = existingFile.UUID
	} else {
		// Nuovo file: genera un UUID fresco
		fileUUID = uuid.New().String()
	}

	hashStr, err := calculateSHA256(filePath)
	if err != nil {
		return err
	}

	f := &FileData{
		UUID:         fileUUID,
		Filename:     info.Name(),
		RelPath:      relPath,
		Size:         info.Size(),
		Status:       "FULL",
		Checksum:     hashStr,
		LastModified: info.ModTime(),
		LastAccess:   time.Now(),
	}

	return UpdateOrInsertFile(db, f)
}

// calculateSHA256 calcola l'hash SHA-256 di un file leggendolo in chunk da 32KB.
// Non satura la RAM anche per file molto grandi.
func calculateSHA256(filePath string) (string, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return "", err
	}
	defer file.Close()

	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}

	return hex.EncodeToString(hash.Sum(nil)), nil
}
