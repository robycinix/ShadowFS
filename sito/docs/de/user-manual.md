# ShadowFS - Benutzerhandbuch

ShadowFS verschiebt inaktive Dateien von deinem Android-Telefon auf deinen
Raspberry Pi oder Heimserver und stellt sie wieder her, wenn du sie brauchst.

## Erster Start

1. Installiere den Daemon auf dem Server mit `install_raspberry.sh`.
2. Oeffne die Android-App.
3. Kopple die App mit dem QR-Code des Servers.
4. Erteile Benachrichtigungen, Dateizugriff und Batterie-Ausnahme.
5. Teste die mTLS-Verbindung und starte den Dienst.

## Alltag

Wenn der Speicher knapp wird, sendet ShadowFS geeignete Dateien an den Server,
prueft den Checksum und laesst einen leichten Platzhalter auf dem Telefon. Eine
Datei kann ueber **View Ghosted Files** und **Restore** wiederhergestellt werden.

Nutze **Protected Folders**, um Arbeitsdokumente, Chats oder von anderen Diensten
synchronisierte Ordner auszuschliessen.

## Cloud-Apps

Vermeide, dass zwei Apps denselben Ordner verwalten. Wenn Google Photos,
OneDrive, Dropbox oder Nextcloud einen Ordner verwaltet, markiere ihn als
geschuetzt oder deaktiviere das Cloud-Backup fuer diesen Pfad.

## Backup

ShadowFS verifiziert Transfers vor lokalen Aenderungen, ist aber kein Backup.
Teste es zuerst mit unkritischen Dateien und behalte eine separate Kopie
wichtiger Daten.

