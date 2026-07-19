# ShadowFS - Overview

ShadowFS frees storage on Android by moving inactive files to your Raspberry Pi,
NAS, or home Linux server. The phone keeps a lightweight placeholder and restores
the complete file when you need it.

The goal is cloud-like convenience with local control: no ShadowFS subscription,
no required third-party cloud provider, and mTLS authentication between phone and
server.

## At a glance

- Moves eligible photos, videos, PDFs, ZIP files, and other inactive content when
  phone storage drops below the configured threshold.
- Verifies every upload with SHA-256 before changing the local file.
- Leaves thumbnails or placeholders on the phone so files remain discoverable.
- Restores complete files on demand from the app.
- Supports Tailscale for access outside the home.
- Lets you protect sensitive folders from automatic management.

## How it works

1. The Android app identifies large files that are rarely used.
2. The file is uploaded to the home server over an encrypted channel.
3. The server confirms receipt, checksum, and disk persistence.
4. Only then does the phone replace the local file with a lightweight
   placeholder.
5. When the file is needed again, ShadowFS restores it from the server.

## Security model

The main rule is simple: no confirmation, no local change. ShadowFS never
lightens a phone file until the server has received, verified, and saved the
original.

The connection uses TLS 1.3 with mTLS authentication: the phone verifies the
server, and the server accepts only devices with valid certificates.

## Components

- `shadow_client`: Android app.
- `shadow_daemon`: Go service for Raspberry Pi or Linux server.
- `proto`: protocol definitions.
- `docs/en/user-manual.md`: site user manual.
- `docs/en/install.md`: site installation guide.

## Project status

ShadowFS is intended for real-device testing on Android and home servers. For
irreplaceable files, keep a backup of the server.
