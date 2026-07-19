# ShadowFS - Manual de usuario

ShadowFS mueve archivos inactivos desde tu telefono Android a tu Raspberry Pi o
servidor domestico, y los restaura cuando los necesitas.

## Primer uso

1. Instala el daemon en el servidor con `install_raspberry.sh`.
2. Abre la app Android.
3. Empareja con el QR mostrado por el servidor.
4. Concede permisos de notificaciones, acceso a archivos y exclusion de bateria.
5. Prueba la conexion mTLS y arranca el servicio.

## Uso diario

Cuando el espacio baja, ShadowFS sube archivos elegibles al servidor, verifica el
checksum y deja un marcador ligero en el telefono. Puedes restaurar un archivo
desde **View Ghosted Files** tocando **Restore**.

Usa **Protected Folders** para excluir documentos de trabajo, chats o carpetas
sincronizadas por otros servicios.

## Apps cloud

Evita que dos apps gestionen la misma carpeta. Si Google Photos, OneDrive,
Dropbox o Nextcloud gestionan una carpeta, marcala como protegida o desactiva el
backup cloud para esa ruta.

## Backup

ShadowFS verifica transferencias antes de modificar archivos locales, pero no es
un backup. Pruebalo primero con archivos no criticos y conserva una copia
separada de los datos importantes.

