# ShadowFS - Guida deploy Raspberry + Android

Questa guida porta Raspberry Pi e smartphone alla stessa versione del codice
presente in questo repository.

Importante: app Android e daemon Raspberry vanno aggiornati insieme. Il protocollo
upload/download e' cambiato: se aggiorni solo uno dei due, i trasferimenti possono
fallire.

## 0. Situazione attesa

Sul PC Windows hai il repository aggiornato:

```powershell
cd C:\Users\rober\Desktop\shadowsfs\ShadowFS
git log -1 --oneline
```

Output atteso:

```text
0fe4272 chore: stabilize ShadowFS baseline
```

Controlla che non ci siano modifiche locali:

```powershell
git status --short
```

Output atteso: nessuna riga.

## 1. Preparare l'APK Android

### Opzione A - Da Android Studio

1. Apri Android Studio.
2. Seleziona `File > Open`.
3. Apri la cartella:

```text
C:\Users\rober\Desktop\shadowsfs\ShadowFS\shadow_client
```

4. Aspetta la sincronizzazione Gradle.
5. Collega lo smartphone via USB.
6. Attiva sul telefono:
   - Opzioni sviluppatore
   - Debug USB
   - Autorizza il PC quando compare il popup RSA
7. In Android Studio seleziona il device reale.
8. Premi `Run`.

Cosa aspettarsi:

- Android Studio compila l'app.
- Installa l'APK sul telefono.
- L'app si apre automaticamente.
- Se l'app era gia' installata, viene aggiornata mantenendo dati e preferenze.

### Opzione B - Da terminale PowerShell

Compila l'APK debug:

```powershell
cd C:\Users\rober\Desktop\shadowsfs\ShadowFS\shadow_client
.\gradlew.bat assembleDebug
```

Output atteso:

```text
BUILD SUCCESSFUL
```

APK generato:

```text
C:\Users\rober\Desktop\shadowsfs\ShadowFS\shadow_client\build\outputs\apk\debug\shadow_client-debug.apk
```

Verifica che `adb` veda il telefono:

```powershell
adb devices
```

Output atteso:

```text
List of devices attached
XXXXXXXX	device
```

Installa l'APK:

```powershell
adb install -r C:\Users\rober\Desktop\shadowsfs\ShadowFS\shadow_client\build\outputs\apk\debug\shadow_client-debug.apk
```

Output atteso:

```text
Success
```

Se `adb` non e' nel PATH, usa quello incluso nell'SDK Android. Di solito si trova qui:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r C:\Users\rober\Desktop\shadowsfs\ShadowFS\shadow_client\build\outputs\apk\debug\shadow_client-debug.apk
```

## 2. Aggiornare i certificati sul telefono

La nuova app migra i certificati dalla cartella pubblica legacy allo storage
privato dell'app.

Cartella legacy attesa sul telefono:

```text
/storage/emulated/0/Download/shadowfs_certs/
```

Deve contenere:

```text
ca.crt
client.crt
client.key
```

Verifica da PowerShell:

```powershell
adb shell ls -l /storage/emulated/0/Download/shadowfs_certs
```

Output atteso: i tre file sopra.

Apri l'app almeno una volta dopo l'installazione. Cosa aspettarsi:

- l'app copia i certificati nello storage privato;
- la cartella legacy resta sul telefono;
- se i certificati sono validi, lo stato certificati nell'app deve risultare OK.

Se mancano i certificati, copiarli di nuovo:

```powershell
adb shell mkdir -p /storage/emulated/0/Download/shadowfs_certs
adb push C:\percorso\certs_for_android\ca.crt /storage/emulated/0/Download/shadowfs_certs/ca.crt
adb push C:\percorso\certs_for_android\client.crt /storage/emulated/0/Download/shadowfs_certs/client.crt
adb push C:\percorso\certs_for_android\client.key /storage/emulated/0/Download/shadowfs_certs/client.key
```

Sostituisci `C:\percorso\certs_for_android` con il percorso reale dei certificati
generati dal Raspberry.

## 3. Preparare il Raspberry

Entra sul Raspberry:

```powershell
ssh pi@IP_DEL_RASPBERRY
```

Oppure, se usi un altro utente:

```powershell
ssh NOME_UTENTE@IP_DEL_RASPBERRY
```

Cosa aspettarsi:

```text
pi@raspberrypi:~ $
```

Ferma il servizio attuale prima di sostituire il binario:

```bash
sudo systemctl stop shadowfs
sudo systemctl status shadowfs --no-pager
```

Output atteso: servizio `inactive` oppure `deactivated`.

## 4. Copiare il codice daemon sul Raspberry

Dal PC Windows, in PowerShell, copia la cartella `shadow_daemon`.

Esempio con `scp`:

```powershell
cd C:\Users\rober\Desktop\shadowsfs\ShadowFS
scp -r .\shadow_daemon pi@IP_DEL_RASPBERRY:/home/pi/ShadowFS_shadow_daemon_new
```

Cosa aspettarsi:

- vengono copiati i file `.go`, script e risorse del daemon;
- non devono essere copiati file Android.

Se il tuo utente non e' `pi`, cambia il comando:

```powershell
scp -r .\shadow_daemon NOME_UTENTE@IP_DEL_RASPBERRY:/home/NOME_UTENTE/ShadowFS_shadow_daemon_new
```

## 5. Compilare il daemon sul Raspberry

Sul Raspberry:

```bash
cd /home/pi/ShadowFS_shadow_daemon_new
go version
```

Output atteso:

```text
go version ...
```

Se `go` non esiste:

```bash
sudo apt update
sudo apt install -y golang
```

Poi compila:

```bash
cd /home/pi/ShadowFS_shadow_daemon_new
go mod tidy
go build -o shadow_daemon .
```

Output atteso: nessun errore e nuovo file `shadow_daemon`.

Verifica:

```bash
ls -lh shadow_daemon
```

Output atteso:

```text
-rwxr-xr-x ... shadow_daemon
```

## 6. Installare il nuovo binario

Prima trova dove si trova il daemon attuale:

```bash
systemctl cat shadowfs
```

Cerca la riga `ExecStart=`.

Esempio:

```text
ExecStart=/opt/shadowfs/shadow_daemon ...
```

Fai backup del binario attuale:

```bash
sudo cp /opt/shadowfs/shadow_daemon /opt/shadowfs/shadow_daemon.backup.$(date +%Y%m%d-%H%M%S)
```

Copia il nuovo binario:

```bash
sudo cp /home/pi/ShadowFS_shadow_daemon_new/shadow_daemon /opt/shadowfs/shadow_daemon
sudo chmod +x /opt/shadowfs/shadow_daemon
```

Se il tuo `ExecStart` usa un percorso diverso, sostituisci `/opt/shadowfs/shadow_daemon`
con quel percorso.

## 7. Verificare configurazione e certificati Raspberry

Controlla la definizione del servizio:

```bash
systemctl cat shadowfs
```

Annota:

- porta TCP, di solito `4243`;
- storage path;
- percorso database;
- percorso certificati.

Controlla che i certificati esistano:

```bash
ls -l /opt/shadowfs/certs
```

Output atteso, nomi simili:

```text
ca.crt
server.crt
server.key
client.crt
client.key
```

Se l'app Android si connette usando un IP diverso da quello presente nei SAN del
certificato server, rigenera i certificati con l'IP corretto prima di testare.

## 8. Riavviare il servizio

Sul Raspberry:

```bash
sudo systemctl daemon-reload
sudo systemctl start shadowfs
sudo systemctl status shadowfs --no-pager
```

Output atteso:

```text
Active: active (running)
```

Leggi i log in tempo reale:

```bash
journalctl -u shadowfs -f
```

Tienilo aperto durante i test dallo smartphone.

## 9. Configurare/verificare l'app Android

Apri ShadowFS sul telefono.

Controlla:

- IP server corretto;
- porta corretta, di solito `4243`;
- certificati presenti;
- permesso storage completo;
- esenzione batteria/Doze concessa se l'app la richiede.

Se usi Tailscale, l'IP nell'app deve essere quello Tailscale del Raspberry, e il
certificato server deve includere quell'IP nei SAN.

## 10. Test rapido di connessione

Dal telefono, prova una funzione che contatti il daemon, per esempio lista ghost,
sync o upload di un file piccolo.

Sul Raspberry, nei log, dovresti vedere righe simili:

```text
[TCP] Connessione da ...
[Upload] ...
[Download] ...
[SyncIndex] ...
```

Se vedi errori TLS:

- `bad certificate`: certificato client non compatibile con la CA del server;
- `certificate is not valid for any names`: IP/hostname usato dall'app non e' nei SAN;
- timeout: IP/porta/firewall/Tailscale non raggiungibile.

## 11. Test funzionali minimi

Usa anche `TEST_CHECKLIST.md`, ma come smoke test iniziale fai questo:

1. Upload file piccolo.
   - Atteso: file presente sul Raspberry.
   - Atteso: log `[Upload] File salvato`.

2. Download/idratrazione dello stesso file.
   - Atteso: ghost sostituito solo a download completo.
   - Atteso: nessun file corrotto se la rete cade.

3. Upload file grande.
   - Atteso: progresso visibile.
   - Atteso: file `.part` solo durante/interruzione upload.

4. Interrompi la rete durante upload grande.
   - Atteso: `.part` resta sul Raspberry.
   - Atteso: secondo tentativo riprende.

5. Interrompi la rete durante download grande.
   - Atteso: ghost locale resta intatto.
   - Atteso: `.shadowdl.tmp` resta come temporaneo.
   - Atteso: secondo tentativo riprende e poi sostituisce il ghost.

## 12. Comandi utili per debug

Log daemon:

```bash
journalctl -u shadowfs -n 100 --no-pager
journalctl -u shadowfs -f
```

Stato daemon:

```bash
sudo systemctl status shadowfs --no-pager
```

Riavvio daemon:

```bash
sudo systemctl restart shadowfs
```

Log Android via adb:

```powershell
adb logcat | findstr ShadowFS
```

Oppure piu' specifico:

```powershell
adb logcat | findstr ShadowClient
```

Verifica APK installato:

```powershell
adb shell dumpsys package com.shadowfs.client | findstr version
```

Pulire temporanei download Android, solo se sei sicuro che nessun download sia in
corso:

```powershell
adb shell find /storage/emulated/0 -name "*.shadowdl.tmp"
```

## 13. Rollback

Se la nuova versione non parte sul Raspberry:

```bash
sudo systemctl stop shadowfs
ls -lh /opt/shadowfs/shadow_daemon.backup.*
sudo cp /opt/shadowfs/shadow_daemon.backup.YYYYMMDD-HHMMSS /opt/shadowfs/shadow_daemon
sudo chmod +x /opt/shadowfs/shadow_daemon
sudo systemctl start shadowfs
sudo systemctl status shadowfs --no-pager
```

Sostituisci `YYYYMMDD-HHMMSS` con il backup reale.

Per Android, da Android Studio puoi reinstallare una build precedente se hai ancora
l'APK. In alternativa ricompila/installa la versione precedente dal commit Git
desiderato.

## 14. Esito finale atteso

Alla fine devono essere veri tutti questi punti:

- Raspberry esegue il nuovo `shadow_daemon`.
- Smartphone ha la nuova app installata.
- Certificati migrati nello storage privato dell'app.
- Upload piccolo funziona.
- Download piccolo funziona.
- File grande riprende dopo interruzione upload.
- File grande riprende dopo interruzione download.
- Il ghost locale non viene mai sostituito da un file troncato.
