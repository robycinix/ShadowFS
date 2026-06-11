# Changelog

All notable changes to ShadowFS will be documented in this file.

The format follows the spirit of [Keep a Changelog](https://keepachangelog.com/)
and this project aims to use semantic versioning once releases begin.

## Unreleased

### Added

- GitHub-facing project documentation and contribution guidelines.
- CI workflow for Go checks and Android debug builds.
- Issue and pull request templates for safer bug reports.

### Current Capabilities

- Android client with foreground service, manual restore list and optional
  automatic hydration.
- Go daemon with TCP + TLS + mTLS protocol.
- QR-code pairing endpoint.
- SHA-256 verification and final upload ACK before local ghosting.
- Resumable upload and download behavior.
- Per-device storage isolation.
- Anti-loop protections for cloud backup apps.
