# ShadowFS - Sicurezza

ShadowFS e progettato con una regola centrale: non modificare il file locale se
il server non ha confermato in modo completo la copia remota.

## Garanzie principali

- Il file sul telefono viene sostituito solo dopo ricezione completa.
- Il server verifica il contenuto con checksum SHA-256.
- Upload e download incompleti usano file temporanei.
- Ogni dispositivo ha certificati e spazio server isolati.
- La comunicazione usa TLS 1.3 con autenticazione mTLS.

## Abbinamento

Il QR di abbinamento e monouso, scade dopo pochi minuti e contiene un token
casuale ad alta entropia. Se scade, basta rigenerarlo riavviando il servizio.

## Percorsi server

Il daemon valida i percorsi per impedire richieste fuori dallo storage dedicato.
Questo riduce il rischio di path traversal e accessi indesiderati.

## Backup consigliato

ShadowFS protegge il trasferimento, ma non sostituisce un backup. Per file
importanti o insostituibili, mantieni un backup del server domestico.

## Responsabilita

ShadowFS e fornito senza garanzie. Le verifiche riducono il rischio di perdita
dati durante i trasferimenti, ma non coprono errori hardware, configurazioni
errate, cancellazioni accidentali o problemi esterni al software. L'uso resta
sotto la responsabilita dell'utente.

## Segnalare vulnerabilita

Per problemi di sicurezza, evita issue pubbliche con dettagli sfruttabili.
Raccogli log, versione, scenario e passi per riprodurre, poi condividili tramite
il canale privato scelto dal progetto.
