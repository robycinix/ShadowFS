# ShadowFS - Manuale utente

**ShadowFS** sposta automaticamente i file inattivi dal telefono Android al tuo
Raspberry Pi o server di casa, poi li ripristina quando ti servono. Il telefono
recupera spazio libero e i file restano sotto il tuo controllo: nessun
abbonamento ShadowFS e nessun cloud di terzi obbligatorio.

> **Garanzia di sicurezza principale:** un file viene sostituito sul telefono
> con un segnaposto solo dopo che il Raspberry Pi conferma di averlo ricevuto,
> verificato byte per byte con SHA-256 e salvato su disco. Se qualcosa va storto
> durante l'upload, la copia sul telefono resta intatta.

---

## Prima di iniziare

- Un **Raspberry Pi** o **server Linux domestico** sulla tua rete, acceso e
  connesso a internet.
- Un **telefono Android** con Android 10 o successivo.
- L'app **ShadowFS** installata sul telefono.
- Accesso alla stessa rete Wi-Fi per la prima configurazione.

---

## Parte 1 - Configurazione Raspberry Pi

### Passaggio 1 - Installare Tailscale sul Raspberry Pi

Tailscale permette al telefono di raggiungere il Raspberry Pi anche fuori casa,
per esempio tramite rete mobile o un altro Wi-Fi.

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

Annota l'IP mostrato, per esempio `100.74.44.19`: ti servira piu avanti.

### Passaggio 2 - Eseguire l'installer

```bash
cd /home/<utente>/ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```

L'installer prepara il server: installa Go, compila il daemon, genera i
certificati di sicurezza, avvia il servizio e stampa nel terminale il **QR di
abbinamento**.

### Passaggio 3 - Verificare che funzioni

```bash
systemctl status shadowfs
```

Se vedi `active (running)`, il Raspberry Pi e pronto.

---

## Parte 2 - Configurazione del telefono

### Passaggio 1 - Installare Tailscale sul telefono

1. Apri il **Play Store**, cerca **Tailscale** e installalo.
2. Accedi con lo **stesso account** usato sul Raspberry Pi.
3. Abilita la VPN quando richiesto.

Telefono e Raspberry Pi possono ora raggiungersi tramite IP Tailscale `100.x.x.x`.

### Passaggio 2 - Abbinare l'app al Raspberry Pi

**Metodo consigliato - QR code, senza configurazione manuale:**

1. Sul Raspberry Pi esegui `journalctl -u shadowfs | grep "PAIRING"` oppure
   riavvia il servizio per mostrare di nuovo il QR:

   ```bash
   sudo systemctl restart shadowfs && journalctl -fu shadowfs
   ```

2. Nell'app tocca **Pair via QR Code** e scansiona il codice.
3. Fatto: IP, porta e certificati vengono configurati automaticamente.

> Il QR code e valido per **5 minuti** e funziona **una sola volta** per
> sicurezza. Se scade, riavvia il servizio sul Raspberry Pi per generarne uno
> nuovo.

**Metodo manuale:** copia i 3 certificati dal Raspberry Pi al PC, poi inviali al
telefono via USB e inserisci manualmente l'IP.

Dal PC:

```powershell
scp pi@RASPBERRY_IP:/opt/shadowfs/certs_for_android/ca.crt .
scp pi@RASPBERRY_IP:/opt/shadowfs/certs_for_android/client.crt .
scp pi@RASPBERRY_IP:/opt/shadowfs/certs_for_android/client.key .
adb shell mkdir -p /storage/emulated/0/Download/shadowfs_certs
adb push .\ca.crt /storage/emulated/0/Download/shadowfs_certs/ca.crt
adb push .\client.crt /storage/emulated/0/Download/shadowfs_certs/client.crt
adb push .\client.key /storage/emulated/0/Download/shadowfs_certs/client.key
```

Se `adb` e disponibile direttamente sul Raspberry Pi, puoi invece eseguire:

```bash
adb push /opt/shadowfs/certs_for_android/ca.crt     /sdcard/Download/shadowfs_certs/ca.crt
adb push /opt/shadowfs/certs_for_android/client.crt /sdcard/Download/shadowfs_certs/client.crt
adb push /opt/shadowfs/certs_for_android/client.key /sdcard/Download/shadowfs_certs/client.key
```

Poi, nell'app, inserisci l'IP Tailscale, lascia la porta su `4243` e tocca
**Save**. Al primo avvio l'app importa i certificati nel proprio spazio privato.

### Passaggio 3 - Concedere i permessi

Al primo avvio l'app ti guida in 3 passaggi: **notifiche**, **accesso a tutti i
file** ed **esclusione dall'ottimizzazione batteria**. L'ultimo permesso serve a
evitare che Android sospenda ShadowFS in background.

### Passaggio 4 - Testare e avviare

1. Tocca **Test mTLS Connection**. Dovresti vedere `Connected ...` in verde.
2. Tocca **Start Shadow Daemon**. Compare la notifica persistente
   **ShadowFS Active**.

---

## Parte 3 - Uso quotidiano

### Gestione automatica dei file inattivi

ShadowFS controlla lo spazio **ogni ora**. Quando lo spazio libero scende
**sotto il 15%**, invia al Raspberry Pi i file idonei:

- file piu grandi di **512 KB**
- file non usati da almeno **3 giorni**
- immagini (jpg/png), video (mp4/mov/mkv), PDF o archivi ZIP

Quando un file viene sostituito, il telefono mantiene una **miniatura** per
foto/video oppure un file vuoto, piu un marker `.shadow` con i metadati. Lo
spazio viene liberato, ma l'originale e al sicuro sul Raspberry Pi ed e gia
stato verificato.

### Liberare spazio subito

Tocca **Free Space Now** nell'app. I file idonei vengono caricati subito e i
file grandi mostrano l'avanzamento nelle notifiche. Se un upload si interrompe,
per esempio per un problema di rete, riprende dal punto esatto al tentativo
successivo.

### Aprire un file sostituito

**Metodo 1 - Ripristino manuale, sempre disponibile:**

Apri l'app, tocca **View Ghosted Files**, poi **Restore** sul file. In pochi
secondi il file completo e verificato torna disponibile.

**Metodo 2 - Ripristino automatico, opzionale:**

Abilita **Automatic hydration** nella schermata principale. Da quel momento,
aprire lo stesso file sostituito **due volte di seguito**, ad almeno 1 secondo
di distanza, fa scaricare di nuovo il file in background. La regola del doppio
avvio aiuta a distinguere un'azione reale dell'utente dal precaricamento delle
anteprime della galleria.

In entrambi i casi, dopo **1 ora** il file ripristinato viene sostituito di
nuovo automaticamente se non lo stai piu usando.

### Cartelle protette

Tocca **Protected Folders** per indicare cartelle che ShadowFS non deve
**mai** gestire, per esempio documenti di lavoro, WhatsApp o cartelle
sincronizzate da altri servizi. La cartella dei certificati e quella di sistema
Android sono protette di default.

---

## Parte 4 - Convivenza con app cloud

Questa e una parte importante del manuale. I servizi di backup cloud e ShadowFS
possono provare a gestire gli stessi file. Alcune app cloud ripristinano file
che sembrano modificati o mancanti, creando cicli ripetuti.

### Cosa fa ShadowFS automaticamente

1. **Primo ripristino esterno:** ShadowFS lo rileva al ciclo successivo. Se il
   contenuto e identico alla copia gia sul Raspberry Pi, sostituisce di nuovo il
   file **senza caricare nulla**.
2. **Secondo ripristino:** il file viene marcato come **contestato**. ShadowFS
   smette di gestirlo e ti avvisa con una notifica.
3. **Limite di sicurezza:** massimo 15 ripristini automatici all'ora. Se un'app
   cloud innesca molti download, il ripristino automatico viene sospeso e ricevi
   una notifica **Possible cloud app loop**.

### Cosa dovresti fare

**Regola d'oro: una sola app proprietaria per cartella.**

| Situazione | Raccomandazione |
| --- | --- |
| Usi Google Photos per le foto | Lascia `DCIM` a Google Photos. Usa ShadowFS per video grandi, ZIP, PDF e Download. |
| Vuoi usare ShadowFS ovunque | Disattiva il backup nelle app cloud. L'onboarding mostra quali sono installate. |
| Dropbox, OneDrive o Nextcloud gestisce una cartella | Inserisci quella cartella in **Protected Folders**. Gli strumenti di file sync sono il caso piu rischioso. |

Nota rassicurante: Google Photos e Amazon Photos **non** eliminano o sovrascrivono
i backup cloud a causa di ShadowFS. Al massimo possono caricare qualche miniatura
in piu. Se elimini un file da ShadowFS, la copia cloud resta.

---

## Parte 5 - Funzioni avanzate

### Pulizia degli orfani

Se elimini una foto dal telefono, la copia sul Raspberry Pi resta. Per pulirla,
apri **View Ghosted Files**, tocca **Check**, controlla la lista degli orfani e
tocca **Delete all**. I file ripristinati di recente e in attesa di nuova
sostituzione non vengono mai segnalati come orfani.

### Rilevamento automatico in rete

Quando il telefono e sulla stessa rete Wi-Fi del Raspberry Pi, tocca
**Search Wi-Fi**. ShadowFS prova a trovare automaticamente il Raspberry Pi via
mDNS, senza inserire manualmente l'IP.

### Piu telefoni in casa

Ogni dispositivo riceve uno spazio isolato sul Raspberry Pi. Per aggiungere un
secondo telefono:

```bash
cd /opt/shadowfs
sudo ./shadowdaemon --add-device=annas_phone
```

Poi copia sul telefono i certificati generati.

---

## Parte 6 - Risoluzione problemi

| Problema | Soluzione |
| --- | --- |
| `Connection refused` | Il servizio e fermo: `sudo systemctl start shadowfs` |
| `Certificates missing` | Abbina di nuovo con QR, oppure verifica i 3 file in `Download/shadowfs_certs/` e riapri l'app. |
| Non appare il permesso sui file | Settings -> Privacy -> Special app access -> All files access -> ShadowFS |
| I file non vengono sostituiti | Il daemon e attivo? I permessi sono corretti? Lo spazio libero e sopra il 15%? Sopra la soglia, la gestione automatica non interviene. |
| Notifica **Cloud app conflict** | Un'app cloud continua a ripristinare quel file. Disattiva il backup per quella cartella o lasciala cosi: il file non verra piu gestito da ShadowFS. |
| Notifica **Possible cloud app loop** | Il ripristino automatico e in pausa per un po'. Disattiva il backup cloud per le cartelle gestite da ShadowFS. |
| Non raggiungibile fuori casa | Tailscale e attivo su entrambi i dispositivi? Sul Raspberry Pi: `sudo tailscale up` |
| IP del Raspberry Pi cambiato | Rigenera i certificati e abbina di nuovo. |

### Rigenerare i certificati dopo un cambio IP

```bash
cd /opt/shadowfs
sudo ./shadowdaemon --generate-certs \
  --server-ip="$(tailscale ip -4),$(hostname -I | awk '{print $1}')"
sudo systemctl restart shadowfs
```

Poi abbina di nuovo dal telefono.

### Log e diagnostica

```bash
# Raspberry Pi - log live
journalctl -fu shadowfs

# Telefono, con PC collegato via USB
adb logcat -s ShadowFS ShadowClient HydrationManager VfsManager

# Spazio usato sul Raspberry Pi
du -sh /storage/shadow_root/
sqlite3 /opt/shadowfs/shadowfs.db "SELECT COUNT(*), SUM(size)/1048576 || ' MB' FROM files;"
```

---

## Glossario

| Termine | Significato |
| --- | --- |
| **Segnaposto** | File alleggerito o miniatura sul telefono, con originale salvato sul Raspberry Pi. |
| **Hydration** | Download verificato dell'originale dal Raspberry Pi. |
| **Re-ghost** | Nuova sostituzione automatica di un file ripristinato dopo 1 ora di inutilizzo. |
| **ACK** | Conferma del Raspberry Pi che il file e stato ricevuto e verificato. |
| **File contestato** | File ripristinato ripetutamente da un'app cloud. ShadowFS lo esclude per evitare cicli. |
| **mTLS** | Autenticazione reciproca tramite certificati: solo il tuo telefono puo parlare con il Raspberry Pi. |
| **Tailscale** | VPN privata che collega i tuoi dispositivi ovunque. |
| **Marker `.shadow`** | File nascosto con dimensione, checksum e stato del file sostituito. |
| **Orfano** | File sul Raspberry Pi il cui originale e stato eliminato dal telefono. |

---

*ShadowFS - perche i tuoi file sono tuoi. In caso di problemi, i log di solito
contengono l'indizio giusto.*

