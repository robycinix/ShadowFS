# ShadowFS — Self-Hosted File Ghosting System

> Libera spazio sul tuo telefono Android inviando i file sul tuo Raspberry Pi di casa, in modo automatico, trasparente e crittografato. Come iCloud, ma tutto tuo.

---

## Come funziona

```
[File > 512KB, non usato da 3 giorni]
        ↓
[Android lo invia al Raspberry via TLS 1.3 + mTLS]
        ↓
[Android sostituisce il file con un thumbnail (immagini/video)
 o lo tronca a 0 byte (altri tipi) e crea un marker .shadow]
        ↓
[Utente apre il file → ShadowFS lo riscarica silenziosamente]
        ↓
[Dopo 1 ora → il file viene ri-ghostato automaticamente]
```

Il tutto è **trasparente**: la galleria mostra le anteprime, i file sono apribili, lo spazio viene recuperato.

---

## Architettura

```
SMARTPHONE ANDROID                    RASPBERRY PI
══════════════════                    ════════════

┌─────────────────────┐               ┌──────────────────────┐
│   App ShadowFS      │  TLS 1.3      │   shadowdaemon (Go)  │
│   (Kotlin)          │◄─────────────►│   porta 4243         │
│                     │  mTLS         │                      │
│  ForegroundService  │               │  Upload / Download   │
│  HydrationManager   │               │  Delete / SyncIndex  │
│  ShadowClient       │               │  SQLite DB           │
│  VfsManager         │               │  /storage/shadow_root│
└─────────────────────┘               └──────────────────────┘

Rete: LAN Wi-Fi (IP locale) oppure ovunque con Tailscale VPN
```

### Protocollo binario (TCP stream)

| Comando | Byte | Descrizione |
|---------|------|-------------|
| Upload   | `0x01` | Android → Raspberry: invia il file |
| Download | `0x02` | Android ← Raspberry: scarica il file |
| Delete   | `0x03` | Elimina file dal Raspberry e dal DB |
| SyncIndex| `0x04` | Confronta indici per trovare orfani |

---

## Struttura del Repository

```
ShadowFS/
├── README.md                    ← questo file
├── Manuale_Utente.md            ← guida per l'utente finale
│
├── proto/
│   └── shadow.proto             # Definizione Protobuf (uso futuro)
│
├── shadow_daemon/               # Server Go — Raspberry Pi
│   ├── main.go                  # Entrypoint + flag CLI
│   ├── server.go                # TCP+TLS server + handler comandi
│   ├── certs.go                 # Generazione certificati mTLS (PKCS#8)
│   ├── scanner.go               # Scansione cartelle + SHA-256
│   ├── db.go                    # SQLite: CRUD + query
│   ├── go.mod / go.sum          # Dipendenze Go
│   └── install_raspberry.sh    # Installer automatico
│
└── shadow_client/               # App Android — Kotlin
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/shadowfs/client/
            ├── ShadowClient.kt       # Client TLS+mTLS (upload/download)
            ├── MainActivity.kt       # UI principale
            ├── ForegroundService.kt  # Ghosting automatico in background
            ├── HydrationManager.kt   # FileObserver + idratazione on-demand
            ├── VfsManager.kt         # Troncamento file + thumbnail
            ├── GhostListActivity.kt  # Lista file ghostati + garbage collector
            └── QrScanActivity.kt     # Pairing via QR Code
```

---

## Setup Raspberry Pi

### Prerequisiti
- Raspberry Pi con Raspberry Pi OS
- Go 1.21+ (installato automaticamente dallo script)
- Connessione internet

### Installazione

```bash
cd /home/<utente>/ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```

Lo script fa automaticamente:
1. Installa Go se mancante
2. Compila il daemon (`go mod tidy && go build`)
3. Genera i certificati mTLS con IP LAN e Tailscale nel SAN
4. Installa come servizio systemd (avvio automatico al boot)
5. Apre la porta 4243 nel firewall
6. Stampa i comandi per copiare i certificati sul telefono

### Tailscale (raccomandato)

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
tailscale ip -4   # annota questo IP — ti serve nell'app
```

Con Tailscale, ShadowFS funziona sia a casa che da remoto senza configurare porte del router.

---

## Setup Android

1. Apri **Android Studio** → **Open** → seleziona `ShadowFS/shadow_client`
2. Aspetta il Gradle Sync
3. Collega il telefono via USB con **Debug USB** abilitato
4. Premi **▶ Run**

### Copia i certificati

```bash
adb push /opt/shadowfs/certs_for_android/ca.crt     /sdcard/Download/shadowfs_certs/ca.crt
adb push /opt/shadowfs/certs_for_android/client.crt /sdcard/Download/shadowfs_certs/client.crt
adb push /opt/shadowfs/certs_for_android/client.key /sdcard/Download/shadowfs_certs/client.key
```

---

## Comandi utili — Raspberry Pi

```bash
systemctl status shadowfs          # stato del servizio
journalctl -fu shadowfs            # log in tempo reale
systemctl restart shadowfs         # riavvio

sqlite3 /opt/shadowfs/shadowfs.db \
  "SELECT COUNT(*), SUM(size)/1048576 || ' MB' FROM files;"

du -sh /storage/shadow_root/       # spazio usato

# Rigenera certificati (dopo cambio IP)
sudo ./shadowdaemon --generate-certs \
  --server-ip="$(tailscale ip -4),$(hostname -I | awk '{print $1}')"
sudo systemctl restart shadowfs
```

---

## Comandi utili — Android (ADB)

```bash
adb logcat -s ShadowFS ShadowClient HydrationManager VfsManager
adb shell "find /storage/emulated/0 -name '*.shadow' | wc -l"
adb shell appops set com.shadowfs.client MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant com.shadowfs.client android.permission.POST_NOTIFICATIONS
```

---

## Sicurezza

- **mTLS bidirezionale**: server e client si autenticano con certificati X.509
- **TLS 1.3**: crittografia moderna, nessuna versione legacy
- **CA privata**: certificati generati localmente, non da CA pubbliche
- **Nessun cloud di terzi**: i dati non escono mai dalla tua rete privata

---

## Troubleshooting

| Sintomo | Causa | Soluzione |
|---------|-------|-----------|
| `Connection refused` | Daemon non avviato | `systemctl start shadowfs` |
| `PKIX path building failed` | IP non nel certificato SAN | Rigenera certificati con `--server-ip` corretto |
| `bad certificate` | Client cert non corrisponde alla CA | Rigenera tutti i certificati |
| `Certificati mancanti` (app) | File non nella cartella giusta | Verifica `Download/shadowfs_certs/` |
| File non si tronca | Permesso `MANAGE_EXTERNAL_STORAGE` mancante | Impostazioni → App → ShadowFS → Permessi |
| Idratazione indesiderata | Galleria in prefetching | Rate limiter attivo — normale |

---

## Roadmap futura

- [ ] QUIC invece di TCP (migrazione WiFi→4G senza interruzioni)
- [ ] Protobuf per il protocollo (versioning, robustezza)
- [ ] Pinning cartelle dall'UI
- [ ] Backup incrementale (solo diff)

---

*ShadowFS — perché i tuoi file sono tuoi.*
