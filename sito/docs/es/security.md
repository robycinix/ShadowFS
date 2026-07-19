# ShadowFS - Seguridad

ShadowFS se basa en una regla central: no cambiar el archivo local si el servidor
no ha confirmado por completo la copia remota.

## Garantias principales

- El archivo del telefono se sustituye solo tras recepcion completa.
- El servidor verifica el contenido con SHA-256.
- Subidas y descargas incompletas usan archivos temporales.
- Cada dispositivo tiene certificados y almacenamiento aislados.
- La comunicacion usa TLS 1.3 con autenticacion mTLS.

## Backup y responsabilidad

ShadowFS protege la integridad de la transferencia, pero no sustituye un backup.
Para archivos importantes o irreemplazables, conserva una copia separada.

El software se proporciona sin garantias. El uso y la configuracion quedan bajo
responsabilidad del usuario.

