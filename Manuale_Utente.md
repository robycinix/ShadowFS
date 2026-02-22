# Manuale di Utilizzo: ShadowFS (v1.0)

Benvenuto in **ShadowFS**, il tuo sistema P2P proprietario per il "File Ghosting" intelligente tra dispositivi Android e Raspberry Pi. Questo manuale ti guiderà passo dopo passo nell'installazione, configurazione e utilizzo del sistema.

---

## 📋 Indice
1. [Prerequisiti](#1-prerequisiti)
2. [Installazione Lato Raspberry Pi (ShadowDaemon)](#2-installazione-lato-raspberry-pi-shadowdaemon)
3. [Generazione Certificati (mTLS) e Pairing](#3-generazione-certificati-mtls-e-pairing)
4. [Configurazione Lato Android (ShadowClient)](#4-configurazione-lato-android-shadowclient)
5. [Come Funziona (Guida all'Uso)](#5-come-funziona-guida-alluso)
6. [Risoluzione dei Problemi](#6-risoluzione-dei-problemi)

---

## 1. Prerequisiti

### Per il Raspberry Pi (Il Magazzino)
* Sistema Operativo: Raspberry Pi OS (basato su Debian) o simile.
* **Go (Golang)**: Versione 1.20 o superiore.
* Spazio di archiviazione adeguato per ospitare i file offloaded.
* Connessione di rete (meglio se con IP statico locale o tramite Tailscale/DDNS per l'accesso remoto).

### Per lo Smartphone (Il Fantasma)
* Sistema Operativo: Android 10+.
* Permessi di accesso alla memoria (Storage Access Framework o All Files Access).
* (Futuro) App compilata tramite Android Studio.

### Per il PC (Sviluppo su Windows)
* **Go (Golang)**: Necessario per compilare il codice sorgente del demone. [Scarica Go da qui](https://go.dev/dl/).

---

## 2. Installazione Lato Raspberry Pi (ShadowDaemon)

Lo `ShadowDaemon` è il cuore del sistema, scritto in Go per massimizzare le performance e minimizzare l'uso della RAM.

1. **Installa Go sul Raspberry Pi:**
   ```bash
   sudo apt update
   sudo apt install golang -y
   ```

2. **Compila il Demone:**
   Dal tuo PC o direttamente sul Raspberry, posizionati nella cartella `shadow_daemon`:
   ```bash
   cd shadow_daemon
   go mod tidy
   go build -o shadowdaemon .
   ```

3. **Esegui il Demone:**
   ```bash
   ./shadowdaemon -storage /percorso/della/tua/cartella/ombra
   ```
   Questa cartella diventerà il tuo "Magazzino". Il demone avvierà un server QUIC in ascolto sulla porta `4242` e avvierà la scansione iniziale del database SQLite.

---

## 3. Generazione Certificati (mTLS) e Pairing

ShadowFS utilizza **mTLS (Mutual TLS)**. Questo significa che sia il client che il server devono dimostrare la propria identità usando un certificato crittografico. Non ci sono password: se non hai il certificato, la connessione viene chiusa brutalmente.

1. **Generazione:**
   All'interno della cartella `shadow_daemon`, ho integrato un comando per generare i certificati necessari:
   ```bash
   go run . -generate-certs
   ```
   Questo comando creerà una cartella `certs/` contenente:
   * `ca.crt` (La Certificate Authority che firma tutto)
   * `server.crt` e `server.key` (Per il Raspberry)
   * `client.crt` e `client.key` (Da trasferire sul telefono)

2. **Pairing (Trasferimento al Telefono):**
   * Trasferisci `ca.crt`, `client.crt` e `client.key` sul tuo telefono in modo sicuro.
   * *In una UI futura, questo avverrà scansionando un QR Code generato dal terminale del Raspberry.*

---

## 4. Configurazione Lato Android (ShadowClient)

L'app Android è progettata attorno a un **Foreground Service** che monitora continuamente la tua memoria (di default ogni ora o triggerato in real-time).

1. L'app necessiterà dei permessi `MANAGE_EXTERNAL_STORAGE` per manipolare i file senza interruzioni.
2. Nelle impostazioni dell'app, dovrai caricare i certificati generati nello step precedente.
3. Seleziona le "Cartelle da Monitorare" (es. `/Download`, `/DCIM/Camera`).
4. Seleziona le "Cartelle Pinned" (cartelle che non devono MAI essere cantierizzate).

---

## 5. Come Funziona (Guida all'Uso)

Una volta attivo, il sistema è completamente invisibile ("Serverless").

### Cantierizzazione (Offloading)
Se la memoria del tuo Android scende sotto il 15%:
1. Lo ShadowClient intercetta i file non aperti da più di 3 giorni.
2. Apre una connessione QUIC verso il Raspberry (resistente al cambio rete Wi-Fi/4G).
3. Invia i file in blocchi da 4MB.
4. Completa l'operazione tranciando il file locale a **0 byte** (`truncate()`) e generando un file `.shadow` invisibile.
*Risultato: Il file è ancora nella tua galleria, ma occupa 0 spazio fisico.*

### Idratazione (Hydration)
1. Tu (o un'app) provi ad aprire quel file da 0 byte.
2. L'intercettore ShadowClient blocca l'apertura.
3. Compare una piccola notifica di stato: *"ShadowFS: Download in corso dal Magazzino"*.
4. Il file viene riempito fisicamente all'istante (scaricando dal Raspberry Pi).
5. Il file viene aperto normalmente.

---

## 6. Risoluzione dei Problemi

* **Errore "The term 'go' is not recognized" su Windows:** Devi installare Golang sul tuo sistema Windows e riavviare il terminale o VSCode per far aggiornare la variabile PATH.
* **Il server non accetta connessioni:** Assicurati di aver aperto la porta UDP `4242` sul firewall del Raspberry (`sudo ufw allow 4242/udp`).
* **Non trovo il database locale:** Il file SQLite `shadowfs.db` viene creato automaticamente nella directory in cui avvii il demone.

---
*Progetto: ShadowFS (v1.0) - Sviluppato per garantire la massima privacy e gestione dello spazio.*
