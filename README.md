# PiffBackup

PiffBackup is an Android 13+ one-way media backup app for a private Hetzner
Storage Box. Development follows `reference/PROJECT.MD` phase by phase.

Current status: Phases 1–7 are complete. The project builds a single
`arm64-v8a` APK, includes
reproducibly built rsync and strict SSH tools, turns bounded MediaStore
generation changes into streamed NUL-delimited file lists, and persists
immutable pending work and checkpoints across process death. The app performs
password-once SSH key installation, Android Keystore-backed credential
protection, strict host-key pinning, and read-only verification of the backup
root explicitly entered by the user. Users can choose non-overlapping local
folders, map them beneath that root, preview the existing collection with
actual rsync, and explicitly confirm the initial one-way upload. Completed
setups open on a minimal home screen with live status, one-tap discovery,
last-success time, dynamic folder management, and settings. Background UIDT
and WorkManager execution remains Phase 8.

## Build

Requirements:

- Android Studio compatible with Android Gradle Plugin 9.3.2
- JDK 17 or newer (Android Studio's bundled JBR works)
- Android SDK API 37 and Build Tools 36.0.0
- Android NDK 28.2.13676358 for rebuilding native tools

Build the bundled tools from pinned source, then build the APK:

```text
tools/native/build-all.sh
./gradlew assembleDebug testDebugUnitTest
```

If the shell cannot find Java on macOS, point `JAVA_HOME` at Android Studio's
bundled runtime for that invocation:

```text
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

See `tools/native/README.md` for native reproduction,
`PHASE1_NATIVE_SPIKE.md` for native feasibility evidence, and
`PHASE2_COMMAND_ENGINE.md` for command-engine semantics. Phase 3 design and
verification are recorded in `PHASE3_MEDIASTORE_PLANNER.md`; Room schema and
recovery semantics are recorded in `PHASE4_ROOM_DURABILITY.md`. See
`PHASE5_HETZNER_ONBOARDING.md` for onboarding security, harmless verification,
and live-device validation. Phase 6 storage access, mapping, preview,
confirmation, interruption, and completion navigation are recorded in
`PHASE6_INITIAL_ADOPTION.md`. Phase 7 home, folder, and settings behavior is
recorded in `PHASE7_MINIMAL_UI.md`. The first real transfer measurement and its
limitations are recorded in `PERFORMANCE.md`.

## Safety and privacy

Automated host and device tests are intentionally local-only. Phase 5's live
onboarding check uses only bounded read-only commands against the root entered
by the user. Phase 6 adoption starts with an rsync dry run and cannot upload
until the user reviews the calculated summary and presses `Start backup`; no
code path enables remote deletion. Never run destructive tests or generated
performance fixtures against a real user destination. `Matteo/` is the
explicit development test root for this environment, not a product default.

Do not commit passwords, private keys, host keys, live connection files, or
personal filenames. Android backup of app-private data is disabled.
