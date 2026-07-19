# ShadowFS - User Manual

**ShadowFS** automatically moves inactive files from your Android phone to your
home Raspberry Pi or server, then restores them when you need them. Your phone
gets free space back and your files stay under your control: no ShadowFS
subscription and no required third-party cloud.

> **Core safety guarantee:** a file is replaced on the phone with a placeholder
> only after the Raspberry Pi confirms that it received the file, verified it
> byte for byte with SHA-256, and saved it to disk. If anything goes wrong during
> upload, the phone copy stays intact.

---

## Before you start

- A **Raspberry Pi** or **Linux home server** on your network, powered on and
  connected to the internet.
- An **Android phone** running Android 10 or newer.
- The **ShadowFS app** installed on the phone.
- Access to the same Wi-Fi network for first setup.

---

## Part 1 - Raspberry Pi setup

### Step 1 - Install Tailscale on the Raspberry Pi

Tailscale lets your phone reach the Raspberry Pi even when you are away from
home, such as on mobile data or another Wi-Fi network.

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

Write down the IP that appears, for example `100.74.44.19`.

### Step 2 - Run the installer

```bash
cd /home/<user>/ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```

The installer prepares the server, generates certificates, starts the service,
and prints the **pairing QR code** in the terminal.

### Step 3 - Verify the service

```bash
systemctl status shadowfs
```

It should show `active (running)`.

---

## Part 2 - Phone setup

### Step 1 - Install Tailscale on the phone

1. Open the **Play Store**, search for **Tailscale**, and install it.
2. Sign in with the same account used on the Raspberry Pi.
3. Enable the VPN when prompted.

The phone and Raspberry Pi can now reach each other through their Tailscale
`100.x.x.x` IPs.

### Step 2 - Pair the app with the Raspberry Pi

Recommended method:

1. On the Raspberry Pi, show the QR:

   ```bash
   sudo systemctl restart shadowfs && journalctl -fu shadowfs
   ```

2. In the app, tap **Pair via QR Code** and scan the code.
3. IP, port, and certificates are configured automatically.

The QR code is valid for a few minutes and works only once.

### Step 3 - Grant permissions

The app guides you through notifications, all-files access, and battery
optimization exclusion. These permissions help ShadowFS keep working reliably in
the background.

### Step 4 - Test and start

1. Tap **Test mTLS Connection**.
2. Tap **Start Shadow Daemon**.
3. Confirm that the persistent **ShadowFS Active** notification appears.

---

## Part 3 - Daily use

### Automatic file management

ShadowFS checks storage every hour. When free space drops below the configured
threshold, it uploads eligible files to the server:

- files larger than 512 KB
- files unused for at least 3 days
- images, videos, PDFs, or ZIP archives

After server verification, the phone keeps a thumbnail or placeholder plus a
`.shadow` metadata marker.

### Free space immediately

Tap **Free Space Now** in the app. Eligible files are uploaded immediately.
Interrupted uploads resume from the correct byte offset on the next attempt.

### Restore a file

Open **View Ghosted Files**, choose a file, and tap **Restore**. The complete
verified file is downloaded from the server.

If automatic hydration is enabled, opening the same placeholder twice in a row
can also trigger a background restore.

### Protected folders

Use **Protected Folders** for folders ShadowFS must never manage, such as work
documents, WhatsApp, or folders synchronized by other services.

---

## Part 4 - Cloud app coexistence

Cloud backup services and ShadowFS may try to manage the same files. Some cloud
apps restore files that look modified or missing, which can create repeated
restore loops.

Best practice: one owner app per folder.

| Situation | Recommendation |
| --- | --- |
| You use Google Photos | Leave `DCIM` to Google Photos. Use ShadowFS for large videos, ZIP files, PDFs, and Downloads. |
| You want ShadowFS everywhere | Disable backup in cloud apps. |
| Dropbox, OneDrive, or Nextcloud manages a folder | Put that folder in **Protected Folders**. |

Google Photos and Amazon Photos do not delete or overwrite their cloud backups
because of ShadowFS.

---

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `Connection refused` | Start the service: `sudo systemctl start shadowfs` |
| `Certificates missing` | Pair again with QR or verify the certificate files. |
| Files are not managed | Check daemon status, permissions, and free-space threshold. |
| Cloud app conflict | Disable backup for that folder or leave the file unmanaged. |
| Unreachable outside the house | Confirm Tailscale is active on both devices. |

## Logs

```bash
# Raspberry Pi live logs
journalctl -fu shadowfs

# Phone logs with USB connected
adb logcat -s ShadowFS ShadowClient HydrationManager VfsManager
```

---

*ShadowFS - because your files are yours.*

