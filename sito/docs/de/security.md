# ShadowFS - Sicherheit

ShadowFS basiert auf einer zentralen Regel: Die lokale Datei wird nicht
geaendert, solange der Server die entfernte Kopie nicht vollstaendig bestaetigt
hat.

## Zentrale Garantien

- Die Datei auf dem Telefon wird erst nach vollstaendigem Empfang ersetzt.
- Der Server verifiziert Inhalte mit SHA-256.
- Unvollstaendige Uploads und Downloads nutzen temporaere Dateien.
- Jedes Geraet hat isolierte Zertifikate und isolierten Serverspeicher.
- Die Kommunikation nutzt TLS 1.3 mit mTLS-Authentifizierung.

## Backup und Verantwortung

ShadowFS schuetzt die Integritaet der Uebertragung, ersetzt aber kein Backup.
Fuer wichtige oder unersetzliche Dateien solltest du immer eine separate Kopie
behalten.

Die Software wird ohne Gewaehrleistung bereitgestellt. Nutzung und Konfiguration
liegen in der Verantwortung des Nutzers.

