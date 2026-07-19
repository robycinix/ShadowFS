# ShadowFS - Installation Guide

This guide keeps the Android app and Raspberry Pi daemon aligned to the same
project version. Update phone and server together: upload, download, and
certificate handling must remain compatible.

## Requirements

- Raspberry Pi or Linux home server powered on and reachable.
- Android phone with the ShadowFS app installed.
- Same Wi-Fi network access for first setup.
- Tailscale recommended for access outside the home.

## 1. Prepare the server

On the Raspberry Pi or Linux server:

```bash
cd /home/<user>/ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```

The script installs dependencies, builds the daemon, generates certificates,
starts the service, and prints the pairing QR code.

Verify the service:

```bash
systemctl status shadowfs
```

It should show `active (running)`.

## 2. Prepare the phone

1. Install the ShadowFS app.
2. Install Tailscale if you want access away from home.
3. Open ShadowFS and grant notifications, all-files access, and battery
   optimization exclusion.

These permissions keep the background service stable.

## 3. Pair phone and server

Recommended method:

1. On the server, show the pairing QR:

   ```bash
   sudo systemctl restart shadowfs && journalctl -fu shadowfs
   ```

2. In the app, tap **Pair via QR Code**.
3. Scan the QR.

The QR expires after a few minutes and works only once.

## 4. Test the connection

In the app:

1. Tap **Test mTLS Connection**.
2. Confirm the connected status.
3. Tap **Start Shadow Daemon**.

ShadowFS can now work in the background.

## 5. Minimum checks

- Confirm the server has enough free disk space.
- Protect folders you do not want managed automatically.
- Test first with non-critical files.
- Verify that restoring a file works correctly.

## Useful commands

```bash
# Server live logs
journalctl -fu shadowfs

# Server storage use
du -sh /storage/shadow_root/

# Service status
systemctl status shadowfs
```

## Important note

For important or irreplaceable files, ShadowFS is not a backup strategy by
itself. Keep a backup of the home server.

## Responsibility

ShadowFS is provided without warranties. Before using it with important files,
test upload, replacement, and restore with non-critical files. Always keep a
separate copy of important data.
