package main

import (
	"bytes"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"testing"
)

// initTestDB crea un DB SQLite reale, temporaneo e isolato per ogni test.
func initTestDB(t *testing.T) *sql.DB {
	t.Helper()
	dbPath := filepath.Join(t.TempDir(), "test.db")
	db, err := InitDB(dbPath)
	if err != nil {
		t.Fatalf("InitDB fallito: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

// rwPair simula una connessione: gli handler leggono da in e scrivono su out.
// Gli handler non rileggono mai ciò che scrivono, quindi due buffer distinti
// riproducono fedelmente il comportamento di una net.Conn per questi test.
type rwPair struct {
	in  *bytes.Reader
	out *bytes.Buffer
}

func (p *rwPair) Read(b []byte) (int, error)  { return p.in.Read(b) }
func (p *rwPair) Write(b []byte) (int, error) { return p.out.Write(b) }

// helper: costruisce il frame di upload [2B len][path][8B size][32B sha][bytes]
func buildUploadFrame(relPath string, content []byte) []byte {
	sum := sha256.Sum256(content)
	var buf bytes.Buffer
	pb := []byte(relPath)
	buf.WriteByte(byte(len(pb) >> 8))
	buf.WriteByte(byte(len(pb)))
	buf.Write(pb)
	size := int64(len(content))
	buf.Write([]byte{
		byte(size >> 56), byte(size >> 48), byte(size >> 40), byte(size >> 32),
		byte(size >> 24), byte(size >> 16), byte(size >> 8), byte(size),
	})
	buf.Write(sum[:])
	buf.Write(content)
	return buf.Bytes()
}

func readOffsetAndAck(t *testing.T, out *bytes.Buffer) (offset int64, ack byte) {
	t.Helper()
	off, err := readInt64BE(out)
	if err != nil {
		t.Fatalf("lettura offset fallita: %v", err)
	}
	a, err := out.ReadByte()
	if err != nil {
		t.Fatalf("lettura ACK fallita: %v", err)
	}
	return off, a
}

// ============================================================
// validatePath — protezione path traversal
// ============================================================

func TestValidatePath(t *testing.T) {
	storage := t.TempDir()
	cases := []struct {
		name    string
		relPath string
		wantErr bool
	}{
		{"file normale", "DCIM/Camera/foto.jpg", false},
		{"sottocartella profonda", "a/b/c/d/e.txt", false},
		{"slash iniziale rimosso", "/DCIM/foto.jpg", false},
		{"traversal classico", "../../etc/passwd", true},
		{"traversal nel mezzo", "DCIM/../../secret", true},
		{"traversal solo dots", "..", true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := validatePath(storage, c.relPath)
			if c.wantErr && err == nil {
				t.Fatalf("attendevo errore per %q, ottenuto path %q", c.relPath, got)
			}
			if !c.wantErr && err != nil {
				t.Fatalf("path valido %q rifiutato: %v", c.relPath, err)
			}
		})
	}
}

// ============================================================
// sanitizeCN — CN del certificato usato come componente di path
// ============================================================

func TestSanitizeCN(t *testing.T) {
	cases := map[string]string{
		"":               "default",
		"pixel-7":        "pixel-7",
		"device_01":      "device_01",
		"../evil":        "___evil",
		"a/b\\c":         "a_b_c",
		"nome con spazi": "nome_con_spazi",
		"Tab\tNewline\n": "Tab_Newline_",
	}
	for in, want := range cases {
		if got := sanitizeCN(in); got != want {
			t.Errorf("sanitizeCN(%q) = %q, atteso %q", in, got, want)
		}
	}
}

// ============================================================
// int64 big-endian roundtrip
// ============================================================

func TestInt64BERoundtrip(t *testing.T) {
	values := []int64{0, 1, 255, 256, 4 * 1024 * 1024, 1<<40 + 123, 1 << 62}
	for _, v := range values {
		var b bytes.Buffer
		if err := writeInt64BE(&b, v); err != nil {
			t.Fatalf("write %d: %v", v, err)
		}
		got, err := readInt64BE(&b)
		if err != nil {
			t.Fatalf("read %d: %v", v, err)
		}
		if got != v {
			t.Errorf("roundtrip %d -> %d", v, got)
		}
	}
}

// ============================================================
// handleUpload — protocollo v3 con ACK finale
// ============================================================

func TestHandleUpload_Success(t *testing.T) {
	db := initTestDB(t)
	storage := t.TempDir()
	content := []byte("contenuto del file di test, abbastanza lungo da essere significativo")
	relPath := "DCIM/Camera/foto.jpg"

	p := &rwPair{in: bytes.NewReader(buildUploadFrame(relPath, content)), out: &bytes.Buffer{}}
	handleUpload(p, db, storage, "dev1")

	offset, ack := readOffsetAndAck(t, p.out)
	if offset != 0 {
		t.Errorf("offset atteso 0 per nuovo file, ottenuto %d", offset)
	}
	if ack != 0x01 {
		t.Fatalf("ACK atteso 0x01, ottenuto 0x%02x", ack)
	}

	// File pubblicato sul path finale, nessun .part residuo
	full := filepath.Join(storage, relPath)
	got, err := os.ReadFile(full)
	if err != nil {
		t.Fatalf("file finale non trovato: %v", err)
	}
	if !bytes.Equal(got, content) {
		t.Error("contenuto del file non corrisponde")
	}
	if _, err := os.Stat(full + ".part"); !os.IsNotExist(err) {
		t.Error(".part non rimosso dopo pubblicazione")
	}

	// DB aggiornato con checksum corretto e stato FULL
	rec, err := GetFileByRelPath(db, "dev1", relPath)
	if err != nil {
		t.Fatalf("record DB mancante: %v", err)
	}
	sum := sha256.Sum256(content)
	if rec.Checksum != hex.EncodeToString(sum[:]) {
		t.Error("checksum nel DB errato")
	}
	if rec.Status != "FULL" {
		t.Errorf("status atteso FULL, ottenuto %q", rec.Status)
	}
}

// Il test critico per la perdita dati: su checksum mismatch il server deve
// rispondere NACK (0x00), NON pubblicare il file e rimuovere il .part.
func TestHandleUpload_ChecksumMismatch(t *testing.T) {
	db := initTestDB(t)
	storage := t.TempDir()
	content := []byte("dati reali del file")
	relPath := "DCIM/x.bin"

	// Costruisco un frame con checksum sbagliato (hash di contenuto diverso)
	frame := buildUploadFrame(relPath, content)
	// I 32 byte di checksum stanno subito prima del contenuto.
	csStart := len(frame) - len(content) - sha256.Size
	for i := 0; i < sha256.Size; i++ {
		frame[csStart+i] ^= 0xFF // corrompe il checksum atteso
	}

	p := &rwPair{in: bytes.NewReader(frame), out: &bytes.Buffer{}}
	handleUpload(p, db, storage, "dev1")

	offset, ack := readOffsetAndAck(t, p.out)
	if offset != 0 {
		t.Errorf("offset atteso 0, ottenuto %d", offset)
	}
	if ack != 0x00 {
		t.Fatalf("su checksum mismatch atteso NACK 0x00, ottenuto 0x%02x", ack)
	}

	full := filepath.Join(storage, relPath)
	if _, err := os.Stat(full); !os.IsNotExist(err) {
		t.Error("il file NON deve essere pubblicato su checksum mismatch")
	}
	if _, err := os.Stat(full + ".part"); !os.IsNotExist(err) {
		t.Error(".part corrotto non rimosso")
	}
	if _, err := GetFileByRelPath(db, "dev1", relPath); err == nil {
		t.Error("il DB non deve contenere un file mai pubblicato")
	}
}

// Dimensione finale diversa dall'attesa → NACK, nessuna pubblicazione.
func TestHandleUpload_SizeMismatch(t *testing.T) {
	db := initTestDB(t)
	storage := t.TempDir()
	content := []byte("dieci byte!")
	relPath := "a/b.txt"

	frame := buildUploadFrame(relPath, content)
	// Tronco un byte dal contenuto: il server attende expectedSize ma riceve meno.
	// io.CopyN fallirà con ErrUnexpectedEOF → ritorno senza ACK positivo.
	frame = frame[:len(frame)-1]

	p := &rwPair{in: bytes.NewReader(frame), out: &bytes.Buffer{}}
	handleUpload(p, db, storage, "dev1")

	// Almeno l'offset (8 byte) deve essere stato inviato; l'upload non completa.
	if _, err := readInt64BE(p.out); err != nil {
		t.Fatalf("offset non inviato: %v", err)
	}
	full := filepath.Join(storage, relPath)
	if _, err := os.Stat(full); !os.IsNotExist(err) {
		t.Error("file non deve essere pubblicato con dati incompleti")
	}
}

// Resume: se esiste un .part valido, il server deve restituire il suo offset.
func TestHandleUpload_ResumeOffset(t *testing.T) {
	db := initTestDB(t)
	storage := t.TempDir()
	content := []byte("0123456789ABCDEFGHIJ") // 20 byte
	relPath := "resume/file.dat"

	// Pre-creo un .part con i primi 8 byte già ricevuti.
	full := filepath.Join(storage, relPath)
	if err := os.MkdirAll(filepath.Dir(full), 0755); err != nil {
		t.Fatal(err)
	}
	prefix := content[:8]
	if err := os.WriteFile(full+".part", prefix, 0644); err != nil {
		t.Fatal(err)
	}

	// Il client invia size+checksum dell'intero file, poi SOLO i byte mancanti.
	sum := sha256.Sum256(content)
	var frame bytes.Buffer
	pb := []byte(relPath)
	frame.WriteByte(byte(len(pb) >> 8))
	frame.WriteByte(byte(len(pb)))
	frame.Write(pb)
	size := int64(len(content))
	frame.Write([]byte{
		byte(size >> 56), byte(size >> 48), byte(size >> 40), byte(size >> 32),
		byte(size >> 24), byte(size >> 16), byte(size >> 8), byte(size),
	})
	frame.Write(sum[:])
	frame.Write(content[8:]) // solo i byte mancanti, come farebbe il client dopo l'offset

	p := &rwPair{in: bytes.NewReader(frame.Bytes()), out: &bytes.Buffer{}}
	handleUpload(p, db, storage, "dev1")

	offset, ack := readOffsetAndAck(t, p.out)
	if offset != 8 {
		t.Fatalf("offset di resume atteso 8, ottenuto %d", offset)
	}
	if ack != 0x01 {
		t.Fatalf("ACK atteso 0x01 dopo resume, ottenuto 0x%02x", ack)
	}
	got, err := os.ReadFile(full)
	if err != nil {
		t.Fatalf("file finale assente: %v", err)
	}
	if !bytes.Equal(got, content) {
		t.Error("contenuto dopo resume non corrisponde all'originale")
	}
}

// ============================================================
// handleDownload — idratazione
// ============================================================

func TestHandleDownload_Success(t *testing.T) {
	db := initTestDB(t)
	storage := t.TempDir()
	content := []byte("file da scaricare sul telefono")
	relPath := "DCIM/dl.jpg"

	full := filepath.Join(storage, relPath)
	if err := os.MkdirAll(filepath.Dir(full), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(full, content, 0644); err != nil {
		t.Fatal(err)
	}

	// Richiesta: [2B len][path][8B resumeOffset=0]
	var frame bytes.Buffer
	pb := []byte(relPath)
	frame.WriteByte(byte(len(pb) >> 8))
	frame.WriteByte(byte(len(pb)))
	frame.Write(pb)
	frame.Write(make([]byte, 8)) // offset 0

	p := &rwPair{in: bytes.NewReader(frame.Bytes()), out: &bytes.Buffer{}}
	handleDownload(p, db, storage, "dev1")

	status, err := p.out.ReadByte()
	if err != nil || status != 0x01 {
		t.Fatalf("status atteso 0x01, ottenuto 0x%02x (err %v)", status, err)
	}
	size, err := readInt64BE(p.out)
	if err != nil || size != int64(len(content)) {
		t.Fatalf("size attesa %d, ottenuta %d (err %v)", len(content), size, err)
	}
	csBytes := make([]byte, sha256.Size)
	if _, err := io.ReadFull(p.out, csBytes); err != nil {
		t.Fatalf("checksum non inviato: %v", err)
	}
	sum := sha256.Sum256(content)
	if !bytes.Equal(csBytes, sum[:]) {
		t.Error("checksum inviato errato")
	}
	body := p.out.Bytes()
	if !bytes.Equal(body, content) {
		t.Errorf("corpo file errato: %q", body)
	}
}

func TestHandleDownload_NotFound(t *testing.T) {
	db := initTestDB(t)
	storage := t.TempDir()

	var frame bytes.Buffer
	pb := []byte("inesistente.bin")
	frame.WriteByte(byte(len(pb) >> 8))
	frame.WriteByte(byte(len(pb)))
	frame.Write(pb)
	frame.Write(make([]byte, 8))

	p := &rwPair{in: bytes.NewReader(frame.Bytes()), out: &bytes.Buffer{}}
	handleDownload(p, db, storage, "dev1")

	status, err := p.out.ReadByte()
	if err != nil || status != 0x00 {
		t.Fatalf("file mancante: atteso status 0x00, ottenuto 0x%02x", status)
	}
}

// ============================================================
// handleDelete
// ============================================================

func TestHandleDelete(t *testing.T) {
	db := initTestDB(t)
	storage := t.TempDir()
	relPath := "DCIM/del.jpg"
	full := filepath.Join(storage, relPath)
	if err := os.MkdirAll(filepath.Dir(full), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(full, []byte("x"), 0644); err != nil {
		t.Fatal(err)
	}
	UpdateOrInsertFile(db, &FileData{UUID: "u1", DeviceID: "dev1", RelPath: relPath, Status: "FULL"})

	var frame bytes.Buffer
	pb := []byte(relPath)
	frame.WriteByte(byte(len(pb) >> 8))
	frame.WriteByte(byte(len(pb)))
	frame.Write(pb)

	p := &rwPair{in: bytes.NewReader(frame.Bytes()), out: &bytes.Buffer{}}
	handleDelete(p, db, storage, "dev1")

	status, err := p.out.ReadByte()
	if err != nil || status != 0x01 {
		t.Fatalf("delete: atteso OK 0x01, ottenuto 0x%02x", status)
	}
	if _, err := os.Stat(full); !os.IsNotExist(err) {
		t.Error("file non eliminato dal disco")
	}
	if _, err := GetFileByRelPath(db, "dev1", relPath); err == nil {
		t.Error("record non rimosso dal DB")
	}
}
