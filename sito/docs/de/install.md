# ShadowFS - Installationsanleitung

Diese Anleitung haelt Android-App und Raspberry-Pi-Daemon auf derselben Version.
Aktualisiere Telefon und Server zusammen, damit Protokoll und Zertifikate
kompatibel bleiben.

## Voraussetzungen

- Raspberry Pi oder Linux-Heimserver, eingeschaltet und erreichbar.
- Android-Telefon mit installierter ShadowFS-App.
- Gleiches Wi-Fi fuer die erste Einrichtung.
- Tailscale empfohlen fuer Zugriff ausserhalb des Zuhauses.

## 1. Server vorbereiten

```bash
cd /home/<user>/ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```

Service pruefen:

```bash
systemctl status shadowfs
```

Er sollte `active (running)` anzeigen.

## 2. Telefon koppeln

Auf dem Server den QR-Code anzeigen:

```bash
sudo systemctl restart shadowfs && journalctl -fu shadowfs
```

In der App **Pair via QR Code** antippen und den Code scannen. Der QR-Code laeuft
nach wenigen Minuten ab und funktioniert nur einmal.

## 3. Testen

1. **Test mTLS Connection** antippen.
2. Verbindungsstatus pruefen.
3. **Start Shadow Daemon** antippen.

## Verantwortung

ShadowFS wird ohne Gewaehrleistung bereitgestellt. Bevor du wichtige Dateien
verwenden laesst, teste Upload, Ersetzung und Wiederherstellung mit unkritischen
Dateien. Behalte immer eine separate Kopie wichtiger Daten.

