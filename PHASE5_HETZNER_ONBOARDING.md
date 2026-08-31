# Phase 5 — Hetzner onboarding

Status: implemented and verified on the host and an ARM64 Samsung SM-P610
running API 33 on 2026-08-24.

## Password-once authentication

The welcome and connection screens implement the deliberately small flow from
the project specification. The login asks only for the username and password.
After authentication, a read-only selector shows compatible existing top-level
folders so the user does not need to know the exact name in advance. The normal hostname is derived as
`<username>.your-storagebox.de`, an advanced hostname override is available,
and SSH always uses Hetzner's port 23. Connection failures explain the SSH
Support and External Reachability settings and offer a link to the Hetzner
Console.

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

After key installation the coordinator keeps a temporary in-memory onboarding
session and performs two separate native operations:

1. `pwd` proves the generated key can authenticate.
2. A read-only rsync listing shows top-level directories; after selection,
   `ls -d <backup-root>/` proves the selected SSH-home-relative directory
   exists.

The backup root is restricted to one safe top-level name before it is included
in the second command. Both commands are bounded to 30 seconds and read-only.
No incomplete profile replaces durable configuration. Setup is saved as complete
only after the selected folder verification returns exit code 0. The app does not
create a marker, upload a file, list media contents, run rsync, or modify the
selected root during this phase.

Real-device testing found that Hetzner's restricted shell rejects the otherwise
common `test -d` form with exit code 8 (`Command not found`). The verifier uses
the supported `ls -d` form and has a focused regression test for the exact
command.

Hetzner documents that the main account's SMB `/backup` share and SSH home are
different views of the same Storage Box root. Phase 5 combines that documented
mapping with the live SSH-relative directory check. It does not make a second
SMB connection or claim byte-level cross-protocol identity.

## Verification

The final Phase 5 gate covers:

- endpoint and hostname validation, host-key encoding/fingerprints, pin reuse,
  pin-change rejection, and corrupt-pin fail-closed behavior;
- temporary connection-before-selection and complete-only-after-verify persistence;
- fixed read-only verification commands and bounded native-process timeout;
- Android Keystore encryption/decryption, owner-only temporary key permissions,
  exact process-death cleanup, native Ed25519 key reuse, and isolated
  `known_hosts` permissions; and
- debug, minified release, instrumentation-APK, and Android lint builds.

Host tests, debug and instrumentation APK compilation, the minified release
build, and Android lint pass. The earlier device gate includes the Phase 5
credential, native-key, cleanup, and known-host tests. Lint has no errors; its
remaining security warning points into an unused TLS trust-manager class in the
transitive Bouncy Castle archive, not PiffBackup's SSH host-key verifier, and R8
removes that class from the release application.

A user-driven live setup succeeded on the Samsung SM-P610 with `Matteo/` selected
as the dedicated development root. Native key authentication, `pwd`, and the
read-only `ls -d Matteo/` check all succeeded. No password, username, host key,
or personal filename was committed, and connection verification performed no
remote write or deletion.

On 2026-08-31 onboarding was refined so credentials are established first and
the backup root is then selected from the Storage Box top level. Existing
configured profiles keep their previous behavior and destination.

## Deferred to later phases

- Phase 6 added remote folder browsing/mapping, adoption dry-run reconciliation,
  a calculated summary, and confirmed initial upload.
- A first Phase 7 slice replaced the terminal completion placeholder with
  actions to check saved mappings, change folders, or change the connection.
  The broader home/history/settings UI remains.
- Phase 8 moves long-running backup work into the required background APIs;
  Phase 5 onboarding remains a short, timeout-bounded foreground operation.
- Phase 9 repeats device security/accessibility/localization checks and release
  hardening before delivery.
