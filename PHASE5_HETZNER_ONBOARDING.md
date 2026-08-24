# Phase 5 — Hetzner onboarding

Status: implemented and verified on the host on 2026-08-24. The Android
credential tests compile, but their new Phase 5 cases have not yet run on a
device because no ADB device or emulator was connected for the final run.

## Password-once authentication

The welcome and connection screens implement the deliberately small flow from
the project specification. The username is prefilled but editable, the normal
hostname is derived as `<username>.your-storagebox.de`, an advanced hostname
override is available, and SSH always uses Hetzner's port 23. Connection
failures explain the SSH Support and External Reachability settings and offer a
link to the Hetzner Console.

The packaged Dropbear client remains key-only. Initial password authentication
therefore uses SSHJ 0.40.0 solely to run Hetzner's fixed `install-ssh-key`
command and stream the generated public key through standard input. The UI
copies the password into a bounded `CharArray`, clears its password field
immediately, never writes the password to Room or a file, and overwrites the
array on every exit path. No password, private key, remote output, command line,
or personal filename is logged.

## Credentials and server identity

On first setup the app creates a dedicated Ed25519 key with the packaged
Dropbear key tool. Its private half is encrypted with AES-256-GCM under a
non-exportable Android Keystore key and stored in the app's no-backup area.
Native SSH receives only an owner-readable app-private temporary file, which is
deleted immediately afterward. Exact abandoned key temporary files are removed
on the next app launch after process death.

The password connection records the server's first supported host key. Later
connections to the same endpoint require that exact key; a changed or corrupt
pin fails closed and is never silently replaced. The captured key is written as
the only entry in an app-private Dropbear `known_hosts` file. Native reconnects
also set strict host-key checking and batch/key-only authentication.

The completion screen exposes the SHA-256 host-key fingerprint for advanced
comparison. Room persists only the encrypted credential reference and public
host-key pin, never the raw key or password.

## Harmless destination verification

After key installation the coordinator saves setup as incomplete and performs
two separate native Dropbear connections:

1. `pwd` proves the generated key can authenticate.
2. `ls -d Bianca/` proves the expected SSH-home-relative directory exists.

Both commands are fixed, bounded to 30 seconds, and read-only. Setup becomes
complete only after both return exit code 0. The app does not create a marker,
upload a file, list media contents, run rsync, or modify `Bianca/` during this
phase.

Hetzner documents that the main account's SMB `/backup` share and SSH home are
different views of the same Storage Box root. Phase 5 combines that documented
mapping with the live SSH-relative directory check. It does not make a second
SMB connection or claim byte-level cross-protocol identity.

## Verification

The final Phase 5 gate covers:

- endpoint and hostname validation, host-key encoding/fingerprints, pin reuse,
  pin-change rejection, and corrupt-pin fail-closed behavior;
- incomplete-before-verify and complete-after-verify persistence;
- fixed read-only verification commands and bounded native-process timeout;
- Android Keystore encryption/decryption, owner-only temporary key permissions,
  exact process-death cleanup, native Ed25519 key reuse, and isolated
  `known_hosts` permissions; and
- debug, minified release, instrumentation-APK, and Android lint builds.

All 49 host tests pass, as do the debug, minified release, instrumentation-APK,
and lint builds. The unsigned minified release APK is 3,922,578 bytes. Lint has
no errors; its remaining security warning points into an unused TLS trust-manager
class in the transitive Bouncy Castle archive, not PiffBackup's SSH host-key
verifier, and R8 removes that class from the release application.

No live Storage Box connection was made, and no real `Bianca/` folder was
touched. Before relying on onboarding in production, run the 12 compiled Android
tests and one explicit manual setup against the intended device/account, compare
the displayed fingerprint, and confirm that the read-only `Bianca/` check
succeeds.

## Deferred to later phases

- Phase 6 adds remote folder browsing/mapping, adoption dry-run reconciliation,
  a calculated summary, and confirmed initial upload.
- Phase 7 replaces the onboarding completion placeholder with the minimal home,
  folder, and settings screens.
- Phase 8 moves long-running backup work into the required background APIs;
  Phase 5 onboarding remains a short, timeout-bounded foreground operation.
- Phase 9 repeats device security/accessibility/localization checks and release
  hardening before delivery.
