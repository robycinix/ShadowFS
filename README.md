# ShadowFS

[![CI](https://github.com/robycinix/ShadowFS/actions/workflows/ci.yml/badge.svg)](https://github.com/robycinix/ShadowFS/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Raspberry%20Pi-blue)](#architecture)
[![Status](https://img.shields.io/badge/status-field%20testing-orange)](#project-status)

ShadowFS is a self-hosted file ghosting system for Android and Raspberry Pi.
It frees space on your phone by moving inactive files to your own Raspberry Pi,
then restores them on demand when you need them again.

Think "iCloud storage optimization", but under your control: no subscription, no
third-party cloud, and mutual TLS between your phone and your Raspberry Pi.

> The core safety rule: ShadowFS ghosts a local file only after the Raspberry Pi
> has received it, verified it with SHA-256, and acknowledged the upload.

## Why It Exists

Phone storage fills up quietly. Photos, videos, PDFs, downloads and archives sit
there for months, but deleting them is risky and cloud subscriptions are not for
everyone.

ShadowFS keeps the convenient local browsing experience while offloading cold
files to hardware you own. The Android app keeps lightweight ghost files and
metadata locally; the daemon stores the original bytes on the Raspberry Pi.

## Highlights

- Android client in Kotlin
- Raspberry Pi daemon in Go
- TCP + TLS 1.3 with mutual certificate authentication
- QR-code pairing for first setup
- Tailscale-friendly networking for access at home or away
- SHA-256 verification before a local file is ghosted
- Resumable uploads and downloads for large files
- Isolated storage per paired device
- Manual restore list and optional automatic hydration
- Anti-loop protections for Google Photos, OneDrive, Amazon Photos and similar
  sync or backup apps
- SQLite index on the Raspberry Pi

## Project Status

ShadowFS is currently in field testing. The architecture is usable, but it is
not yet a polished consumer product and should not be treated as the only copy
of important data.

Recommended use today:

- run it on files you can validate and recover;
- keep a separate backup for critical data;
- test upload, restore and delete flows before trusting a folder;
- avoid mixing ShadowFS with aggressive cloud-sync apps on the same directory
  unless that directory is explicitly protected.

## Architecture

```text
Android phone                              Raspberry Pi
-------------                              ------------

ShadowFS app                               shadowdaemon
Kotlin                                     Go

ForegroundService                          TCP + TLS 1.3 + mTLS
HydrationManager             <-------->    Upload / Download / Delete / SyncIndex
ShadowClient                               SQLite index
VfsManager                                /storage/shadow_root
```

Default ports:

| Port | Purpose |
| --- | --- |
| `4243/tcp` | Android client protocol over TLS + mTLS |
| `4244/tcp` | temporary HTTP pairing endpoint for QR setup |
| `4242/udp` | experimental QUIC path, currently parked |

## How Ghosting Works

```text
1. Android finds a cold file that is large enough to offload.
2. The file is uploaded to the Raspberry Pi over mTLS.
3. The daemon verifies the expected SHA-256 checksum.
4. The daemon sends a final ACK.
5. Only then does Android replace the local file with a ghost marker or preview.
6. When restored, Android downloads to a temporary file and swaps it in only
   after checksum verification succeeds.
```

Interrupted transfers are designed to be recoverable:

- uploads use `.part` files on the Raspberry Pi;
- downloads use `.shadowdl.tmp` files on Android;
- incomplete bytes are not published as final files.

## Repository Layout

```text
.
├── shadow_client/          Android app, Kotlin + Gradle
├── shadow_daemon/          Raspberry Pi daemon, Go + SQLite
├── proto/                  future protocol schema
├── Manuale_Utente.md       end-user setup and usage guide, Italian
├── DEPLOY_GUIDE.md         deployment guide for Android + Raspberry Pi
├── TEST_CHECKLIST.md       real-device validation checklist
└── ANDROID_APP_AUDIT.md    Android implementation audit notes
```

## Quick Start

### Raspberry Pi

```bash
git clone https://github.com/robycinix/ShadowFS.git
cd ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```

The installer:

- installs system dependencies and Go if needed;
- builds the daemon;
- creates `/opt/shadowfs` and `/storage/shadow_root`;
- generates mTLS certificates;
- installs a `systemd` service;
- prints pairing information for the Android app.

Check the service:

```bash
systemctl status shadowfs
journalctl -fu shadowfs
```

### Android

1. Open `shadow_client/` in Android Studio.
2. Let Gradle sync.
3. Connect a real Android device.
4. Run the debug build.
5. Pair the app with the Raspberry Pi by QR code or manual certificate import.

Manual build:

```powershell
cd shadow_client
.\gradlew.bat assembleDebug
```

## Tailscale

ShadowFS works best when the Android device can reach the Raspberry Pi through a
private network such as Tailscale.

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
tailscale ip -4
```

Use the Raspberry Pi Tailscale IP in the Android app, or include it when
regenerating certificates:

```bash
sudo ./shadowdaemon --generate-certs \
  --server-ip="$(tailscale ip -4),$(hostname -I | awk '{print $1}')"
```

## Documentation

- [User Manual](Manuale_Utente.md)
- [Deployment Guide](DEPLOY_GUIDE.md)
- [Real-Device Test Checklist](TEST_CHECKLIST.md)
- [Security Policy](SECURITY.md)
- [Contributing Guide](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Development

Run daemon checks:

```bash
cd shadow_daemon
go test ./...
go vet ./...
```

Build the Android client:

```powershell
cd shadow_client
.\gradlew.bat assembleDebug
```

The GitHub Actions workflow runs Go checks and an Android debug build on pull
requests and pushes to `main`.

## Security Model

ShadowFS assumes the Raspberry Pi and Android device are controlled by the same
person or household.

- A private CA is generated locally.
- The daemon requires a valid client certificate.
- The Android app validates the daemon certificate.
- Each paired device gets an isolated storage namespace.
- Pairing tokens are one-time and expire quickly.

Do not commit generated certificates, device keys, databases, storage roots or
logs. The repository `.gitignore` excludes the expected runtime paths.

## Roadmap

- [x] TCP + TLS + mTLS Android protocol
- [x] QR-code pairing
- [x] Resumable uploads
- [x] Resumable downloads
- [x] Per-device storage isolation
- [x] Manual restore list
- [x] Anti-loop handling for cloud backup apps
- [ ] Signed release builds
- [ ] Automated Android instrumentation tests
- [ ] Protocol versioning with Protobuf
- [ ] Optional QUIC transport after the TCP path is fully validated
- [ ] Incremental backup or block-level dedupe

## Contributing

Issues, bug reports and focused pull requests are welcome. Please read
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR, especially because this
project handles local files and data-loss safety matters more than cosmetic speed.

## License

MIT. See [LICENSE](LICENSE).
