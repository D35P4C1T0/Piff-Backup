# Native tools

Phase 1 pins and cross-compiles these executables for Android `arm64-v8a`:

- rsync 3.4.2
- Dropbear 2025.89: `dbclient`, `dropbearkey`, and `dropbearconvert`
- Android NDK 28.2.13676358 (r28c), targeting API 33

The source archives are fetched over HTTPS and rejected unless their SHA-256
matches `checksums.txt`. Nothing downloads executable code at app runtime.
`dropbear-localoptions.h` removes server, password, forwarding, proxy-command,
agent-forwarding, and X11 functionality; PiffBackup's transport is key-only.
`dropbear-rsa-hostkey-first.patch` keeps Ed25519 client-key support but prefers
the server's RSA-SHA2 host key, matching the key pinned by Android onboarding.

## Build on macOS

Install NDK `28.2.13676358` from Android Studio's SDK Manager, then run:

```text
tools/native/build-all.sh
```

On Apple Silicon, the r28c command-line toolchain may require Rosetta 2 because
Google's NDK host directory is named `darwin-x86_64`. Intel macOS uses the same
toolchain. Linux x86_64 is also supported. Override `ANDROID_NDK_ROOT` when the
NDK is outside the Android SDK.

Outputs use native-library-compatible names beneath
`app/src/main/jniLibs/arm64-v8a`. The scripts verify that every result is an
AArch64 ELF64 position-independent executable using Android's 64-bit linker.

The APK deliberately uses legacy JNI-library packaging so Package Manager
extracts these files into `ApplicationInfo.nativeLibraryDir`. The app resolves
only fixed filenames in that directory and never extracts an executable into a
writable directory.

## Safety

The in-app Phase 1 check is local-only. It runs `--version` and an rsync dry run
between two empty directories under the app cache. It cannot touch a Storage
Box or the real `Bianca/` folder.

Remote device verification must use a dedicated disposable server path such as
`.piffbackup-test/<random-id>/`, never user media. A host key must already be in
the app-private `HOME/.ssh/known_hosts`; `StrictHostKeyChecking=yes` is mandatory.
See `PHASE1_NATIVE_SPIKE.md` at the project root.
