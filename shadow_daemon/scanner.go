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

// ScanFolder scans directory and saves metadata
func ScanFolder(db *sql.DB, rootPath string) error {
	return filepath.Walk(rootPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}

		err = analyzeFile(db, rootPath, path, info)
		if err != nil {
			log.Printf("Errore durante l'analisi del file %s: %v\n", path, err)
		}
		return nil
	})
}

func analyzeFile(db *sql.DB, rootPath, filePath string, info os.FileInfo) error {
	relPath, err := filepath.Rel(rootPath, filePath)
	if err != nil {
		return err
	}

	hashStr, err := calculateSHA256(filePath)
	if err != nil {
		return err
	}

	// For a real production app we would check DB first to avoid re-generating UUID.
	// For this blueprint, we keep it simple.
	newUUID := uuid.New().String()

	f := &FileData{
		UUID:         newUUID,
		Filename:     info.Name(),
		RelPath:      relPath,
		Size:         info.Size(),
		Status:       "FULL",
		Checksum:     hashStr,
		LastModified: info.ModTime(),
		LastAccess:   time.Now(), // Simulated access time
	}

	return UpdateOrInsertFile(db, f)
}

func calculateSHA256(filePath string) (string, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return "", err
	}
	defer file.Close()

	hash := sha256.New()
	// io.Copy reads the file in 32KB chunks. Very efficient for large files.
	// RAM impact will be minimal regardless of file size.
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}

	return hex.EncodeToString(hash.Sum(nil)), nil
}
