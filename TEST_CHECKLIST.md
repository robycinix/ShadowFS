# ShadowFS - Real-Device Test Checklist

Use this checklist to validate a build on Android + Raspberry Pi before adding
new features such as QUIC.

## Preparation

- [ ] Raspberry Pi is powered on and reachable through LAN or Tailscale.
- [ ] Daemon is running with TCP+mTLS on the configured port, usually `4243`.
- [ ] Pairing HTTP endpoint is available on port `4244` during daemon startup.
- [ ] Certificates were generated with SANs matching the IP/hostname used by the app.
- [ ] App was paired by QR code, or certificates were copied to `Download/shadowfs_certs/`.
- [ ] App was opened at least once, so it migrated certificates to private storage.
- [ ] All-files storage permission is granted.
- [ ] Battery/Doze exemption is granted if requested.

## Minimum Tests

- [ ] Connection test succeeds from the Android app.
- [ ] Small upload: file reaches the Raspberry Pi and the DB is updated.
- [ ] Small download/hydration: the ghost is replaced by the real file.
- [ ] Delete: deletion from the phone removes the file and Raspberry Pi DB record.
- [ ] SyncIndex: orphans are detected and displayed correctly.

## Large Files and Unstable Network

- [ ] Large upload without interruption.
- [ ] Large upload with network interrupted halfway: second attempt resumes.
- [ ] After resumed upload, Raspberry Pi file checksum matches the original.
- [ ] Large download without interruption.
- [ ] Large download with network interrupted halfway: local ghost stays intact.
- [ ] On second download, `.shadowdl.tmp` resumes and is then published.
- [ ] After resumed download, Android file checksum matches the Raspberry Pi file.

## Regression Watchlist

- [ ] Gallery does not cause infinite or overly aggressive hydration.
- [ ] Google Photos or similar backup apps do not create ghost/restore loops.
- [ ] The foreground service stays alive during long upload/download operations.
- [ ] App restart does not leave operations blocked.
- [ ] `.part` files left on the Raspberry Pi are reused or cleaned up correctly.
- [ ] `.shadowdl.tmp` files left on the phone never replace a valid ghost prematurely.
- [ ] Protected folders are skipped during forced and automatic ghosting.
- [ ] mDNS **Search Wi-Fi** discovers the daemon when both devices are on the same LAN.

## Result

- Test date:
- Phone:
- Android version:
- Raspberry Pi model:
- Raspberry Pi OS:
- Network used:
- Notes:
