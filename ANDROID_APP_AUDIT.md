# ShadowFS - Android App Logic Audit

Goal: reduce noise, unnecessary surfaces, and complexity before stabilizing
real-device deployment.

## Summary

The Android app contains several useful features, but it is still a mix of final
product, debug panel, and technical onboarding. The main risk is removing live
features before real Android + Raspberry Pi testing. Atomic upload/download,
hydration, QR pairing, the foreground service, and protected folders all fit the
current product model.

The best cleanup path has two phases:

1. remove anything that is certainly dead;
2. after Android + Raspberry Pi field testing, simplify the UI around the flows
   that are actually used.

## Already Removed

- `shadow_client/src/main/res/icon_shadowfs.png`

Reason: unused PNG asset of roughly 9 MB. It was not referenced by the manifest,
layouts, or code. The active launcher icons live in `res/mipmap-*`. Keeping it
increased repository and debug APK weight without value.

## Keep for Now

### `ShadowForegroundService`

This is required. It is the core background behavior and handles:

- storage scanning;
- automatic and forced ghosting;
- upload retry;
- re-ghosting after hydration;
- transfer notifications;
- startup of `HydrationManager`.

Do not remove it. In the future, it should probably be split into smaller
classes.

### `HydrationManager`

This is required. It observes ghost file access and calls the atomic download
path. It is a delicate part of the app value.

Keep it, but test it carefully with Gallery and Google Photos to verify whether
the rate limiter is too aggressive or too permissive.

### `VfsManager`

This is required. It creates the local ghost, manages thumbnails, and sets
`IS_PENDING`. It is risky code, but necessary to avoid Gallery or Photos seeing
partial files during replacement.

Do not remove it before real-device testing.

### QR Pairing

Keep it. It reduces manual mistakes with IP, port, and certificates. The daemon
has `pairing.go`, and the Android app has `QrScanActivity`, so this is a complete
cross-device flow rather than isolated Android-only code.

QR pairing should remain the primary setup path.

### mDNS Discovery

Keep it for now. `MainActivity` discovers `_shadowfs._tcp`, and
`install_raspberry.sh` installs `avahi-daemon` and publishes the same service on
port `4243`.

The feature is coherent with the installer. It still needs real-device testing
on home routers, guest networks, and Tailscale-only scenarios.

## Candidates for Simplification After Field Testing

### Home Screen Is Too Technical

`activity_main.xml` exposes many actions:

- save IP;
- search Wi-Fi;
- test connection;
- QR pairing;
- permissions;
- battery exemption;
- start/stop daemon;
- force offload;
- ghost list;
- protected folders.

For a stable app, split this into:

- main screen: status, storage, start/stop, ghost list;
- setup screen: QR pairing, IP, port, certificates, permissions;
- advanced/debug screen: connection test, force offload, mDNS details.

This reduces the chance that users tap technical actions out of context.

### `btn_force_offload`

Useful for testing and maintenance, but dangerous as a primary action because it
can ghost many files at once. In a stable version, move it to **Advanced** or
protect it with a confirmation dialog.

Do not remove it yet: it is useful for system testing.

### Cloud Backup Onboarding

The logic is reasonable but heuristic: it checks known package names and shows a
warning. It is not useless, but it can feel generic or invasive.

Keep it for non-technical users, but make the message shorter after field
testing.

### Ghost Summary With `walkTopDown()`

`MainActivity.refreshGhostList()` scans all of `/storage/emulated/0` in the
background to count `.shadow` files. This works, but it can be expensive on full
phones.

Better option:

- maintain a persistent counter when ghost/hydration state changes;
- run a full scan only on the **Ghosted Files** screen.

## Permissions to Re-evaluate

### `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`

With modern target SDK and Android 11+, the central permission is
`MANAGE_EXTERNAL_STORAGE`. `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`
are legacy.

Do not remove them before testing because `minSdk = 29` includes Android 10,
where they may still matter. If the project decides to support only Android 11+,
raise `minSdk` to 30 and remove them.

### `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE`

The Kotlin code does not directly read Wi-Fi state, but mDNS behavior should be
tested on real devices before trimming these permissions.

If mDNS is removed later, these become strong removal candidates.

### `CAMERA`

Required only for QR pairing. Keep it as long as QR pairing remains.

## Dependencies to Re-evaluate

### CameraX + ML Kit

These are heavy dependencies, but justified while QR pairing is the recommended
setup path.

Remove them only if the app switches to manual configuration or certificate
import only.

### ConstraintLayout

Current layouts use `LinearLayout`, `ScrollView`, and `FrameLayout`. The current
`build.gradle.kts` does not include ConstraintLayout, so there is nothing to
remove here.

## Do Not Remove Even If They Look Technical

- `testConnection`: useful for diagnosing certificates and IP before starting
  the daemon.
- `pinnedFolders`: prevents ghosting sensitive folders.
- `.shadowdl.tmp`: required for atomic downloads.
- `.part`: required for resumable uploads.
- `.reghost`: required for the re-ghost cycle after hydration.
- `IS_PENDING`: important for avoiding Gallery/Google Photos conflicts.

## Recommended Next Cleanup

After real deployment:

1. verify QR pairing works reliably;
2. keep QR as the primary setup path and move manual IP setup to an advanced
   section;
3. verify mDNS discovery on real home networks;
4. move **Force Offload** to an advanced section with confirmation;
5. replace the home ghost count scan with a persistent counter;
6. reduce onboarding and home UI to a few clear states.
