# ShadowFS - Security

ShadowFS is designed around one central rule: do not change the local file unless
the server has fully confirmed the remote copy.

## Main guarantees

- The phone file is replaced only after complete receipt.
- The server verifies content with SHA-256.
- Incomplete uploads and downloads use temporary files.
- Each device has isolated certificates and server storage.
- Communication uses TLS 1.3 with mTLS authentication.

## Pairing

The pairing QR is one-time use, expires after a few minutes, and contains a
high-entropy random token. If it expires, restart the service to generate a new
one.

## Server paths

The daemon validates paths to prevent requests outside the dedicated storage
area. This reduces path traversal and unintended access risks.

## Backup recommendation

ShadowFS protects transfer integrity, but it does not replace backup. For
important or irreplaceable files, keep a backup of the home server.

## Responsibility

ShadowFS is provided without warranties. Its verification steps reduce the risk
of data loss during transfers, but they cannot cover hardware failures,
misconfiguration, accidental deletion, or issues outside the software. Use of the
software remains the user's responsibility.

## Reporting vulnerabilities

Avoid public issues that include exploitable details. Collect logs, version,
scenario, and reproduction steps, then share them through the project's chosen
private channel.
