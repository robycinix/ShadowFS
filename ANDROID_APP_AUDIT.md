# ShadowFS - Valutazione logica app Android

Obiettivo: ridurre rumore, superfici inutili e complessita' prima di rendere
stabile il deploy su telefono reale.

## Sintesi

L'app Android contiene molte funzioni utili, ma oggi e' ancora una combinazione
di prodotto finale, pannello debug e onboarding tecnico. La cosa da evitare e'
tagliare feature vive prima del test reale: upload/download atomico, hydration,
pairing QR, servizio foreground e pinned folders hanno senso nel modello attuale.

La pulizia migliore e' procedere in due fasi:

1. rimuovere subito cio' che e' certamente morto;
2. dopo il test Android + Raspberry, semplificare la UI e lasciare solo i flussi
   realmente usati.

## Eliminato subito

- `shadow_client/src/main/res/icon_shadowfs.png`

Motivo: asset PNG da circa 9 MB, non referenziato da manifest, layout o codice.
Gli launcher icon effettivi sono in `res/mipmap-*`. Tenere quel file aumentava
peso del repo/APK debug senza dare valore.

## Da mantenere per ora

### `ShadowForegroundService`

Serve davvero: e' il cuore del comportamento background. Gestisce:

- scansione spazio;
- ghosting automatico/forzato;
- retry upload;
- re-ghosting dopo idratazione;
- notifiche di trasferimento;
- avvio di `HydrationManager`.

Non va eliminato. Semmai, in futuro, va diviso in classi piu' piccole.

### `HydrationManager`

Serve davvero: intercetta accessi ai ghost e richiama download atomico. E' una
parte delicata del valore dell'app.

Da tenere, ma da testare bene con Galleria/Google Photos per capire se il rate
limiter e' troppo aggressivo o troppo permissivo.

### `VfsManager`

Serve davvero: crea il ghost locale, gestisce thumbnail e `IS_PENDING`. E' una
parte rischiosa ma necessaria se vuoi evitare che Gallery/Photos vedano file
parziali o thumbnail durante la sostituzione.

Non eliminare prima del test reale.

### Pairing QR

Ha senso mantenerlo: riduce errori manuali su IP, porta e certificati. In piu'
il server ha gia' `pairing.go`, quindi non e' codice isolato lato Android.

Da rivalutare solo se vuoi una versione ultra-minimale senza onboarding.

## Candidati a semplificazione dopo il test reale

### Home troppo tecnica

`activity_main.xml` espone molte azioni:

- salva IP;
- cerca in Wi-Fi;
- testa connessione;
- pairing QR;
- permessi;
- batteria;
- avvio/stop daemon;
- force offload;
- lista ghost;
- cartelle protette.

Per una versione stabile conviene separare:

- schermata principale: stato, spazio, avvia/ferma, lista ghost;
- schermata setup: IP, porta, pairing QR, certificati, permessi;
- schermata avanzata/debug: test connessione, force offload, mDNS.

Questo riduce il rischio che l'utente prema azioni tecniche fuori contesto.

### `btn_force_offload`

Utile per test e manutenzione, ma pericoloso come azione primaria: puo' ghostare
molti file in una volta. In versione stabile andrebbe spostato in "Avanzate" o
protetto da conferma.

Non eliminarlo ora: serve per testare il sistema.

### mDNS "Cerca in Wi-Fi"

Utile se il Raspberry pubblica `_shadowfs._tcp.`. Al momento il daemon ha pairing
HTTP/QR, ma non ho visto una pubblicazione mDNS nel codice Go. Quindi il pulsante
puo' risultare spesso inutile.

Decisione consigliata:

- se vuoi pairing QR come flusso principale, sposta mDNS in avanzate o rimuovilo;
- se vuoi discovery automatica, aggiungi pubblicazione mDNS lato Raspberry.

### Onboarding cloud backup

La logica ha senso, ma e' molto euristica: controlla pacchetti noti e mostra un
warning. Non e' inutile, pero' puo' sembrare invasiva o generica.

Da tenere per utenti non tecnici, ma rendere piu' breve.

### Ghost summary con `walkTopDown()`

`MainActivity.refreshGhostList()` scansiona tutto `/storage/emulated/0` in
background per contare i `.shadow`. Funziona, ma su telefoni pieni puo' essere
costoso.

Alternativa migliore:

- aggiornare un contatore persistente quando ghost/idratation cambiano stato;
- usare la scansione completa solo nella schermata "File Ghostati".

## Permessi da rivalutare

### `READ_EXTERNAL_STORAGE` e `WRITE_EXTERNAL_STORAGE`

Con target SDK moderno e Android 11+, il permesso centrale e' `MANAGE_EXTERNAL_STORAGE`.
`READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` sono legacy.

Non li eliminerei prima del test su device, perche' `minSdk = 29` include Android
10, dove possono ancora avere effetto. Se decidi di supportare solo Android 11+,
puoi rimuoverli e alzare `minSdk` a 30.

### `ACCESS_NETWORK_STATE` e `ACCESS_WIFI_STATE`

Non risultano usati direttamente dal codice Kotlin. `NsdManager` puo' funzionare
senza che l'app legga esplicitamente lo stato Wi-Fi, ma conviene verificare su
telefono reale prima di tagliarli.

Se elimini mDNS, questi diventano candidati forti alla rimozione.

### `CAMERA`

Serve solo per pairing QR. Se mantieni QR, resta necessario.

## Dipendenze da rivalutare

### CameraX + ML Kit

Sono dipendenze pesanti, ma giustificate se il pairing QR e' il flusso consigliato.

Rimuoverle solo se decidi di usare esclusivamente configurazione manuale o import
certificati via file/ADB.

### ConstraintLayout

I layout attuali sono `LinearLayout`/`ScrollView`; non ho visto uso reale di
ConstraintLayout.

Candidato alla rimozione da `build.gradle.kts`, dopo build di verifica.

## Cose da non eliminare anche se sembrano "troppo tecniche"

- `testConnection`: utile per diagnosticare certificati/IP prima di avviare il daemon.
- `pinnedFolders`: evita di ghostare cartelle sensibili.
- `.shadowdl.tmp`: necessario per download atomico.
- `.part`: necessario per upload resumable.
- `.reghost`: serve al ciclo di re-ghosting dopo idratazione.
- `IS_PENDING`: importante per evitare conflitti con Gallery/Google Photos.

## Prossima pulizia consigliata

Dopo il deploy reale:

1. verificare se pairing QR funziona bene;
2. se QR funziona, rendere QR il flusso principale e spostare IP manuale in avanzate;
3. decidere se mDNS serve davvero;
4. rimuovere `ConstraintLayout` se non usato;
5. spostare `Force Offload` in una sezione avanzata con conferma;
6. sostituire il conteggio home con un contatore persistente invece di `walkTopDown()`;
7. ridurre onboarding e home a pochi stati chiari.
