# PiffBackup

PiffBackup is an Android 13+ one-way media backup app for a private Hetzner
Storage Box. Development follows `reference/PROJECT.MD` phase by phase.

Current status: Phase 1 native feasibility spike. The project builds a single
`arm64-v8a` APK and includes reproducibly built rsync and strict SSH tools. No
real backup, onboarding, or Storage Box credential flow is enabled yet.

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

See `tools/native/README.md` for native reproduction and
`PHASE1_NATIVE_SPIKE.md` for device verification, evidence, safety limits, and
remaining checks.

## Safety and privacy

The current in-app check is intentionally local-only and cannot connect to the
Storage Box. Never run tests or performance fixtures against the real
`Bianca/` destination. Remote development tests must use an isolated,
disposable `.piffbackup-test/` path on an equivalent server.

Do not commit passwords, private keys, host keys, live connection files, or
personal filenames. Android backup of app-private data is disabled.
