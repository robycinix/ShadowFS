package main

import (
	"database/sql"
	"log"
	"strings"
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
	// WAL mode: letture concorrenti senza bloccare le scritture.
	// busy_timeout: ritenta automaticamente per 5s invece di restituire SQLITE_BUSY.
	db, err := sql.Open("sqlite3", dbPath+"?_journal_mode=WAL&_busy_timeout=5000&_synchronous=NORMAL")
	if err != nil {
		return nil, err
	}

	// Migrazione: aggiunge device_id se il DB è già esistente senza di essa (errore ignorato se già presente)
	db.Exec("ALTER TABLE files ADD COLUMN device_id TEXT NOT NULL DEFAULT 'default'")

	// Migrazione: i DB legacy non hanno il vincolo UNIQUE(device_id, rel_path).
	// Senza di esso ogni UpdateOrInsertFile fallirebbe con
	// "ON CONFLICT clause does not match any PRIMARY KEY or UNIQUE constraint",
	// quindi nessun upload verrebbe mai registrato nel DB.
	if err := migrateUniqueConstraint(db); err != nil {
		return nil, err
	}

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

// migrateUniqueConstraint ricostruisce la tabella files se esiste ma è priva del
// vincolo UNIQUE(device_id, rel_path) richiesto dall'upsert ON CONFLICT.
// In caso di duplicati legacy, vince la riga con last_modified più recente.
func migrateUniqueConstraint(db *sql.DB) error {
	var tableSQL string
	err := db.QueryRow(
		"SELECT sql FROM sqlite_master WHERE type='table' AND name='files'",
	).Scan(&tableSQL)
	if err == sql.ErrNoRows {
		return nil // tabella non ancora creata: la CREATE successiva avrà già il vincolo
	}
	if err != nil {
		return err
	}
	if strings.Contains(strings.ToUpper(strings.ReplaceAll(tableSQL, " ", "")), "UNIQUE(DEVICE_ID,REL_PATH)") {
		return nil // vincolo già presente
	}

	log.Printf("🔧 Migrazione DB: aggiungo vincolo UNIQUE(device_id, rel_path) alla tabella files...")

	// Transazione gestita con db.Begin()/tx: il vecchio approccio con
	// db.Exec("BEGIN; ...") + db.Exec("ROLLBACK") separato poteva eseguire il
	// ROLLBACK su una connessione DIVERSA del pool, lasciando la transazione
	// fallita aperta su quella originale.
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	stmts := []string{
		`CREATE TABLE files_new (
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
		)`,
		`INSERT OR REPLACE INTO files_new
			SELECT uuid, filename, device_id, rel_path, size, status, checksum, last_modified, last_access
			FROM files ORDER BY last_modified ASC`,
		`DROP TABLE files`,
		`ALTER TABLE files_new RENAME TO files`,
	}
	for _, stmt := range stmts {
		if _, err := tx.Exec(stmt); err != nil {
			tx.Rollback()
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	log.Printf("✅ Migrazione DB completata.")
	return nil
}

// UpdateOrInsertFile inserisce o aggiorna un file nel database.
// Il conflitto è risolto su (device_id, rel_path) — la chiave logica del file —
// così funziona anche se l'UUID cambia (es. dopo reinstallazione dell'app).
func UpdateOrInsertFile(db *sql.DB, f *FileData) error {
	query := `
	INSERT INTO files (uuid, filename, device_id, rel_path, size, status, checksum, last_modified, last_access)
	VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
	ON CONFLICT(device_id, rel_path) DO UPDATE SET
		uuid=excluded.uuid,
		filename=excluded.filename,
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
