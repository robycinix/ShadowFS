# Guida ai Test Iniziali: Preparazione e Avvio (Raspberry Pi & Android)

Questa guida ti accompagna nei passaggi pratici per "mettere in moto" ShadowFS per la primissima volta, passando dal codice sorgente su Windows all'esecuzione vera e propria sui tuoi due dispositivi fisici.

---

## FASE 1: Preparazione e Avvio del "Magazzino" (Raspberry Pi)

L'obiettivo è portare il codice del server sul Raspberry, compilarlo e fargli generare le chiavi di sicurezza.

### 1. Trasferimento dei file
Dal tuo PC Windows, devi trasferire la cartella `ShadowFS` (o almeno la sottocartella `shadow_daemon` e il file `install_raspberry.sh`) sul Raspberry Pi.
*Puoi usare software come FileZilla (SFTP), WinSCP, oppure una normale chiavetta USB, trasferendoli ad esempio in `/home/pi/ShadowFS/`.*

### 2. Esecuzione dell'Auto-Installer
Apri il terminale del Raspberry Pi, vai nella cartella dove hai copiato i file ed esegui lo script di installazione:
```bash
cd /percorso/di/ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```
**Cosa farà lo script:**
- Installerà Go (se manca) e compilerà il server `shadowdaemon`.
- Posizionerà l'eseguibile stabile in `/opt/shadowfs`.
- Genererà magicamente la cartella `/opt/shadowfs/certs/` con i certificati mTLS.
- Avvierà il programma in background (come servizio di sistema).

### 3. Recupero dei certificati (Fondamentale!)
Il Raspberry ha appena generato le chiavi per il telefono. Devi "rubarle" dal Raspberry e portarle sul tuo PC (o mandarle direttamente al telefono).
I file che ti servono (si trovano in `/opt/shadowfs/certs/`) sono:
- `ca.crt`
- `client.crt`
- `client.key`

*(Per comodità di test, metti questi tre file nella cartella Download del tuo telefono Android).*

---

## FASE 2: Preparazione de "Il Fantasma" (Smartphone Android)

Andiamo a installare l'app sul telefono usando il tuo PC Windows.

### 1. Apertura in Android Studio
1. Apri **Android Studio** sul tuo computer Windows.
2. Clicca su **Open** e seleziona ESATTAMENTE la cartella `shadow_client` (che si trova dentro `/ShadowFS/shadow_client`).
3. Attendi pazientemente che la barra in basso (Gradle Sync) finisca di caricare tutte le librerie. Se ti chiede di aggiornare il plugin di Gradle, accetta.

### 2. Compilazione e Installazione (Flessibile)
Puoi fare in due modi:
- **Modo A (Cavo USB):** Collega il telefono al PC col cavo, abilita il *Debug USB* nelle impostazioni sviluppatore del telefono, e premi il grande tasto verde **"Play (▶)"** in alto su Android Studio.
- **Modo B (Crea l'APK):** Su Android Studio, vai su `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`. Troverai un file `app-debug.apk` generato. Trasferisci quel file sul telefono e installalo.

---

## FASE 3: Il Primo VERO Test (Cantiere P2P)

Ora che hai il demone attivo sul Raspberry e l'app installata sul telefono:

### 1. I Prerequisiti di Rete
Assicurati che telefono e Raspberry siano collegati allo stesso WiFi di casa, OPPURE (caldamente raccomandato) assicurati di avere **Tailscale** acceso su entrambi i dispositivi.
*Nota per i dev:* Nel file `ShadowForegroundService.kt` e `HydrationManager.kt`, il client "Mock" (Simulatore) sostituirà le comunicazioni finché non compilerai i gRPC Protobuf veri e propri. Adesso testeremo la logica di file management!

### 2. Creazione dell'Esca
1. Sul tuo telefono, vai in `/Storage/emulated/0/Download/` (o una cartella a tua scelta che non sia Pinnata) e mettici un file video o una foto gigante superiore ai 512KB (es. un video `test.mp4` da 100MB).
2. Nota per il test: il nostro scanner ghosta file più vecchi di *3 giorni*. Per il test subito, apri Android Studio, vai in `ForegroundService.kt` riga ~87, e cambia la condizione di controllo del tempo in: 
   `(force || true)` invece di `(force || file.lastModified() < thresholdTime)`. Ricarica l'app.

### 3. Avvio dell'App!
1. Apri l'app **ShadowFS**.
2. Premi **"Richiedi Permessi Memoria"** e consenti all'app di gestire tutti i file (All Files Access).
3. Torna all'app e premi **"Avvia Shadow Daemon"**. Il servizio silente si aggancerà in alto tra le notifiche persistenti.
4. Ora premi: **"Libera Spazio Adesso"**.

### 4. La Magia del Ghosting
- Vai con il File Manager del telefono a guardare quel file `test.mp4`.
- **BOOM!** Noterai che la dimensione del file è scesa a **0 Byte**. Il trucco VFS ha funzionato. Se hai un visualizzatore che mostra i file nascosti, noterai vicino ad esso un marker `/test.mp4.shadow`.
- Il Raspberry Pi (tramite l'apposito log terminale) avrà registrato il tentato arrivo del pacchetto.

### 5. Il Re-Ghosting in Azione (Idratazione)
Provare a premere sulla foto/video appena svuotato per aprirlo:
1. Android ti darà un lag o errorino iniziale (è uno stream da 0 byte per lui).
2. Apparirà in alto la notifica magica: *"Scaricando 'test.mp4' in background..."*
3. Una volta conclusa la notifica, se riaggiorni il file manager, il file sarà esploso di nuovo a 100MB e sarà spuntato l'amico `.reghost` per segnarlo al demone che entro 1 ora dovrà tornare a svuotarlo.

Buon test sperimentale!
