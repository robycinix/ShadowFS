# ShadowFS Deployment Guide - Raspberry Pi + Android

This guide brings the Raspberry Pi daemon and Android app to the same version
from this repository.

Important: update the Android app and Raspberry Pi daemon together. The
upload/download protocol is shared; if only one side is updated, transfers can
fail.

## 0. Expected Starting Point

On the Windows PC, use the repository root:

```powershell
cd C:\Users\rober\Desktop\shadowsfs\ShadowFS
git log -1 --oneline
git status --short
```

Expected `git status --short` output: no lines.

## 1. Build or Install the Android App

### Option A - Android Studio

1. Open Android Studio.
2. Select `File > Open`.
3. Open this folder:

```text
C:\Users\rober\Desktop\shadowsfs\ShadowFS\shadow_client
```

4. Wait for Gradle sync.
5. Connect a real Android phone over USB.
6. Enable on the phone:
   - Developer options
   - USB debugging
   - RSA authorization for this PC when prompted
7. Select the real device in Android Studio.
8. Press `Run`.

Expected result:

- Android Studio builds the app.
- The APK is installed on the phone.
- The app opens automatically.
- If the app was already installed, it is updated while preserving app data and
  preferences.

### Option B - PowerShell

Build the debug APK:

```powershell
cd C:\Users\rober\Desktop\shadowsfs\ShadowFS\shadow_client
.\gradlew.bat assembleDebug
```

Expected output:

```text
BUILD SUCCESSFUL
```

Generated APK:

```text
C:\Users\rober\Desktop\shadowsfs\ShadowFS\shadow_client\build\outputs\apk\debug\shadow_client-debug.apk
```

Verify that `adb` sees the phone:

```powershell
adb devices
```

Expected output:

```text
List of devices attached
XXXXXXXX	device
```

Install the APK:

```powershell
adb install -r C:\Users\rober\Desktop\shadowsfs\ShadowFS\shadow_client\build\outputs\apk\debug\shadow_client-debug.apk
```

Expected output:

```text
Success
```

If `adb` is not in `PATH`, use the copy from the Android SDK:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r C:\Users\rober\Desktop\shadowsfs\ShadowFS\shadow_client\build\outputs\apk\debug\shadow_client-debug.apk
```

## 2. Certificates on the Phone

The Android app can be configured in two ways:

- recommended: scan the one-time pairing QR code printed by the daemon;
- manual fallback: copy certificates to the legacy public folder.

The app migrates certificates from the public legacy folder to private app
storage on first use.

Legacy folder expected on the phone:

```text
/storage/emulated/0/Download/shadowfs_certs/
```

It must contain:

```text
ca.crt
client.crt
client.key
```

Verify from PowerShell:

```powershell
adb shell ls -l /storage/emulated/0/Download/shadowfs_certs
```

Expected output: the three files listed above.

Open the app at least once after installation. Expected behavior:

- the app copies certificates to private storage;
- the legacy folder remains on the phone;
- if certificates are valid, the certificate status in the app is OK.

If certificates are missing, copy them again:

```powershell
adb shell mkdir -p /storage/emulated/0/Download/shadowfs_certs
adb push C:\path\to\certs_for_android\ca.crt /storage/emulated/0/Download/shadowfs_certs/ca.crt
adb push C:\path\to\certs_for_android\client.crt /storage/emulated/0/Download/shadowfs_certs/client.crt
adb push C:\path\to\certs_for_android\client.key /storage/emulated/0/Download/shadowfs_certs/client.key
```

Replace `C:\path\to\certs_for_android` with the actual certificate folder copied
from the Raspberry Pi, usually `/opt/shadowfs/certs_for_android`.

## 3. Prepare the Raspberry Pi

SSH into the Raspberry Pi:

```powershell
ssh pi@RASPBERRY_IP
```

Or, if you use another user:

```powershell
ssh USERNAME@RASPBERRY_IP
```

Expected shell:

```text
pi@raspberrypi:~ $
```

Stop the current service before replacing the binary:

```bash
sudo systemctl stop shadowfs
sudo systemctl status shadowfs --no-pager
```

Expected service state: `inactive` or `deactivated`.

## 4. Copy the Daemon Source to the Raspberry Pi

From Windows PowerShell, copy the `shadow_daemon` folder.

Example with `scp`:

```powershell
cd C:\Users\rober\Desktop\shadowsfs\ShadowFS
scp -r .\shadow_daemon pi@RASPBERRY_IP:/home/pi/ShadowFS_shadow_daemon_new
```

Expected result:

- Go files, scripts, `go.mod`, and `go.sum` are copied;
- Android files are not copied.

If your Raspberry Pi user is not `pi`, adjust the destination:

```powershell
scp -r .\shadow_daemon USERNAME@RASPBERRY_IP:/home/USERNAME/ShadowFS_shadow_daemon_new
```

## 5. Build the Daemon on the Raspberry Pi

On the Raspberry Pi:

```bash
cd /home/pi/ShadowFS_shadow_daemon_new
go version
```

Expected output:

```text
go version ...
```

If `go` is missing:

```bash
sudo apt update
sudo apt install -y golang
```

Build the binary. The project service installed by `install_raspberry.sh` expects
the binary name `shadowdaemon`:

```bash
cd /home/pi/ShadowFS_shadow_daemon_new
go mod tidy
go build -o shadowdaemon .
```

Expected result: no errors and a new `shadowdaemon` file.

Verify:

```bash
ls -lh shadowdaemon
```

Expected output:

```text
-rwxr-xr-x ... shadowdaemon
```

## 6. Install the New Binary

Find the current service definition:

```bash
systemctl cat shadowfs
```

Look for `ExecStart=`.

Typical installer output:

```text
ExecStart=/opt/shadowfs/shadowdaemon -storage /storage/shadow_root -tcp-addr 0.0.0.0:4243 -pairing-addr 0.0.0.0:4244
```

Back up the current binary:

```bash
sudo cp /opt/shadowfs/shadowdaemon /opt/shadowfs/shadowdaemon.backup.$(date +%Y%m%d-%H%M%S)
```

Copy the new binary:

```bash
sudo cp /home/pi/ShadowFS_shadow_daemon_new/shadowdaemon /opt/shadowfs/shadowdaemon
sudo chmod +x /opt/shadowfs/shadowdaemon
```

If your `ExecStart` uses a different path, replace `/opt/shadowfs/shadowdaemon`
with that path.

## 7. Verify Raspberry Pi Configuration and Certificates

Check the service definition:

```bash
systemctl cat shadowfs
```

Note:

- TCP port, usually `4243`;
- HTTP pairing port, usually `4244`;
- storage path, usually `/storage/shadow_root`;
- database path;
- certificate path.

Check server certificates:

```bash
ls -l /opt/shadowfs/certs
```

Expected names:

```text
ca.crt
server.crt
server.key
client.crt
client.key
```

Check Android client certificates:

```bash
ls -l /opt/shadowfs/certs_for_android
```

Expected names:

```text
ca.crt
client.crt
client.key
```

If the Android app connects through an IP address that is not present in the
server certificate SANs, regenerate certificates with the correct IP before
testing.

## 8. Restart the Service

On the Raspberry Pi:

```bash
sudo systemctl daemon-reload
sudo systemctl start shadowfs
sudo systemctl status shadowfs --no-pager
```

Expected output:

```text
Active: active (running)
```

Read logs live:

```bash
journalctl -u shadowfs -f
```

Keep this open while testing from the phone. On startup, the daemon prints the
temporary pairing URL and QR code. The token is valid for 5 minutes and can be
used once.

## 9. Verify Android App Configuration

Open ShadowFS on the phone.

Check:

- server IP is correct;
- port is correct, usually `4243`;
- certificates are present;
- all-files storage permission is granted;
- battery/Doze exemption is granted if requested.

If you use Tailscale, the app should use the Raspberry Pi Tailscale IP, and the
server certificate must include that IP in its SAN list.

If the phone and Raspberry Pi are on the same Wi-Fi network, **Search Wi-Fi**
uses mDNS service `_shadowfs._tcp`. The installer configures this through
`avahi-daemon`.

## 10. Quick Connection Test

On the phone, run a feature that contacts the daemon, such as connection test,
sync, ghost list, restore, or a small upload.

On the Raspberry Pi, logs should show lines similar to:

```text
[TCP] Connessione da ...
[Upload] ...
[Download] ...
[SyncIndex] ...
```

If you see TLS errors:

- `bad certificate`: the client certificate is not compatible with the server CA;
- `certificate is not valid for any names`: the app IP/hostname is not in SANs;
- timeout: IP, port, firewall, or Tailscale is unreachable.

## 11. Minimum Functional Tests

Use `TEST_CHECKLIST.md` for the full pass. For an initial smoke test:

1. Upload a small file.
   - Expected: file present on the Raspberry Pi.
   - Expected: upload log confirms the file was saved.

2. Restore or hydrate the same file.
   - Expected: the ghost is replaced only after a complete verified download.
   - Expected: no corrupted file if the network drops.

3. Upload a large file.
   - Expected: progress is visible.
   - Expected: `.part` exists only during an interrupted upload.

4. Interrupt the network during a large upload.
   - Expected: `.part` remains on the Raspberry Pi.
   - Expected: the second attempt resumes.

5. Interrupt the network during a large download.
   - Expected: the local ghost remains intact.
   - Expected: `.shadowdl.tmp` remains as a temporary file.
   - Expected: the second attempt resumes, verifies, then replaces the ghost.

## 12. Useful Debug Commands

Daemon logs:

```bash
journalctl -u shadowfs -n 100 --no-pager
journalctl -u shadowfs -f
```

Daemon status:

```bash
sudo systemctl status shadowfs --no-pager
```

Daemon restart:

```bash
sudo systemctl restart shadowfs
```

Android logs through adb:

```powershell
adb logcat | findstr ShadowFS
```

More specific:

```powershell
adb logcat | findstr ShadowClient
```

Verify installed APK version:

```powershell
adb shell dumpsys package com.shadowfs.client | findstr version
```

List temporary Android download files. Remove them only if you are certain no
download is in progress:

```powershell
adb shell find /storage/emulated/0 -name "*.shadowdl.tmp"
```

## 13. Rollback

If the new daemon does not start on the Raspberry Pi:

```bash
sudo systemctl stop shadowfs
ls -lh /opt/shadowfs/shadowdaemon.backup.*
sudo cp /opt/shadowfs/shadowdaemon.backup.YYYYMMDD-HHMMSS /opt/shadowfs/shadowdaemon
sudo chmod +x /opt/shadowfs/shadowdaemon
sudo systemctl start shadowfs
sudo systemctl status shadowfs --no-pager
```

Replace `YYYYMMDD-HHMMSS` with the real backup timestamp.

For Android, reinstall a previous APK from Android Studio if you still have it.
Alternatively, build and install the desired previous Git commit.

## 14. Expected Final State

All of these should be true:

- Raspberry Pi runs the new `shadowdaemon`.
- Phone has the new ShadowFS app installed.
- Certificates are migrated to the app private storage.
- Small upload works.
- Small download works.
- Large upload resumes after interruption.
- Large download resumes after interruption.
- The local ghost is never replaced by a truncated file.
