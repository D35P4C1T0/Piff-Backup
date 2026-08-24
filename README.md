# PiffBackup

PiffBackup is an Android 13+ one-way media backup app for a private Hetzner
Storage Box. Development follows `reference/PROJECT.MD` phase by phase.

Current status: Phase 6 initial adoption. The project builds a single
`arm64-v8a` APK, includes reproducibly built rsync and strict SSH tools, turns
bounded MediaStore generation changes into streamed NUL-delimited file lists,
and persists immutable pending work and checkpoints across process death. The
app now performs password-once SSH key installation, Android Keystore-backed
credential protection, strict host-key pinning, and a read-only `Bianca/`
existence check. Users can now choose non-overlapping local folders, browse or
choose remote folders beneath `Bianca/`, preview the existing collection with
actual rsync, and explicitly confirm the initial one-way upload. Normal home
screens and background execution are not enabled yet.

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
and the remaining device/live-account validation. Phase 6 storage access,
mapping, preview, confirmation, and interruption semantics are recorded in
`PHASE6_INITIAL_ADOPTION.md`.

## Safety and privacy

Automated host and device tests are intentionally local-only. Phase 5's live
onboarding check uses fixed read-only commands. Phase 6 adoption starts with an
rsync dry run and cannot upload until the user reviews the calculated summary
and presses `Start backup`; no code path enables remote deletion. Never run
deletion or performance fixtures against the real `Bianca/` destination.
Remote development tests that need disposable writes must use an isolated
`.piffbackup-test/` path on an equivalent server.

Do not commit passwords, private keys, host keys, live connection files, or
personal filenames. Android backup of app-private data is disabled.
