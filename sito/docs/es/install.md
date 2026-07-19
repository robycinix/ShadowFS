# ShadowFS - Guia de instalacion

Esta guia mantiene alineados la app Android y el daemon del Raspberry Pi. Actualiza
telefono y servidor juntos para evitar incompatibilidades de protocolo o
certificados.

## Requisitos

- Raspberry Pi o servidor Linux domestico encendido y accesible.
- Telefono Android con ShadowFS instalado.
- Misma red Wi-Fi para la primera configuracion.
- Tailscale recomendado para acceso fuera de casa.

## 1. Preparar el servidor

```bash
cd /home/<usuario>/ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```

Verifica el servicio:

```bash
systemctl status shadowfs
```

Debe aparecer `active (running)`.

## 2. Emparejar el telefono

En el servidor muestra el QR:

```bash
sudo systemctl restart shadowfs && journalctl -fu shadowfs
```

En la app toca **Pair via QR Code** y escanea el codigo. El QR caduca en pocos
minutos y funciona una sola vez.

## 3. Probar

1. Toca **Test mTLS Connection**.
2. Confirma el estado conectado.
3. Toca **Start Shadow Daemon**.

## Responsabilidad

ShadowFS se proporciona sin garantias. Antes de usarlo con archivos importantes,
prueba subida, sustitucion y restauracion con archivos no criticos. Conserva
siempre una copia separada de los datos importantes.

