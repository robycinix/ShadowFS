package main

import (
	"database/sql"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

type FileData struct {
	UUID         string
	Filename     string
	DeviceID     string
	RelPath      string
	Size         int64
	Status       string
	Checksum     string
	LastModified time.Time
	LastAccess   time.Time
}

// InitDB inizializza il database SQLite e crea la tabella se non esiste
func InitDB(dbPath string) (*sql.DB, error) {
	db, err := sql.Open("sqlite3", dbPath)
	if err != nil {
		return nil, err
	}

	// Migrazione: aggiunge device_id se il DB è già esistente senza di essa (errore ignorato se già presente)
	db.Exec("ALTER TABLE files ADD COLUMN device_id TEXT NOT NULL DEFAULT 'default'")

	createTableQuery := `
	CREATE TABLE IF NOT EXISTS files (
		uuid          TEXT PRIMARY KEY,
		filename      TEXT,
		device_id     TEXT NOT NULL DEFAULT 'default',
		rel_path      TEXT,
		size          INTEGER,
		status        TEXT,
		checksum      TEXT,
		last_modified DATETIME,
		last_access   DATETIME,
		UNIQUE(device_id, rel_path)
	);
	CREATE INDEX IF NOT EXISTS idx_files_device_path ON files(device_id, rel_path);
	`
	_, err = db.Exec(createTableQuery)
	if err != nil {
		return nil, err
	}

	return db, nil
}

// UpdateOrInsertFile inserisce o aggiorna un file nel database
func UpdateOrInsertFile(db *sql.DB, f *FileData) error {
	query := `
	INSERT INTO files (uuid, filename, device_id, rel_path, size, status, checksum, last_modified, last_access)
	VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
	ON CONFLICT(uuid) DO UPDATE SET
		filename=excluded.filename,
		device_id=excluded.device_id,
		rel_path=excluded.rel_path,
		size=excluded.size,
		status=excluded.status,
		checksum=excluded.checksum,
		last_modified=excluded.last_modified,
		last_access=excluded.last_access;
	`
	_, err := db.Exec(query,
		f.UUID, f.Filename, f.DeviceID, f.RelPath,
		f.Size, f.Status, f.Checksum, f.LastModified, f.LastAccess,
	)
	return err
}

// GetFileByUUID recupera i metadati di un file tramite UUID
func GetFileByUUID(db *sql.DB, uuidStr string) (*FileData, error) {
	row := db.QueryRow(
		"SELECT uuid, filename, device_id, rel_path, size, status, checksum, last_modified, last_access FROM files WHERE uuid = ?",
		uuidStr,
	)
	f := &FileData{}
	err := row.Scan(&f.UUID, &f.Filename, &f.DeviceID, &f.RelPath, &f.Size, &f.Status, &f.Checksum, &f.LastModified, &f.LastAccess)
	if err != nil {
		return nil, err
	}
	return f, nil
}

// GetFileByRelPath recupera i metadati di un file tramite device_id + percorso relativo.
func GetFileByRelPath(db *sql.DB, deviceID, relPath string) (*FileData, error) {
	row := db.QueryRow(
		"SELECT uuid, filename, device_id, rel_path, size, status, checksum, last_modified, last_access FROM files WHERE device_id = ? AND rel_path = ?",
		deviceID, relPath,
	)
	f := &FileData{}
	err := row.Scan(&f.UUID, &f.Filename, &f.DeviceID, &f.RelPath, &f.Size, &f.Status, &f.Checksum, &f.LastModified, &f.LastAccess)
	if err != nil {
		return nil, err
	}
	return f, nil
}

// DeleteFileByRelPath rimuove un file dal database tramite device_id + percorso relativo
func DeleteFileByRelPath(db *sql.DB, deviceID, relPath string) error {
	_, err := db.Exec("DELETE FROM files WHERE device_id = ? AND rel_path = ?", deviceID, relPath)
	return err
}
