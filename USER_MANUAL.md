# ShadowFS User Manual

**ShadowFS** automatically moves inactive files from your Android phone to your
home Raspberry Pi, then restores them when you need them. Your phone gets free
space back, and your files remain yours: no subscriptions and no third-party
cloud storage.

> **Core safety guarantee:** a file is ghosted, meaning emptied on the phone,
> only after the Raspberry Pi confirms that it received the file, verified it
> byte for byte with SHA-256, and saved it to disk. If anything goes wrong
> during upload, the phone copy stays intact. Always.

---

## Before You Start

- A **Raspberry Pi** powered on and connected to the internet
- An **Android phone** running Android 10 or newer
- The **ShadowFS app** installed on the phone
- Access to the same Wi-Fi network for the first setup

---

## Part 1 - Raspberry Pi Setup

### Step 1 - Install Tailscale on the Raspberry Pi

Tailscale lets your phone reach the Raspberry Pi even when you are away from
home, such as on mobile data or another Wi-Fi network.

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

Write down the IP that appears, for example `100.74.44.19`. You will need it
later.

### Step 2 - Run the Installer

```bash
cd /home/<user>/ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```

The installer handles the setup: it installs Go, builds the daemon, generates
security certificates, starts the service, and prints the **pairing QR code** in
the terminal.

### Step 3 - Verify That It Works

```bash
systemctl status shadowfs
```

It should show `active (running)`. If it does, the Raspberry Pi is ready.

---

## Part 2 - Phone Setup

### Step 1 - Install Tailscale on the Phone

1. Open the **Play Store**, search for **Tailscale**, and install it.
2. Sign in with the **same account** used on the Raspberry Pi.
3. Enable the VPN when prompted.

The phone and Raspberry Pi can now reach each other through their `100.x.x.x`
Tailscale IPs, wherever you are.

### Step 2 - Pair the App With the Raspberry Pi

**Recommended method - QR code, no manual configuration:**

1. On the Raspberry Pi, run `journalctl -u shadowfs | grep "PAIRING"` or restart
   the service to show the QR again:

   ```bash
   sudo systemctl restart shadowfs && journalctl -fu shadowfs
   ```

2. In the app, tap **Pair via QR Code** and scan the code.
3. Done: IP, port, and certificates are configured automatically.

> The QR code is valid for **5 minutes** and works **only once** for security.
> If it expires, restart the Raspberry Pi service to generate a new one.

**Manual method:** copy the 3 certificates from the Raspberry Pi to your PC, then
push them to the phone over USB and enter the IP manually.

From the PC:

```powershell
scp pi@RASPBERRY_IP:/opt/shadowfs/certs_for_android/ca.crt .
scp pi@RASPBERRY_IP:/opt/shadowfs/certs_for_android/client.crt .
scp pi@RASPBERRY_IP:/opt/shadowfs/certs_for_android/client.key .
adb shell mkdir -p /storage/emulated/0/Download/shadowfs_certs
adb push .\ca.crt /storage/emulated/0/Download/shadowfs_certs/ca.crt
adb push .\client.crt /storage/emulated/0/Download/shadowfs_certs/client.crt
adb push .\client.key /storage/emulated/0/Download/shadowfs_certs/client.key
```

If `adb` is available directly on the Raspberry Pi, you can instead run:

```bash
adb push /opt/shadowfs/certs_for_android/ca.crt     /sdcard/Download/shadowfs_certs/ca.crt
adb push /opt/shadowfs/certs_for_android/client.crt /sdcard/Download/shadowfs_certs/client.crt
adb push /opt/shadowfs/certs_for_android/client.key /sdcard/Download/shadowfs_certs/client.key
```

Then, in the app, enter the Tailscale IP, leave the port set to `4243`, and tap
**Save**. On first launch, the app imports the certificates into its private
storage.

### Step 3 - Grant Permissions

On first launch, the app guides you through 3 steps: **notifications**,
**all-files access**, and **battery optimization exclusion**. The last one is
needed so Android does not suspend ShadowFS in the background. Follow the
screens.

### Step 4 - Test and Start

1. Tap **Test mTLS Connection**. You should see `Connected ...` in green.
2. Tap **Start Shadow Daemon**. A persistent **ShadowFS Active** notification
   appears.

---

## Part 3 - Daily Use

### Automatic Ghosting

ShadowFS checks storage **every hour**. When free space drops **below 15%**, it
sends eligible files to the Raspberry Pi:

- files larger than **512 KB**
- files unused for at least **3 days**
- images (jpg/png), videos (mp4/mov/mkv), PDFs, or ZIP archives

When a file is ghosted, the phone keeps either a **thumbnail** for photos/videos
or an empty file, plus a `.shadow` marker with metadata. The space is freed, but
the original file is safe on the Raspberry Pi and has already been verified.

### Free Space Immediately

Tap **Free Space Now** in the app. Eligible files are uploaded immediately, and
large files show progress in notifications. Interrupted uploads, such as after a
network drop, **resume from the exact byte offset** on the next attempt.

### Opening a Ghosted File

**Method 1 - Manual restore, always available:**

Open the app, tap **View Ghosted Files**, then tap **Restore** on the file. In a
few seconds, the full verified file is available again.

**Method 2 - Automatic hydration, opt-in:**

Enable **Automatic hydration** on the main screen. From then on, opening the
same ghosted file **twice in a row**, at least 1 second apart, makes ShadowFS
download it again in the background. The double-open rule helps distinguish a
real user action from gallery thumbnail prefetching.

In both cases, after **1 hour** the restored file is ghosted again automatically
if you are no longer using it.

### Protected Folders

Tap **Protected Folders** to mark folders that ShadowFS must **never** touch,
such as work documents, WhatsApp, or folders synchronized by other services. The
certificate folder and Android system folder are protected by default.

---

## Part 4 - Coexisting With Cloud Apps

This is the most important part of the manual. Cloud backup services and
ShadowFS may both try to manage the same files. Some cloud apps **restore** files
that look modified or missing, which can create an endless tug-of-war.

### What ShadowFS Does Automatically

1. **First external restore:** ShadowFS detects it on the next cycle. If the
   content is identical to the copy already on the Raspberry Pi, it re-ghosts
   the file **without uploading anything**.
2. **Second restore:** the file is marked **contested**. ShadowFS stops ghosting
   it forever and warns you with a notification. No more loop.
3. **Safety budget:** at most 15 automatic hydrations per hour. If a cloud app
   triggers a burst of downloads, automatic hydration pauses and you receive a
   **Possible cloud app loop** notification.

### What You Should Do

**Golden rule: one owner app per folder.**

| Situation | Recommendation |
| --- | --- |
| You use Google Photos for photos | Leave `DCIM` to Google Photos. Use ShadowFS for large videos, ZIP files, PDFs, and Downloads. |
| You want ShadowFS everywhere | Disable backup in cloud apps. The onboarding shows which ones are installed. |
| Dropbox, OneDrive, or Nextcloud manages a folder | Put that folder in **Protected Folders**. File sync tools overwrite files and are the riskiest case. |

Reassuring note: Google Photos and Amazon Photos do **not** delete or overwrite
their cloud backups because of ShadowFS. At most, they may upload a few extra
thumbnails. If you delete a file from ShadowFS, the cloud copy remains.

---

## Part 5 - Advanced Features

### Orphan Cleanup

If you delete a photo from the phone, the Raspberry Pi copy remains. To clean it
up, open **View Ghosted Files**, tap **Check**, review the orphan list, and tap
**Delete all**. Recently restored files waiting for re-ghosting are never
reported as orphans.

### Automatic Network Discovery

When the phone is on the same Wi-Fi network as the Raspberry Pi, tap
**Search Wi-Fi**. ShadowFS tries to find the Raspberry Pi automatically through
mDNS, without manual IP entry.

### Multiple Phones in One Household

Each device gets isolated storage on the Raspberry Pi. To add a second phone:

```bash
cd /opt/shadowfs
sudo ./shadowdaemon --add-device=annas_phone
```

Then copy the generated certificates to that phone.

---

## Part 6 - Troubleshooting

| Problem | Fix |
| --- | --- |
| `Connection refused` | The service is stopped: `sudo systemctl start shadowfs` |
| `Certificates missing` | Pair again with QR, or verify the 3 files in `Download/shadowfs_certs/` and reopen the app. |
| File permission does not appear | Settings -> Privacy -> Special app access -> All files access -> ShadowFS |
| Files are not ghosted | Is the daemon running? Are permissions OK? Is free space above 15%? Above the threshold, automatic ghosting does nothing. |
| **Cloud app conflict** notification | A cloud app keeps restoring that file. Disable backup for that folder, or leave it as-is; the file will simply stay unghosted. |
| **Possible cloud app loop** notification | Automatic hydration is paused for a while. Disable cloud backup for ShadowFS-managed folders. |
| Unreachable outside the house | Is Tailscale active on both devices? On the Raspberry Pi: `sudo tailscale up` |
| Raspberry Pi IP changed | Regenerate certificates, then pair again. |

### Regenerate Certificates After an IP Change

```bash
cd /opt/shadowfs
sudo ./shadowdaemon --generate-certs \
  --server-ip="$(tailscale ip -4),$(hostname -I | awk '{print $1}')"
sudo systemctl restart shadowfs
```

Then pair again from the phone.

### Logs and Diagnostics

```bash
# Raspberry Pi - live logs
journalctl -fu shadowfs

# Phone, with the PC connected over USB
adb logcat -s ShadowFS ShadowClient HydrationManager VfsManager

# Space used on the Raspberry Pi
du -sh /storage/shadow_root/
sqlite3 /opt/shadowfs/shadowfs.db "SELECT COUNT(*), SUM(size)/1048576 || ' MB' FROM files;"
```

---

## Glossary

| Term | Meaning |
| --- | --- |
| **Ghost** | A file emptied or replaced with a thumbnail on the phone, with the original safely stored on the Raspberry Pi. |
| **Hydration** | Verified download of the original file from the Raspberry Pi. |
| **Re-ghost** | Automatic ghosting of a hydrated file after 1 hour of non-use. |
| **ACK** | Raspberry Pi confirmation that the file was received and verified. The phone ghosts the local file only after this. |
| **Contested file** | A file repeatedly restored by a cloud app. ShadowFS excludes it from ghosting to avoid loops. |
| **mTLS** | Mutual certificate authentication: only your phone can talk to your Raspberry Pi. |
| **Tailscale** | Private VPN that connects your devices from anywhere. |
| **`.shadow` marker** | Hidden metadata file storing size, checksum, and state for a ghosted file. |
| **Orphan** | A file on the Raspberry Pi whose original has been deleted from the phone. |

---

*ShadowFS - because your files are yours. For problems or questions, the logs
usually contain the answer.*
