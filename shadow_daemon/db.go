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

// InitDB initializes the SQLite database
func InitDB(dbPath string) (*sql.DB, error) {
	db, err := sql.Open("sqlite3", dbPath)
	if err != nil {
		return nil, err
	}

	createTableQuery := `
	CREATE TABLE IF NOT EXISTS files (
		uuid TEXT PRIMARY KEY,
		filename TEXT,
		rel_path TEXT,
		size INTEGER,
		status TEXT, -- 'FULL', 'GHOST', 'SYNCING'
		checksum TEXT,
		last_modified DATETIME,
		last_access DATETIME
	);
	`
	_, err = db.Exec(createTableQuery)
	if err != nil {
		return nil, err
	}

	return db, nil
}

// UpdateOrInsertFile updates existing file or inserts a new one in DB
func UpdateOrInsertFile(db *sql.DB, f *FileData) error {
	query := `
	INSERT INTO files (uuid, filename, rel_path, size, status, checksum, last_modified, last_access)
	VALUES (?, ?, ?, ?, ?, ?, ?, ?)
	ON CONFLICT(uuid) DO UPDATE SET
		size=excluded.size,
		status=excluded.status,
		checksum=excluded.checksum,
		last_modified=excluded.last_modified;
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

// GetFileByUUID retrieves file metadata from DB
func GetFileByUUID(db *sql.DB, uuidStr string) (*FileData, error) {
	row := db.QueryRow("SELECT uuid, filename, rel_path, size, status, checksum, last_modified, last_access FROM files WHERE uuid = ?", uuidStr)
	
	f := &FileData{}
	err := row.Scan(&f.UUID, &f.Filename, &f.RelPath, &f.Size, &f.Status, &f.Checksum, &f.LastModified, &f.LastAccess)
	if err != nil {
		return nil, err
	}
	return f, nil
}
