# ShadowFS - Uebersicht

ShadowFS schafft Speicherplatz auf Android, indem inaktive Dateien auf deinen
Raspberry Pi, NAS oder Linux-Heimserver verschoben werden. Das Telefon behaelt
einen leichten Platzhalter und stellt die vollstaendige Datei bei Bedarf wieder
her.

Ziel ist Cloud-aehnlicher Komfort mit lokaler Kontrolle: kein ShadowFS-Abo, kein
erforderlicher Drittanbieter-Cloud-Dienst und mTLS zwischen Telefon und Server.

## Kurz gesagt

- Verschiebt geeignete Fotos, Videos, PDFs, ZIPs und andere inaktive Dateien.
- Verifiziert jeden Upload mit SHA-256 vor lokalen Aenderungen.
- Behaelt Miniaturen oder Platzhalter auf dem Telefon.
- Stellt vollstaendige Dateien bei Bedarf aus der App wieder her.
- Unterstuetzt Tailscale fuer Zugriff ausserhalb des Zuhauses.
- Erlaubt geschuetzte Ordner, die nicht automatisch verwaltet werden.

## Sicherheit und Verantwortung

ShadowFS aendert lokale Dateien nicht ohne vollstaendige Serverbestaetigung.
Trotzdem ersetzt es keine Backup-Strategie. Teste es zuerst mit unkritischen
Dateien und behalte immer eine separate Kopie wichtiger Daten.
