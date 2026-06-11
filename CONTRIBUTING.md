# Contributing to ShadowFS

Thanks for taking a look at ShadowFS. This project touches user files directly,
so contributions should optimize for correctness, recoverability and clear
operator feedback.

## Good First Contributions

- Improve documentation and setup clarity.
- Add small Android UI fixes that do not change file behavior.
- Add tests around protocol parsing, checksum validation and storage indexing.
- Improve diagnostics for setup, certificates and connectivity failures.

## Development Setup

Daemon:

```bash
cd shadow_daemon
go test ./...
go vet ./...
```

Android:

```powershell
cd shadow_client
.\gradlew.bat assembleDebug
```

Use a real Android device for file-access and foreground-service behavior. The
Android emulator is useful for UI checks, but it does not replace testing on a
phone with real storage permissions.

## Pull Request Guidelines

- Keep PRs focused on one behavior or one documentation area.
- Describe the data-safety impact of the change.
- Mention how you tested upload, restore, delete or sync behavior if touched.
- Do not commit generated certificates, databases, APKs, logs or storage roots.
- Update `TEST_CHECKLIST.md` when adding a scenario that should be tested before
  release.

## Data Safety Rules

Any change that touches ghosting, hydration, delete, resume or checksum logic
must preserve these rules:

- never truncate or replace a local file before the daemon confirms a verified
  upload;
- never publish a restored file before the full download checksum matches;
- treat interrupted transfers as resumable or failed, not as successful;
- keep per-device storage boundaries intact;
- make destructive actions visible in logs or UI.

## Reporting Bugs

Please include:

- Android version and device model;
- Raspberry Pi model and OS;
- whether LAN or Tailscale was used;
- relevant app logs from `adb logcat`;
- relevant daemon logs from `journalctl -u shadowfs`;
- whether cloud backup apps are managing the same folder.
