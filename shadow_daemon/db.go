package main

import (
	"database/sql"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

type FileData struct {
	UUID         string
	Filename     string
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

	createTableQuery := `
	CREATE TABLE IF NOT EXISTS files (
		uuid         TEXT PRIMARY KEY,
		filename     TEXT,
		rel_path     TEXT UNIQUE,
		size         INTEGER,
		status       TEXT,
		checksum     TEXT,
		last_modified DATETIME,
		last_access   DATETIME
	);
	CREATE INDEX IF NOT EXISTS idx_files_rel_path ON files(rel_path);
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
	INSERT INTO files (uuid, filename, rel_path, size, status, checksum, last_modified, last_access)
	VALUES (?, ?, ?, ?, ?, ?, ?, ?)
	ON CONFLICT(uuid) DO UPDATE SET
		filename=excluded.filename,
		rel_path=excluded.rel_path,
		size=excluded.size,
		status=excluded.status,
		checksum=excluded.checksum,
		last_modified=excluded.last_modified,
		last_access=excluded.last_access;
	`
	_, err := db.Exec(query,
		f.UUID,
		f.Filename,
		f.RelPath,
		f.Size,
		f.Status,
		f.Checksum,
		f.LastModified,
		f.LastAccess,
	)
	return err
}

// GetFileByUUID recupera i metadati di un file tramite UUID
func GetFileByUUID(db *sql.DB, uuidStr string) (*FileData, error) {
	row := db.QueryRow(
		"SELECT uuid, filename, rel_path, size, status, checksum, last_modified, last_access FROM files WHERE uuid = ?",
		uuidStr,
	)
	f := &FileData{}
	err := row.Scan(&f.UUID, &f.Filename, &f.RelPath, &f.Size, &f.Status, &f.Checksum, &f.LastModified, &f.LastAccess)
	if err != nil {
		return nil, err
	}
	return f, nil
}

// GetFileByRelPath recupera i metadati di un file tramite percorso relativo.
// Utilizzato dallo scanner e dall'upload handler per evitare UUID duplicati.
func GetFileByRelPath(db *sql.DB, relPath string) (*FileData, error) {
	row := db.QueryRow(
		"SELECT uuid, filename, rel_path, size, status, checksum, last_modified, last_access FROM files WHERE rel_path = ?",
		relPath,
	)
	f := &FileData{}
	err := row.Scan(&f.UUID, &f.Filename, &f.RelPath, &f.Size, &f.Status, &f.Checksum, &f.LastModified, &f.LastAccess)
	if err != nil {
		return nil, err
	}
	return f, nil
}
