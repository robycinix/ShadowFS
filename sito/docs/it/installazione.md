# ShadowFS - Guida di installazione

Questa guida porta app Android e daemon Raspberry Pi alla stessa versione del
repository. Aggiorna sempre insieme telefono e server: protocollo di upload,
download e certificati devono restare allineati.

## Requisiti

- Raspberry Pi o server Linux acceso e raggiungibile.
- Telefono Android con l'app ShadowFS installata.
- Accesso alla stessa rete Wi-Fi per il primo abbinamento.
- Tailscale consigliato se vuoi usare ShadowFS anche fuori casa.

## 1. Preparare il server

Sul Raspberry Pi o server Linux:

```bash
cd /home/<utente>/ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```

Lo script installa le dipendenze, compila il daemon, genera i certificati,
avvia il servizio e mostra il QR di abbinamento.

Verifica lo stato:

```bash
systemctl status shadowfs
```

Il servizio deve risultare `active (running)`.

## 2. Preparare il telefono

1. Installa l'app ShadowFS.
2. Installa Tailscale se vuoi raggiungere il server fuori casa.
3. Apri ShadowFS e concedi notifiche, accesso ai file e esclusione
   dall'ottimizzazione batteria.

Questi permessi servono per mantenere il servizio stabile in background.

## 3. Abbinare telefono e server

Metodo consigliato:

1. Sul server mostra il QR di abbinamento:

   ```bash
   sudo systemctl restart shadowfs && journalctl -fu shadowfs
   ```

2. Nell'app tocca **Pair via QR Code**.
3. Scansiona il QR.

Il QR dura pochi minuti e funziona una sola volta.

## 4. Testare la connessione

Nell'app:

1. Tocca **Test mTLS Connection**.
2. Verifica che compaia lo stato connesso.
3. Tocca **Start Shadow Daemon**.

Da questo momento ShadowFS puo lavorare in background.

## 5. Controlli minimi

- Verifica che il server abbia spazio libero sufficiente.
- Proteggi cartelle che non vuoi gestire automaticamente.
- Prova prima con file non critici.
- Controlla che il ripristino di un file funzioni correttamente.

## Comandi utili

```bash
# Log live del server
journalctl -fu shadowfs

# Spazio usato sul server
du -sh /storage/shadow_root/

# Stato del servizio
systemctl status shadowfs
```

## Nota importante

Per file importanti o insostituibili, ShadowFS non sostituisce una strategia di
backup. Mantieni un backup del server domestico.

## Responsabilita

ShadowFS e fornito senza garanzie. Prima di usarlo su file importanti, provalo
con file non critici e verifica upload, sostituzione e ripristino. Mantieni
sempre una copia separata dei dati importanti.
