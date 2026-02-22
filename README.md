# Blueprint Progetto: ShadowFS (v1.0)
Descrizione: Sistema P2P proprietario per il "File Ghosting" intelligente tra Android e Raspberry Pi.

## 1. Architettura di Rete e Sicurezza
Il sistema deve essere Serverless (eccetto per il discovery).

*   **Protocollo di Trasporto:** UDP con implementazione QUIC (per gestire i cambi di IP del mobile senza perdere la sessione).
*   **Discovery P2P:**
    *   **Locale:** mDNS/Zeroconf per trovare il Raspberry nella stessa Wi-Fi.
    *   **Remoto:** UDP Hole Punching tramite un server STUN pubblico.
*   **Sicurezza:** TLS 1.3 con Mutual Auth (mTLS). Il telefono e il Raspberry devono scambiarsi i certificati tramite QR Code durante il pairing iniziale.

## 2. ShadowDaemon (Lato Raspberry Pi) - "Il Magazzino"
*   **Linguaggio:** Go (Golang).
*   **Responsabilità:**
    *   **File Watcher:** Monitora la directory `/storage/shadow_root`.
    *   **Chunk Server:** Gestisce le richieste `GET_CHUNK`. Divide i file in blocchi da 4MB e ne calcola l'hash SHA-256.
    *   **Database Metadati:** SQLite per mappare i file, le versioni e gli ID univoci (UUID).
    *   **Garbage Collector:** Gestisce la coerenza tra i file fisici e il database.

## 3. ShadowClient (Lato Android) - "Il Fantasma"
*   **Linguaggio:** Kotlin (per il demone) + Flutter (solo per UI di config).
*   **Componenti Chiave:**
    *   **Foreground Service:** Un servizio persistente con notifica a bassa priorità.
    *   **VFS Stub Manager:** Quando un file viene "cantierizzato" (offloaded):
        1.  Invia il file al Raspberry.
        2.  Crea un file `.shadow` locale (file segnaposto).
        3.  Tronca il file originale a 0 byte tramite `truncate()`, mantenendo però l'estensione originale se necessario per il sistema operativo.
    *   **Intercettore di Apertura:** Monitora gli accessi ai file `.shadow`. Se invocato, avvia la "Hydration" (download prioritario del chunk necessario).

## 4. Logica di "Cantierizzazione" (Algoritmo di Offloading)
Il demone deve applicare queste regole ogni ora:

*   **Analisi Spazio:** Controlla la memoria libera sul telefono.
*   **Selezione:** Se lo spazio è < 15%, seleziona i file con `last_access` più vecchio di 3 giorni.
*   **Ghosting:** Esegue l'upload al Raspberry e svuota il file locale lasciando il "Ghost".
*   **Pinning:** Cartelle marcate come "Sempre Fisiche" vengono ignorate dall'algoritmo.

## 5. Schema Protocollo Messaggi (Protobuf)
Non usare JSON per il trasferimento dati (troppo overhead). Usa Protocol Buffers:

```protobuf
message ShadowMessage {
  enum Type { HANDSHAKE = 0; PULL_REQUEST = 1; PUSH_DATA = 2; SYNC_INDEX = 3; }
  Type type = 1;
  string file_id = 2;
  uint64 offset = 3;
  bytes payload = 4;
  string checksum = 5;
}
```

## 6. Schema Database (SQLite - Comune a entrambi)
```sql
CREATE TABLE files (
    uuid TEXT PRIMARY KEY,
    filename TEXT,
    rel_path TEXT,
    size INTEGER,
    status TEXT, -- 'FULL', 'GHOST', 'SYNCING'
    checksum TEXT,
    last_modified DATETIME,
    last_access DATETIME
);
```

## 7. Roadmap di Implementazione per l'IA
Fornisci questi prompt in sequenza all'IA che scriverà il codice:

*   **Step 1:** "Scrivi un server in Go che gestisce una connessione QUIC protetta da mTLS e permette di caricare/scaricare file a pezzi (chunks) da 4MB."
*   **Step 2:** "Crea un database SQLite e una funzione di scansione cartelle che generi un UUID per ogni file e ne calcoli l'hash SHA-256 senza saturare la RAM."
*   **Step 3 (Android):** "Implementa un Foreground Service in Kotlin che monitora una cartella specifica. Quando un file viene creato, lo invia al server Go e poi svuota il file locale mantenendo i metadati."
*   **Step 4:** "Scrivi la logica di 'Hydration': se l'utente prova ad accedere a un file da 0 byte, il demone deve bloccare la richiesta, scaricare il file dal Raspberry e notificare il sistema a operazione completata."
