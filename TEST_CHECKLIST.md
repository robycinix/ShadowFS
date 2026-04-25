# ShadowFS - Checklist test reale

Questa checklist serve a validare una build su Android + Raspberry Pi prima di
aggiungere nuove feature come QUIC.

## Preparazione

- [ ] Raspberry acceso e raggiungibile via LAN o Tailscale.
- [ ] Daemon avviato con TCP+mTLS sulla porta configurata.
- [ ] Certificati generati con SAN coerente con IP/hostname usato dall'app.
- [ ] Certificati copiati in `Download/shadowfs_certs/` sul telefono.
- [ ] App aperta almeno una volta, cosi' migra i certificati nello storage privato.
- [ ] Permesso storage completo concesso.
- [ ] Esenzione batteria/Doze concessa, se richiesta.

## Test minimi

- [ ] Upload file piccolo: il file arriva sul Raspberry e il DB viene aggiornato.
- [ ] Download/idratrazione file piccolo: il ghost viene sostituito dal file reale.
- [ ] Delete: eliminazione dal telefono rimuove file e record lato Raspberry.
- [ ] SyncIndex: gli orfani vengono rilevati e mostrati correttamente.

## File grandi e rete instabile

- [ ] Upload file grande, senza interruzioni.
- [ ] Upload file grande con rete interrotta a meta': al secondo tentativo riprende.
- [ ] Dopo upload ripreso, checksum del file Raspberry uguale all'originale.
- [ ] Download file grande, senza interruzioni.
- [ ] Download file grande con rete interrotta a meta': il ghost locale resta intatto.
- [ ] Al secondo download, `.shadowdl.tmp` viene ripreso e poi pubblicato.
- [ ] Dopo download ripreso, checksum del file Android uguale al file Raspberry.

## Regressioni da osservare

- [ ] La Galleria non causa idratazioni infinite o troppo aggressive.
- [ ] Il foreground service resta vivo durante upload/download lunghi.
- [ ] Restart dell'app non lascia operazioni bloccate.
- [ ] File `.part` rimasti sul Raspberry vengono riusati o rimossi correttamente.
- [ ] File `.shadowdl.tmp` rimasti sul telefono non sostituiscono mai un ghost valido.

## Esito

- Data test:
- Telefono:
- Android:
- Raspberry:
- Rete usata:
- Note:
