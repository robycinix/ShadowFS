# Security Policy

ShadowFS handles private local files and device certificates. Please report
security issues privately when possible.

## Supported Versions

ShadowFS is currently pre-1.0. Security fixes target the `main` branch until
versioned releases are introduced.

## Reporting a Vulnerability

Open a private security advisory on GitHub if available for the repository. If
private advisories are not enabled, open a minimal issue that states there is a
security concern without publishing exploit details, then coordinate disclosure
with the maintainer.

Please include:

- affected component: Android app, daemon, pairing, installer or documentation;
- impact: data loss, unauthorized access, certificate exposure, path traversal,
  denial of service or other category;
- reproduction steps or proof-of-concept details;
- whether generated certificates, logs or databases were exposed.

## Security Expectations

- Generated certificates and private keys must never be committed.
- Pairing tokens should remain short-lived and one-time use.
- The daemon should reject unauthenticated clients.
- Device IDs derived from certificates must stay path-safe.
- File paths received from clients must not escape the configured storage root.
- Upload and download completion must remain checksum-gated.

## Non-Goals

ShadowFS does not currently provide multi-user cloud hosting, public internet
exposure, end-to-end encryption against the Raspberry Pi owner, or a remote wipe
service. Use a private network such as Tailscale instead of exposing daemon ports
directly to the internet.
