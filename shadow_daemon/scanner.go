package main

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
)

// ScanFolder scansiona la directory e aggiorna i metadati nel DB.
// Non genera UUID duplicati: riusa l'UUID esistente se il file è già noto.
func ScanFolder(db *sql.DB, rootPath string) error {
	return filepath.Walk(rootPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			// Continua la scansione anche in caso di errori su singoli file/cartelle
			// (es. permessi negati su sottocartelle di sistema).
			log.Printf("⚠️ Errore accesso a %s: %v — scansione continua", path, err)
			return nil
		}
		if info.IsDir() {
			return nil
		}
		// Salta i file temporanei di upload (.part): sono parziali riprendibili,
		// non file completi. Indicizzarli inquinerebbe il DB con record fantasma
		// che SyncIndex segnalerebbe come orfani.
		if strings.HasSuffix(path, ".part") {
			return nil
		}
		if err := analyzeFile(db, rootPath, path, info); err != nil {
			log.Printf("⚠️ Errore analisi file %s: %v", path, err)
		}
		return nil
	})
}

func analyzeFile(db *sql.DB, rootPath, filePath string, info os.FileInfo) error {
	fullRelPath, err := filepath.Rel(rootPath, filePath)
	if err != nil {
		return err
	}

	// I file sono organizzati in shadow_root/<deviceID>/relPath.
	// Estrai deviceID dal primo componente del percorso relativo.
	// File direttamente in shadow_root (senza sottocartella device) vengono saltati.
	parts := strings.SplitN(fullRelPath, string(filepath.Separator), 2)
	if len(parts) < 2 {
		return nil // file nella root di storage, non appartiene a nessun device
	}
	deviceID := parts[0]
	relPath := parts[1]

	// Controlla se il file esiste già nel DB tramite (deviceID, relPath).
	// Se esiste, riusa il suo UUID — evita di creare record duplicati ad ogni scansione.
	existingFile, dbErr := GetFileByRelPath(db, deviceID, relPath)
	var fileUUID string
	if dbErr == nil {
		fileUUID = existingFile.UUID
	} else {
		fileUUID = uuid.New().String()
	}

	hashStr, err := calculateSHA256(filePath)
	if err != nil {
		return err
	}

	f := &FileData{
		UUID:         fileUUID,
		Filename:     info.Name(),
		DeviceID:     deviceID,
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
