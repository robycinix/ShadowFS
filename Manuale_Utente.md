# ShadowFS — Manuale Utente

**ShadowFS** sposta automaticamente i tuoi file sul Raspberry Pi di casa quando non li usi, e li riscarica quando li vuoi aprire. Lo spazio sul telefono si libera, i file restano sempre accessibili.

---

## Prima di iniziare — cosa ti serve

- Un **Raspberry Pi** acceso e collegato a internet
- Un **telefono Android** (Android 10 o superiore)
- La **app ShadowFS** installata sul telefono
- Una connessione alla stessa rete Wi-Fi (per il primo setup)

---

## Parte 1 — Setup del Raspberry Pi (una volta sola)

### Passo 1 — Installa Tailscale sul Raspberry

Tailscale ti permette di raggiungere il Raspberry anche quando sei fuori casa (4G, Wi-Fi del bar, ecc.).

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

L'installer fa tutto da solo: installa Go, compila il programma, genera i certificati di sicurezza e avvia il servizio.

Al termine vedrai stampati i comandi per copiare i certificati sul telefono — tienili a portata di mano.

### Passo 3 — Verifica che funzioni

```bash
systemctl status shadowfs
```

Deve mostrare `active (running)`. Se è così, il Raspberry è pronto.

---

## Parte 2 — Setup del telefono (una volta sola)

### Passo 1 — Installa Tailscale sul telefono

1. Apri il **Play Store**
2. Cerca **Tailscale** e installala
3. Accedi con lo **stesso account** usato sul Raspberry (Google, GitHub, ecc.)
4. Attiva la VPN quando richiesto

Ora telefono e Raspberry si vedono tramite i loro IP `100.x.x.x`.

### Passo 2 — Copia i certificati sul telefono

I certificati sono i "documenti di identità" che permettono al telefono di comunicare in sicurezza con il Raspberry.

Collega il telefono al PC via USB e lancia questi comandi:

```bash
adb push /opt/shadowfs/certs_for_android/ca.crt     /sdcard/Download/shadowfs_certs/ca.crt
adb push /opt/shadowfs/certs_for_android/client.crt /sdcard/Download/shadowfs_certs/client.crt
adb push /opt/shadowfs/certs_for_android/client.key /sdcard/Download/shadowfs_certs/client.key
```

In alternativa puoi usare **FileZilla** per scaricare i 3 file dal Raspberry e copiarli manualmente nella cartella `Download/shadowfs_certs/` del telefono.

### Passo 3 — Configura l'app

1. Apri l'app **ShadowFS**
2. Nel campo **IP**, inserisci l'IP Tailscale del Raspberry (es. `100.74.44.19`)
3. Lascia la porta `4243`
4. Premi **Salva**
5. Premi **Richiedi Permesso Gestione File** e concedi il permesso
6. Premi **Testa Connessione mTLS**

Se vedi `Connesso a 100.74.44.19:4243 ✓` in verde, tutto funziona.

### Passo 4 — Avvia il servizio

Premi **Avvia Shadow Daemon** — comparirà una notifica persistente in alto che indica che ShadowFS è attivo in background.

---

## Parte 3 — Uso quotidiano

### Il ghosting automatico

ShadowFS controlla la memoria ogni ora e invia sul Raspberry i file che:
- Pesano più di **512 KB**
- Non vengono usati da almeno **3 giorni**
- Sono immagini, video, PDF o ZIP

Quando un file viene ghostato:
- Al suo posto rimane un **thumbnail** (per foto e video) oppure un file vuoto
- Viene creato un file `.shadow` invisibile che contiene le informazioni originali
- Lo spazio viene liberato

### Libera spazio subito

Se vuoi liberare spazio immediatamente senza aspettare l'ora:

1. Apri l'app ShadowFS
2. Premi **Libera Spazio Adesso**

Tutti i file idonei vengono inviati al Raspberry in pochi minuti.

### Aprire un file ghostato

Apri normalmente la foto o il video dalla galleria. ShadowFS lo riscarica automaticamente dal Raspberry in background — nel giro di qualche secondo il file è disponibile per intero.

Dopo 1 ora senza utilizzo, il file viene ri-ghostato automaticamente.

### Vedere i file ghostati

Nell'app premi **Vedi File Ghostati** per vedere la lista completa con:
- Nome del file
- Dimensione originale recuperata
- Possibilità di eliminare ogni file anche dal Raspberry

---

## Parte 4 — Funzionalità avanzate

### Garbage collector (pulizia orfani)

Se elimini una foto dal telefono, il file originale rimane sul Raspberry.

Per pulire:
1. Apri **Vedi File Ghostati**
2. Premi **Controlla**
3. Vedrai i file "orfani" sul Raspberry (non più presenti sul telefono)
4. Premi **Elimina tutti gli orfani** per liberare spazio anche sul Raspberry

### Scoperta automatica in rete

Se sei connesso allo stesso Wi-Fi del Raspberry, nell'app puoi premere **Cerca in Wi-Fi** invece di inserire l'IP manualmente. ShadowFS trova il Raspberry da solo.

### Pairing via QR Code

Il Raspberry può generare un QR Code con tutte le informazioni di connessione. Nell'app premi **Pairing via QR Code** e scansiona il codice per configurare tutto automaticamente.

---

## Parte 5 — Cosa fare se qualcosa non va

### "Errore: Connection refused"
Il servizio sul Raspberry non è avviato.
```bash
sudo systemctl start shadowfs
```

### "Certificati mancanti"
I 3 file certificato non sono nella posizione giusta.
Verifica che esistano in `Download/shadowfs_certs/` sul telefono (con esattamente quel nome di cartella).

### "Permesso Gestione File" non appare
Vai in **Impostazioni → Privacy → Accesso speciale alle app → Accesso a tutti i file → ShadowFS** e attiva.

### I file non vengono ghostati
Controlla che:
- Il daemon sia avviato (notifica persistente nell'app)
- Il permesso gestione file sia concesso
- I certificati siano presenti
- L'IP del Raspberry sia quello Tailscale (`100.x.x.x`) e non quello LAN

### Il Raspberry non è raggiungibile da fuori casa
Assicurati che Tailscale sia attivo sia sul Raspberry che sul telefono. Sul Raspberry:
```bash
systemctl status tailscaled
sudo tailscale up
```

---

## Parte 6 — Manutenzione

### Aggiornare i certificati (dopo cambio IP)

Se l'IP Tailscale del Raspberry cambia, devi rigenerare i certificati:

```bash
cd /opt/shadowfs
sudo ./shadowdaemon --generate-certs \
  --server-ip="$(tailscale ip -4),$(hostname -I | awk '{print $1}')"
sudo systemctl restart shadowfs
```

Poi ricopia i 3 file certificato sul telefono.

### Vedere i log del Raspberry

```bash
journalctl -fu shadowfs
```

### Vedere i log del telefono (con PC collegato)

```bash
adb logcat -s ShadowFS ShadowClient HydrationManager
```

### Spazio usato sul Raspberry

```bash
du -sh /storage/shadow_root/
sqlite3 /opt/shadowfs/shadowfs.db \
  "SELECT COUNT(*), SUM(size)/1048576 || ' MB' FROM files;"
```

---

## Glossario

| Termine | Significato |
|---------|-------------|
| **Ghost** | File svuotato/sostituito con thumbnail sul telefono |
| **Idratazione** | Riscaricamento del file originale dal Raspberry |
| **Re-ghost** | Ri-invio al Raspberry di un file idratato dopo 1 ora |
| **mTLS** | Autenticazione reciproca con certificati (server + client) |
| **Tailscale** | VPN che connette i tuoi dispositivi ovunque nel mondo |
| **Marker .shadow** | File nascosto con i metadati del file ghostato |
| **Orfano** | File sul Raspberry il cui originale è stato eliminato dal telefono |

---

*Per problemi o domande, controlla i log — contengono sempre la risposta.*
