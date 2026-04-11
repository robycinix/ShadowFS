# ShadowFS — Stato del Progetto e Guida alla Continuazione

**Data ultimo aggiornamento:** 11 Aprile 2026  
**Versione daemon:** 2.0 (TCP+mTLS)  
**Stato:** ✅ Architettura completa e funzionante — pronta per il test P2P reale

---

## 1. COS'È SHADOWFS (Riassunto Concettuale)

ShadowFS è un sistema di **File Ghosting Intelligente** completamente self-hosted tra un telefono Android e un Raspberry Pi di casa. Funziona esattamente come iCloud o OneDrive nella funzione "Ottimizza spazio su iPhone/Mac", ma:

- I file vanno sul **tuo** Raspberry Pi, non su server di terze parti
- La connessione è crittografata con **mTLS** (doppio certificato, server e client)
- Il trasporto avviene via **TLS 1.3 su TCP** (stabile, nativo su Android)
- Funziona sia in LAN che da remoto tramite **Tailscale** (VPN mesh gratuita)

**Il flusso completo:**

```
[File > 512KB, non usato da 3 giorni]
        ↓
[Android lo manda al Raspberry via TLS]
        ↓
[Android tronca il file a 0 byte, crea "foto.jpg.shadow"]
        ↓
[Utente apre foto.jpg → FileObserver rileva l'accesso]
        ↓
[Android scarica il file dal Raspberry silenziosamente]
        ↓
[Dopo 1 ora → il file viene ri-ghostato automaticamente]
```

---

## 2. COSA È STATO FATTO IN QUESTA SESSIONE

### 2.1 Problemi critici risolti (erano bloccanti)

| # | Problema | Dove | Fix applicato |
|---|----------|------|---------------|
| 1 | `MainActivity.kt` mancante | Android | Creato da zero con UI completa |
| 2 | `QuicClientMock` falso | Android | Sostituito con `ShadowClient.kt` reale |
| 3 | Nessun client di rete reale | Android | `ShadowClient.kt`: SSLSocket + mTLS nativo |
| 4 | Cartella `res/` mancante | Android | Creati `activity_main.xml` e `strings.xml` |
| 5 | Dipendenze gRPC inutili | Android | Rimosse da `build.gradle.kts` |
| 6 | Bug UUID duplicati nello scanner | Go | `scanner.go`: controlla DB prima di generare UUID |
| 7 | `handleUpload` non aggiornava il DB | Go | `server.go`: aggiorna DB con checksum SHA-256 |
| 8 | Certificati senza IP nel SAN | Go | `certs.go`: PKCS#8 + `--server-ip` flag |
| 9 | Nessun server per Android (QUIC difficile) | Go | `server.go`: TCP+TLS sulla porta 4243 |
| 10 | Script install incompleto | Go | `install_raspberry.sh`: rileva IP automaticamente |

### 2.2 File creati o modificati

**Lato Raspberry Pi (`shadow_daemon/`):**
- `main.go` → Aggiunto `--tcp-addr` e `--server-ip` flags
- `server.go` → **Riscritto**: server TCP+mTLS, handler su `io.ReadWriter`, DB aggiornato
- `certs.go` → **Riscritto**: PKCS#8, IP nel SAN, `shadowdaemon` come DNS name
- `scanner.go` → **Fix bug UUID**: controlla `rel_path` nel DB prima di generare UUID
- `db.go` → Aggiunta `GetFileByRelPath()` + indice SQL su `rel_path`
- `install_raspberry.sh` → **Riscritto**: rileva IP LAN e Tailscale, stampa comandi `adb push`

**Lato Android (`shadow_client/`):**
- `ShadowClient.kt` → **NUOVO**: client TLS+mTLS reale (upload + download + test connessione)
- `MainActivity.kt` → **NUOVO**: UI con config IP, permessi, start/stop, force offload
- `ForegroundService.kt` → Usa `ShadowClient.upload()` invece del mock
- `HydrationManager.kt` → Usa `ShadowClient.download()` invece del mock
- `build.gradle.kts` → Rimossi gRPC, solo dipendenze necessarie
- `res/layout/activity_main.xml` → **NUOVO**: layout scuro con tutti i controlli
- `res/values/strings.xml` → **NUOVO**: stringhe dell'app

---

## 3. ARCHITETTURA ATTUALE

```
SMARTPHONE ANDROID                    RASPBERRY PI (server)
══════════════════                    ═════════════════════

┌─────────────────────┐               ┌──────────────────────┐
│   MainActivity      │               │   shadowdaemon       │
│   (config + UI)     │               │   (Go binary)        │
├─────────────────────┤               ├──────────────────────┤
│ ShadowForeground    │  TLS 1.3      │  StartTCPServer()    │
│ Service             │◄─────────────►│  porta 4243          │
│ (ogni ora)          │  mTLS (cert   ├──────────────────────┤
├─────────────────────┤  bilaterale)  │  handleUpload()      │
│ HydrationManager    │               │  → scrive file       │
│ (FileObserver)      │               │  → aggiorna SQLite   │
├─────────────────────┤               ├──────────────────────┤
│ VfsManager          │               │  handleDownload()    │
│ (truncate a 0 byte) │               │  → legge file        │
├─────────────────────┤               │  → invia via TLS     │
│ ShadowClient        │               ├──────────────────────┤
│ (SSLSocket mTLS)    │               │  SQLite DB           │
└─────────────────────┘               │  (metadati file)     │
                                      ├──────────────────────┤
        Rete: LAN Wi-Fi               │  /storage/shadow_root│
        oppure Tailscale VPN          │  (file fisici)       │
                                      └──────────────────────┘

PROTOCOLLO BINARIO (TCP stream):
  Upload:   [0x01][2B len path][path UTF-8][file bytes...][TCP close]
  Download: [0x02][2B len path][path UTF-8] → [0x01][file bytes...] oppure [0x00]
```

---

## 4. COME AVVIARE IL SISTEMA — GUIDA PASSO-PASSO

### FASE 1: Setup Raspberry Pi

**Prerequisiti:** Il Raspberry deve essere acceso e raggiungibile via SSH.  
**Consigliato:** Installa Tailscale sul Raspberry prima di procedere.

```bash
# Installa Tailscale (facoltativo ma raccomandato per uso fuori casa)
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
# Nota l'IP 100.x.x.x mostrato — ti servirà nell'app Android

# Trasferisci il progetto sul Raspberry (da Windows/Mac con sftp, FileZilla, o chiavetta USB)
# es. copia tutta la cartella ShadowFS in /home/pi/ShadowFS/

# Vai nella cartella del progetto
cd /home/pi/ShadowFS

# Esegui l'installer (rileva automaticamente gli IP e genera i certificati)
sudo chmod +x shadow_daemon/install_raspberry.sh
cd shadow_daemon
sudo ./install_raspberry.sh
```

**L'installer farà automaticamente:**
1. Installa Go 1.21 se mancante
2. Compila il daemon (`go mod tidy && go build`)
3. Genera i certificati mTLS con gli IP del Raspberry inclusi
4. Installa come servizio systemd (si avvia automaticamente al boot)
5. Apre la porta TCP 4243 sul firewall
6. Stampa i comandi `adb push` per copiare i certificati sul telefono

### FASE 2: Copia i Certificati sul Telefono

Dopo l'installer, trovi 3 file in `/opt/shadowfs/certs_for_android/`:

```bash
# Opzione A — via ADB (se hai Android Debug Bridge sul PC)
adb push /opt/shadowfs/certs_for_android/ca.crt     /sdcard/Download/shadowfs_certs/ca.crt
adb push /opt/shadowfs/certs_for_android/client.crt /sdcard/Download/shadowfs_certs/client.crt
adb push /opt/shadowfs/certs_for_android/client.key /sdcard/Download/shadowfs_certs/client.key

# Opzione B — via FileZilla/WinSCP
# Copia i 3 file dal Raspberry e mettili manualmente in:
# /sdcard/Download/shadowfs_certs/  (crea la cartella se non esiste)
```

> ⚠️ **Importante:** La cartella deve chiamarsi esattamente `shadowfs_certs` dentro `Download`.  
> L'app Android la cerca in: `/storage/emulated/0/Download/shadowfs_certs/`

### FASE 3: Compila e Installa l'App Android

1. Apri **Android Studio** → **Open** → seleziona la cartella `ShadowFS/shadow_client`
2. Aspetta che il Gradle Sync finisca (prima volta: scarica le dipendenze)
3. Collega il telefono via USB con **Debug USB abilitato** nelle Impostazioni Sviluppatore
4. Premi il tasto **▶ Run** (oppure: `Build → Build APK → installa l'apk manualmente`)

### FASE 4: Primo Avvio dell'App

1. Apri l'app **ShadowFS**
2. **Inserisci l'IP del Raspberry** (l'IP Tailscale `100.x.x.x` se lo hai, altrimenti l'IP LAN) e lascia la porta `4243`
3. Premi **Salva Configurazione**
4. Premi **Richiedi Permesso Gestione File** → nel menu di sistema, attiva il permesso
5. Premi **🔌 Testa Connessione mTLS** → deve comparire `🟢 Connesso a 100.x.x.x:4243 ✓`
6. Premi **▶ Avvia Shadow Daemon** → la notifica persistente compare in alto
7. Per testare subito: premi **⚡ Libera Spazio Adesso**

### FASE 5: Verificare che Funzioni

```bash
# Sul Raspberry, controlla i log in tempo reale:
journalctl -fu shadowfs

# Dovresti vedere qualcosa come:
# 🔒 [TCP] Nuova connessione mTLS da: 100.x.x.x:xxxxx
# [Upload] Ricezione file in corso...
# ✅ [Upload] File salvato: /storage/shadow_root/DCIM/Camera/video.mp4 (104857600 bytes)
# [Upload] 📦 DB aggiornato per 'DCIM/Camera/video.mp4'
```

Sul telefono, controlla con il File Manager che il file sia diventato **0 byte** e che vicino ad esso esista `video.mp4.shadow`.

---

## 5. PROSSIMI PASSI (Da Fare)

Queste funzionalità non sono ancora implementate ma sono il naturale passo successivo:

### 5.1 Immediati (prossima sessione)

**A. Gestione `go.sum`**  
Il file `go.sum` non è nel repo. L'installer lo genera con `go mod tidy`, ma se si vuole committarlo nel repository:
```bash
cd shadow_daemon
go mod tidy  # genera go.sum
git add go.sum
git commit -m "Add go.sum for reproducible builds"
```

**B. Notifica all'utente se il Daemon Android non riesce a connettersi**  
Attualmente se `ShadowClient.upload()` fallisce, il file NON viene ghostato e viene solo scritto un log. Bisogna aggiungere una notifica Android visibile all'utente per sapere se il Raspberry è irraggiungibile.

**C. Lista file ghostati nell'app**  
Aggiungere una schermata nella MainActivity che mostra:
- Lista dei file ghostati (leggendo i `.shadow` marker)
- Dimensione totale recuperata
- Stato connessione con il Raspberry

**D. Progress bar durante upload grandi**  
`ShadowClient.upload()` non dà feedback di avanzamento. Per file da 1GB+ è utile aggiungere una progress notification con `NotificationCompat.Builder.setProgress()`.

### 5.2 Medio termine

**E. mDNS Discovery (LAN automatica)**  
Invece di inserire l'IP manualmente nell'app, il Raspberry potrebbe annunciarsi sulla rete locale via mDNS/Zeroconf (`avahi-daemon`) e l'app Android potrebbe scoprirlo automaticamente con `android.net.nsd.NsdManager`.

Lato Raspberry:
```bash
sudo apt install avahi-daemon
# Configurare avahi per annunciare il servizio TCP su porta 4243
```

Lato Android (in `MainActivity.kt`):
```kotlin
val nsdManager = getSystemService(NSD_SERVICE) as NsdManager
nsdManager.discoverServices("_shadowfs._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
```

**F. QR Code per il Pairing**  
Invece di copiare manualmente i 3 file certificato, il Raspberry potrebbe generare un QR Code contenente:
- I certificati compressi in base64
- L'IP del server
- La porta

L'app Android scansiona il QR Code e configura tutto automaticamente.

**G. Protobuf per il Protocollo**  
Il protocollo attuale usa byte grezzi. Il passaggio a Protocol Buffers (già definito in `proto/shadow.proto`) migliorerebbe la robustezza e permetterebbe di aggiungere facilmente nuovi tipi di messaggi (es. SYNC_INDEX, handshake, versioning).

### 5.3 Lungo termine

**H. QUIC su Android**  
Il README originale prevedeva QUIC. Per Android, la via più pratica è usare `OkHttp 5.x` con HTTP/3 support (che usa QUIC internamente):
```kotlin
// build.gradle.kts
implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
```
Questo permetterebbe la migrazione di sessione durante i cambi di rete (WiFi → 4G) senza interrompere un trasferimento in corso.

**I. Pinning Cartelle dall'App**  
Attualmente le cartelle "pinnate" (mai ghostate) sono hardcodate in `ForegroundService.kt`. Aggiungere un'UI per selezionare le cartelle da proteggere e salvarle in `SharedPreferences`.

**J. Garbage Collector sul Raspberry**  
Se un file viene eliminato dall'Android, il suo equivalente rimane sul Raspberry. Bisogna un meccanismo di sincronizzazione degli indici (comando `SYNC_INDEX` già nel proto) per mantenere i due sistemi allineati.

---

## 6. DETTAGLI TECNICI IMPORTANTI

### 6.1 Il protocollo di comunicazione (low-level)

```
UPLOAD (cmd 0x01):
  Android → Raspberry:
    Byte 0:    0x01 (comando upload)
    Byte 1-2:  lunghezza path (big-endian, max 4095 chars)
    Byte 3..N: path UTF-8 relativo (es. "DCIM/Camera/video.mp4")
    Byte N+1..: contenuto del file (stream raw)
    [chiusura socket TCP → EOF per il Go server]
  
  Raspberry riceve, scrive il file, aggiorna SQLite, chiude.

DOWNLOAD (cmd 0x02):
  Android → Raspberry:
    Byte 0:    0x02 (comando download)
    Byte 1-2:  lunghezza path (big-endian)
    Byte 3..N: path UTF-8 relativo
    [flush, ma socket rimane aperto]
  
  Raspberry → Android:
    Byte 0:    0x01 (file trovato) oppure 0x00 (non trovato)
    Byte 1..:  contenuto del file (se 0x01)
    [chiusura socket TCP dal server]
```

### 6.2 Dove stanno i certificati

```
SUL RASPBERRY PI:
  /opt/shadowfs/certs/
    ca.crt      → CA (Certificate Authority privata)
    server.crt  → Certificato server (con IP Tailscale/LAN nel SAN)
    server.key  → Chiave privata server (PKCS#8)
    client.crt  → Certificato client Android
    client.key  → Chiave privata client (PKCS#8, copiare sull'Android)
  
  /opt/shadowfs/certs_for_android/
    ca.crt      → Copia per l'Android (autorità di fiducia)
    client.crt  → Identità del client Android
    client.key  → Chiave privata client (PKCS#8 — leggibile da Android SSLSocket)

SULL'ANDROID:
  /storage/emulated/0/Download/shadowfs_certs/
    ca.crt      → ShadowClient lo usa come TrustStore
    client.crt  → ShadowClient lo usa come identità client
    client.key  → Chiave PKCS#8 PEM (letta con KeyFactory.getInstance("RSA"))
```

### 6.3 SharedPreferences Android (configurazione salvata)

```kotlin
// Nome del file preferences: "shadowfs_config"
// Chiavi:
//   "server_ip"   → String: IP del Raspberry (es. "100.80.33.12")
//   "server_port" → Int: porta TCP (default 4243)
```

### 6.4 Marker file usati dal sistema

| File | Significato |
|------|-------------|
| `foto.jpg` (0 byte) | File "ghost" — il contenuto è sul Raspberry |
| `foto.jpg.shadow` | Metadata: dimensione originale, stato, timestamp |
| `foto.jpg.reghost` | File idratato temporaneamente — contiene timestamp idratazione |

Il **re-ghost** avviene quando `ForegroundService` trova un file `.reghost` con timestamp > 1 ora fa: ri-carica il file sul Raspberry e lo ri-tronca a 0 byte.

### 6.5 Comandi utili lato Raspberry

```bash
# Stato del servizio
systemctl status shadowfs

# Log in tempo reale
journalctl -fu shadowfs

# Riavvio del servizio
systemctl restart shadowfs

# Visualizza il database (file tracciati)
sqlite3 /opt/shadowfs/shadowfs.db "SELECT rel_path, size, status, checksum FROM files ORDER BY last_modified DESC LIMIT 20;"

# Spazio usato dallo storage
du -sh /storage/shadow_root/

# Rigenera i certificati (es. dopo cambio IP Tailscale)
cd /opt/shadowfs
./shadowdaemon --generate-certs --server-ip="$(tailscale ip -4),$(hostname -I | awk '{print $1}')"
systemctl restart shadowfs
```

---

## 7. TROUBLESHOOTING

| Sintomo | Causa probabile | Soluzione |
|---------|----------------|-----------|
| `🔴 Errore: Connection refused` | Daemon non avviato o porta sbagliata | `systemctl status shadowfs` sul Raspberry |
| `🔴 Errore: PKIX path building failed` | Certificato server non ha l'IP nel SAN | Rigenera cert con `--server-ip=<IP corretto>` |
| `🔴 Errore: bad certificate` | Client cert non corrisponde alla CA | Rigenera tutti i certificati e ricopia sul telefono |
| `❌ Certificati mancanti` (nell'app) | I 3 file non sono nella cartella giusta | Controlla che esistano in `Download/shadowfs_certs/` |
| Upload fallisce silenziosamente | IP server sbagliato nelle preferences | Controlla in MainActivity → testa connessione |
| File non si tronca a 0 byte | Permesso `MANAGE_EXTERNAL_STORAGE` mancante | Vai in Impostazioni → App → ShadowFS → Permessi |
| `handleUpload` riceve 0 bytes | Android ha chiuso il socket troppo presto | Verificare che `out.flush()` sia prima del `use{}` close |
| Daemon crasha all'avvio | `certs/` non esiste | Eseguire prima `--generate-certs` |

---

## 8. STRUTTURA DEL REPOSITORY (stato attuale)

```
ShadowFS/
├── README.md                    # Blueprint architetturale originale
├── NETWORK_GUIDE.md             # Guida Tailscale e Protobuf
├── TEST_GUIDE.md                # Guida ai test iniziali
├── TODO_DOMANI.md               # Note della sessione precedente
├── STATO_PROGETTO_E_GUIDA.md   # ← QUESTO FILE
│
├── proto/
│   └── shadow.proto             # Definizione Protobuf (non ancora usata nel codice)
│
├── shadow_daemon/               # Server Go per Raspberry Pi
│   ├── main.go                  # Entrypoint + flags CLI
│   ├── server.go                # TCP+TLS server + QUIC server + handlers
│   ├── certs.go                 # Generazione certificati mTLS (PKCS#8)
│   ├── scanner.go               # Scansione cartelle + calcolo SHA-256
│   ├── db.go                    # SQLite: init, CRUD, query per relPath
│   ├── go.mod                   # Dipendenze Go
│   └── install_raspberry.sh    # Installer automatico per Raspberry Pi
│
└── shadow_client/               # App Android (Kotlin)
    ├── build.gradle.kts         # Dipendenze (solo AndroidX, no gRPC)
    ├── settings.gradle.kts      # Configurazione progetto
    └── src/main/
        ├── AndroidManifest.xml  # Permessi + dichiarazione componenti
        ├── java/com/shadowfs/client/
        │   ├── ShadowClient.kt      # ★ Client TLS+mTLS reale
        │   ├── MainActivity.kt      # ★ UI: config, permessi, controllo
        │   ├── ForegroundService.kt # Servizio background (ghosting ogni ora)
        │   ├── HydrationManager.kt  # FileObserver + idratazione on-demand
        │   └── VfsManager.kt        # Troncamento file a 0 byte
        └── res/
            ├── layout/activity_main.xml  # Layout schermata principale
            └── values/strings.xml        # Stringhe app
```

---

*Fine del documento — aggiornare questa guida dopo ogni sessione di sviluppo.*
