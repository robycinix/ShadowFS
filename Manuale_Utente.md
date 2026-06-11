# ShadowFS — Manuale Utente

**ShadowFS** sposta automaticamente i tuoi file sul Raspberry Pi di casa quando non li usi, e te li restituisce quando ti servono. Lo spazio sul telefono si libera, i file restano tuoi: niente abbonamenti, niente cloud di terzi.

> 🛡️ **Garanzia fondamentale**: un file viene "ghostato" (svuotato sul telefono) **solo dopo** che il Raspberry ha confermato di averlo ricevuto, verificato byte per byte (checksum SHA-256) e salvato su disco. Se qualcosa va storto durante l'invio, il file sul telefono resta intatto. Sempre.

---

## Prima di iniziare — cosa ti serve

- Un **Raspberry Pi** acceso e collegato a internet
- Un **telefono Android** (Android 10 o superiore)
- La **app ShadowFS** installata sul telefono
- Una connessione alla stessa rete Wi-Fi (solo per il primo setup)

---

## Parte 1 — Setup del Raspberry Pi (una volta sola)

### Passo 1 — Installa Tailscale sul Raspberry

Tailscale ti permette di raggiungere il Raspberry anche fuori casa (4G, Wi-Fi del bar, ecc.).

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

Annota l'IP che compare (es. `100.74.44.19`) — ti servirà dopo.

### Passo 2 — Esegui l'installer

```bash
cd /home/<utente>/ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```

L'installer fa tutto da solo: installa Go, compila il programma, genera i certificati di sicurezza, avvia il servizio e mostra il **QR Code di pairing** nel terminale.

### Passo 3 — Verifica che funzioni

```bash
systemctl status shadowfs
```

Deve mostrare `active (running)`. Se è così, il Raspberry è pronto.

---

## Parte 2 — Setup del telefono (una volta sola)

### Passo 1 — Installa Tailscale sul telefono

1. Apri il **Play Store**, cerca **Tailscale** e installala
2. Accedi con lo **stesso account** usato sul Raspberry
3. Attiva la VPN quando richiesto

Ora telefono e Raspberry si vedono tramite i loro IP `100.x.x.x`, ovunque tu sia.

### Passo 2 — Collega l'app al Raspberry

**Metodo consigliato — QR Code (zero configurazione):**

1. Sul Raspberry: `journalctl -u shadowfs | grep "PAIRING"` oppure riavvia il servizio per rivedere il QR (`sudo systemctl restart shadowfs && journalctl -fu shadowfs`)
2. Nell'app premi **Pairing via QR Code** e inquadra il codice
3. Fatto: IP, porta e certificati si configurano da soli

> Il QR vale **5 minuti** e funziona **una sola volta** (per sicurezza). Se scade, riavvia il servizio sul Raspberry per generarne uno nuovo.

**Metodo manuale (alternativo):** copia i 3 certificati via USB e inserisci l'IP a mano:

```bash
adb push /opt/shadowfs/certs_for_android/ca.crt     /sdcard/Download/shadowfs_certs/ca.crt
adb push /opt/shadowfs/certs_for_android/client.crt /sdcard/Download/shadowfs_certs/client.crt
adb push /opt/shadowfs/certs_for_android/client.key /sdcard/Download/shadowfs_certs/client.key
```

Poi nell'app: inserisci l'IP Tailscale, lascia la porta `4243`, premi **Salva**. Al primo avvio l'app importa i certificati nel proprio storage privato.

### Passo 3 — Concedi i permessi

Al primo avvio l'app ti guida in 3 passaggi: **notifiche**, **accesso a tutti i file** e **esclusione dall'ottimizzazione batteria** (serve perché Android non sospenda ShadowFS in background). Segui le schermate.

### Passo 4 — Testa e avvia

1. Premi **Testa Connessione** → deve apparire `Connesso ✓` in verde
2. Premi **Avvia Shadow Daemon** → compare la notifica persistente "ShadowFS Attivo"

---

## Parte 3 — Uso quotidiano

### Il ghosting automatico

ShadowFS controlla la memoria **ogni ora**. Quando lo spazio libero scende **sotto il 15%**, invia al Raspberry i file che:

- pesano più di **512 KB**
- non vengono usati da almeno **3 giorni**
- sono immagini (jpg/png), video (mp4/mov/mkv), PDF o ZIP

Quando un file viene ghostato, al suo posto resta un **thumbnail** (foto/video) o un file vuoto, più un marker `.shadow` con i metadati. Lo spazio è libero, ma il file è al sicuro sul Raspberry — già verificato.

### Libera spazio subito

Premi **⚡ Libera Spazio Adesso** nell'app: tutti i file idonei partono immediatamente, con barra di progresso nelle notifiche per i file grandi. Upload interrotti (rete caduta) **riprendono dal punto esatto** al tentativo successivo.

### Riaprire un file ghostato — due modi

**Modo 1 — Ripristino manuale (sempre attivo):**
apri l'app → **Vedi File Ghostati** → premi **Ripristina** sul file. In pochi secondi torna disponibile, completo e verificato.

**Modo 2 — Idratazione automatica (da attivare):**
attiva lo switch **💧 Idratazione automatica** nella schermata principale. Da quel momento, aprendo un file ghost **due volte di seguito** (a distanza di almeno 1 secondo), ShadowFS lo riscarica da solo in background. La doppia apertura serve a distinguere te dalla galleria che fa anteprime.

In entrambi i casi, dopo **1 ora** dal ripristino il file viene ri-ghostato automaticamente (se non lo stai più usando).

### Cartelle protette

Premi **📌 Cartelle Protette** per indicare cartelle che ShadowFS non deve **mai** toccare (es. documenti di lavoro, WhatsApp, cartelle sincronizzate con altri servizi). I certificati e la cartella Android sono protetti di default.

---

## Parte 4 — Convivenza con i cloud (Google Photos, Amazon Photos…) ⚠️

Questa è la parte più importante del manuale. I servizi di backup cloud e ShadowFS vogliono entrambi "possedere" gli stessi file, e alcuni cloud **ripristinano** i file che vedono modificati o mancanti — innescando un tira-e-molla infinito.

### Cosa fa ShadowFS per difendersi (automatico)

1. **Primo ripristino esterno**: ShadowFS se ne accorge al ciclo successivo. Se il contenuto è identico a quello già sul Raspberry, ri-ghosta il file **senza ricaricare nulla** (zero traffico).
2. **Secondo ripristino**: il file viene dichiarato **conteso** — ShadowFS smette per sempre di ghostarlo e ti avvisa con una notifica. Niente più loop.
3. **Tetto di sicurezza**: massimo 15 idratazioni automatiche all'ora. Se un'app cloud scatena una raffica di download, l'idratazione si mette in pausa e ricevi la notifica "Possibile loop con app cloud".

### Cosa dovresti fare tu (consigliato)

**Regola d'oro: una sola app "padrona" per cartella.**

| Situazione | Consiglio |
|------------|-----------|
| Usi Google Photos per le foto | Lascia DCIM a Google Photos; usa ShadowFS per video grandi, ZIP, PDF e Download |
| Vuoi ShadowFS su tutto | Disattiva il backup nelle app cloud (l'onboarding ti mostra quali hai installate) |
| Hai Dropbox/OneDrive/Nextcloud su una cartella | Mettila nelle **Cartelle Protette** — i sync di file sovrascrivono, è l'unico caso davvero rischioso |

Nota tranquillizzante: Google Photos e Amazon Photos **non cancellano né sovrascrivono** i loro backup cloud per colpa di ShadowFS — al massimo caricano qualche thumbnail in più. E se elimini un file da ShadowFS, la copia sul loro cloud resta.

---

## Parte 5 — Funzionalità avanzate

### Pulizia orfani (garbage collector)

Se cancelli una foto dal telefono, la copia resta sul Raspberry. Per pulire: **Vedi File Ghostati** → **Controlla** → rivedi la lista degli orfani → **Elimina tutto**. I file appena ripristinati (in attesa di re-ghost) non vengono mai segnalati come orfani.

### Scoperta automatica in rete

Sullo stesso Wi-Fi del Raspberry puoi premere **🔍 Cerca in Wi-Fi**: ShadowFS trova il Raspberry da solo via mDNS, senza inserire IP.

### Più telefoni sulla stessa famiglia

Ogni dispositivo ha il suo spazio isolato sul Raspberry. Per aggiungere un secondo telefono:

```bash
cd /opt/shadowfs
sudo ./shadowdaemon --add-device=telefono_di_anna
```

e copia i certificati generati su quel telefono.

---

## Parte 6 — Cosa fare se qualcosa non va

| Problema | Soluzione |
|----------|-----------|
| `Connection refused` | Il servizio è fermo: `sudo systemctl start shadowfs` |
| `Certificati mancanti` | Rifai il pairing QR, oppure verifica i 3 file in `Download/shadowfs_certs/` e riapri l'app |
| Permesso file non appare | Impostazioni → Privacy → Accesso speciale → Accesso a tutti i file → ShadowFS |
| File non ghostati | Daemon avviato? Permessi ok? Spazio sopra il 15%? (sotto soglia non ghosta nulla, è normale) |
| Notifica "File conteso" | Un'app cloud ripristina quel file: disattiva il suo backup per quella cartella, o lascia così (il file resta semplicemente non ghostato) |
| Notifica "Possibile loop con app cloud" | Idratazione in pausa automatica per 1 ora. Disattiva il backup cloud per le cartelle gestite |
| Irraggiungibile fuori casa | Tailscale attivo su entrambi? Sul Raspberry: `sudo tailscale up` |
| Ho cambiato IP al Raspberry | Rigenera i certificati (vedi sotto) e rifai il pairing |

### Rigenerare i certificati (dopo cambio IP)

```bash
cd /opt/shadowfs
sudo ./shadowdaemon --generate-certs \
  --server-ip="$(tailscale ip -4),$(hostname -I | awk '{print $1}')"
sudo systemctl restart shadowfs
```

Poi rifai il pairing QR dal telefono.

### Log e diagnostica

```bash
# Raspberry — log in tempo reale
journalctl -fu shadowfs

# Telefono (con PC collegato via USB)
adb logcat -s ShadowFS ShadowClient HydrationManager VfsManager

# Spazio usato sul Raspberry
du -sh /storage/shadow_root/
sqlite3 /opt/shadowfs/shadowfs.db "SELECT COUNT(*), SUM(size)/1048576 || ' MB' FROM files;"
```

---

## Glossario

| Termine | Significato |
|---------|-------------|
| **Ghost** | File svuotato/sostituito con thumbnail sul telefono, originale al sicuro sul Raspberry |
| **Idratazione** | Riscaricamento verificato del file originale dal Raspberry |
| **Re-ghost** | Ri-ghosting automatico di un file idratato, dopo 1 ora di non utilizzo |
| **ACK** | Conferma del Raspberry "file ricevuto e verificato" — solo dopo questa il telefono ghosta |
| **File conteso** | File che un'app cloud continua a ripristinare: escluso dal ghosting per evitare loop |
| **mTLS** | Autenticazione reciproca con certificati: solo il TUO telefono parla col TUO Raspberry |
| **Tailscale** | VPN privata che connette i tuoi dispositivi ovunque nel mondo |
| **Marker `.shadow`** | File nascosto con i metadati (dimensione, checksum) del file ghostato |
| **Orfano** | File sul Raspberry il cui originale è stato eliminato dal telefono |

---

*ShadowFS — perché i tuoi file sono tuoi. Per problemi o domande, i log contengono sempre la risposta.*
