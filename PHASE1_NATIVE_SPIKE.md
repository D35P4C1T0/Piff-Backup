# Phase 1 — native feasibility spike

Status: host build, APK packaging, and local execution verified on Apple
Silicon macOS and an ARM64 Samsung SM-P610 running API 33 on 2026-08-24.
Disposable SSH server checks remain pending.

## Decision

The spike uses rsync 3.4.2 and Dropbear 2025.89 for Android `arm64-v8a`, built
with NDK r28c against API 33. Dropbear was selected because it is compact,
supports Ed25519 keys, supports `StrictHostKeyChecking=yes`, and reads its host
pin from an app-controlled `HOME/.ssh/known_hosts` file. The build includes
`dropbearkey` and `dropbearconvert` so key material never needs a host utility.

Executables are packaged under `lib/arm64-v8a/` with `.so`-compatible names,
extracted by Package Manager, resolved through `ApplicationInfo.nativeLibraryDir`,
and launched with `ProcessBuilder(List<String>)`. The app never downloads or
extracts executable code at runtime.

## Safety boundary

No automated Phase 1 command names or addresses a user backup root. The in-app
probe only uses two empty app-cache directories. Any remote verification must
use a unique, disposable `.piffbackup-test/<random-id>/` directory on a
test/equivalent server. It must never use a real media destination.

The SMB-to-SSH root mapping is therefore deliberately **not verified by this
automated spike**. It requires the later harmless onboarding check with the
owner's explicit connection details and user-entered root.

## Evidence recorded in this repository

| Requirement | Evidence | Status |
|---|---|---|
| Pinned, reproducible ARM64 rsync | `tools/native/build-rsync.sh`, archive SHA-256, ELF checks | Implemented |
| Android SSH transport | Dropbear build script and packaged `dbclient` | Implemented |
| Strict known-host verification | Fixed arguments, isolated `HOME`, unit tests | Implemented |
| Private-key format | Bundled Dropbear Ed25519 key generator/converter | Implemented; end-to-end key auth pending device |
| APK packaging | `jniLibs/arm64-v8a`, legacy extraction, fixed locator | Passed on ARM64 API 33 device |
| Safe execution | argument lists, concurrent bounded output drains | Implemented and host unit-tested |
| Cancellation | graceful destroy then forced fallback | Implemented and host unit-tested; process-tree observation pending device |
| Process death | no persistent job exists in Phase 1 | Transfer process kill observation pending test server |
| Port 23 auth and rsync transport | requires test server credentials | Pending device/test server |
| Harmless remote dry run | disposable test destination only | Pending device/test server |

Host verification produced four stripped AArch64 ELF64 PIE executables using
`/system/bin/linker64`. Their combined uncompressed size is approximately 1.1
MiB. `assembleDebug` produced an arm64-only APK with those exact bytes under
`lib/arm64-v8a/`, with `extractNativeLibs=true` in the merged manifest. Seven
host unit tests pass, and Android lint reports no errors. The device
instrumentation test passed packaged rsync 3.4.2 execution, Dropbear 2025.89
execution, disposable Ed25519 key generation and cleanup, and an isolated local
rsync dry run. No Storage Box connection was made, so the remote rows above are
not claimed as passed.

## Physical-device protocol

1. Connect an API 33+ ARM64 device with USB debugging. Do not enter real Storage
   Box credentials in this Phase 1 diagnostic build.
2. Build and install with `./gradlew installDebug`.
3. Tap **Run safe local check**. All four checks must exit zero.
4. Confirm from `run-as com.d35p4c1t0.piffbackup ls -l lib` (or Package Manager
   inspection) that the tools are executable in `nativeLibraryDir`.
5. For SSH, provision an equivalent disposable server on port 23, an isolated
   remote directory, a dedicated key, and its known-host line. Use
   `StrictHostKeyChecking=yes`; do not test an unknown or changed key by accepting
   it automatically.
6. Exercise success, authentication failure, changed host key, unreachable host,
   cancellation during a large disposable transfer, and app process kill.
7. Verify no child `rsync` or `dbclient` remains after cancellation/process kill,
   and verify the disposable destination contains no deletions.

## Remaining native integration limitation

`Process.destroy()` is the current cancellation hypothesis. Rsync normally
terminates its remote-shell child, but Android process-tree behavior must be
observed on the target device against an isolated test server. If a child
survives, the launcher must be replaced with a small supported JNI process-group
supervisor before any real backup transfer is enabled.
