# ShadowFS - Panoramica

ShadowFS libera spazio su Android spostando file inattivi verso un Raspberry Pi,
un NAS o un server Linux di casa. Il telefono mantiene un segnaposto leggero e
recupera il file completo quando serve.

L'obiettivo e offrire un'esperienza simile al cloud, ma con file sotto il tuo
controllo: nessun abbonamento ShadowFS, nessun provider terzo obbligatorio e
autenticazione mTLS tra telefono e server.

## In sintesi

- Sposta foto, video, PDF, ZIP e altri file idonei quando lo spazio sul telefono
  scende sotto la soglia configurata.
- Verifica ogni upload con checksum SHA-256 prima di modificare il file locale.
- Lascia anteprime o segnaposto sul telefono per mantenere l'accesso ai contenuti.
- Ripristina il file completo su richiesta dall'app.
- Supporta Tailscale per raggiungere il server anche fuori casa.
- Protegge cartelle sensibili che non devono essere gestite automaticamente.

## Come funziona

1. L'app Android individua file grandi e usati di rado.
2. Il file viene caricato sul server domestico tramite canale cifrato.
3. Il server conferma ricezione, checksum e salvataggio su disco.
4. Solo dopo la conferma, il telefono sostituisce il file locale con un
   segnaposto leggero.
5. Quando il file serve di nuovo, ShadowFS lo ripristina dal server.

## Modello di sicurezza

La regola principale e semplice: nessuna conferma, nessuna modifica locale.
ShadowFS non alleggerisce il file sul telefono finche il server non ha ricevuto,
verificato e salvato l'originale.

Il collegamento usa TLS 1.3 con autenticazione mTLS: il telefono verifica il
server e il server accetta solo dispositivi con certificato valido.

## Componenti

- `shadow_client`: app Android.
- `shadow_daemon`: servizio Go per Raspberry Pi o server Linux.
- `proto`: definizioni del protocollo.
- `docs/it/manuale-utente.md`: manuale utente del sito.
- `docs/it/installazione.md`: guida di installazione del sito.

## Stato del progetto

ShadowFS e pensato per test reali su dispositivi Android e server domestici.
Prima di usarlo con file insostituibili, configura anche un backup del server.
