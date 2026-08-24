# PiffBackup — Android media backup app

You are a senior Android engineer. Build a production-quality, sideloaded Android application named **PiffBackup**. Work autonomously, make conservative engineering decisions, and verify every important assumption with tests. Do not stop at scaffolding or pseudocode: deliver a buildable, usable application with documentation and tests.

This document is the authoritative product and engineering specification. If a lower-level implementation choice conflicts with a product requirement, preserve the product requirement and document the implementation tradeoff.

## 1. Product mission

PiffBackup is an extremely simple Android backup app for non-technical users. It backs up selected local folders, recursively including all subfolders, from a Samsung Galaxy S23+ to an existing Hetzner Storage Box.

The primary content is personal photos and videos. The Storage Box already contains a large existing media collection. PiffBackup must adopt that collection without blindly re-uploading it, then efficiently upload only new or modified media during later, occasional backup sessions.

The scale estimates discussed during planning were roughly 100,000 existing remote files and roughly 2,000–3,000 new files in a typical monthly session. These are **illustrative estimates only**:

- Never hardcode these counts.
- Never treat them as limits, validation thresholds, pagination sizes, or product promises.
- All algorithms, schemas, counters, progress displays, and tests must work for zero files, a few files, and substantially larger collections.
- Use `Long` for file counts and byte counts where practical.
- Stream file lists and database queries instead of assuming the entire collection fits in memory.

## 2. Known environment

- App name: `PiffBackup`
- Target device: Samsung Galaxy S23+
- Initial minimum SDK: Android 13 / API 33
- Compile and target SDK: latest stable SDK supported by the chosen stable Android Gradle Plugin
- Distribution: signed APK installed directly by the owner; Google Play distribution is out of scope
- CPU target for the first release: `arm64-v8a`
- Development host: macOS, supporting both Apple Silicon and Intel where reasonably possible
- Storage Box username: provided by the user during onboarding
- Storage Box hostname: `<storage-box-username>.your-storagebox.de`
- Hetzner SSH/rsync port: `23`
- Backup root: entered by the user during first-run onboarding; there is no product default
- Non-destructive development verification root: `Matteo/`
- Corresponding SMB-visible test destination: `smb://<storage-box-username>.your-storagebox.de/backup/Matteo/`

Important: the SMB share component `backup` represents the main account's share and should not automatically become part of the rsync destination. Before adoption, verify harmlessly that the user-entered SSH-relative backup root exposes the same directory as its SMB `/backup/<backup-root>/` path. For development tests, use the separately designated `Matteo/` root. Do not assume a root name and do not write during connection verification.

Never commit a password, private key, live host key, generated credential, or personally identifying file list to Git.

## 3. Core product behaviour

The user can configure an arbitrary number of local folder roots. Every selected root includes all its descendants.

For each local root, persist an explicit mapping:

```text
local Android folder -> remote folder under <backup-root>/
```

Examples only:

```text
/storage/emulated/0/DCIM/Camera/       -> <backup-root>/Camera/
/storage/emulated/0/Pictures/          -> <backup-root>/Pictures/
/storage/emulated/0/Movies/            -> <backup-root>/Videos/
```

Do not assume these exact local or remote names. First-run onboarding must require the user to enter an existing top-level backup root. The mapping flow must then let the user adopt existing remote directories or create new directories beneath that configured root.

Backup semantics are deliberately one-way and non-destructive:

- Upload new local files.
- Upload a replacement when a local file is modified.
- Preserve relative subdirectory structure.
- Do not delete remote files when a local file disappears.
- Do not implement bidirectional synchronization.
- Do not modify unrelated remote files.
- Do not use rsync `--delete` in any normal or hidden code path.
- Never turn an inaccessible or empty local root into a destructive remote operation.

Use user-facing language such as **Back up**, **Back up now**, and **Everything is backed up**. Avoid exposing terms such as rsync, SSH, SFTP, generation number, manifest, or remote shell outside troubleshooting/advanced screens.

## 4. Required performance strategy

### 4.1 One-time initial adoption

The first setup must reconcile the selected local roots against the existing remote mappings once. Use actual rsync over SSH port 23, because rsync can exchange large file lists much more efficiently than a client performing one SFTP request per remote file.

For the adoption preview, use a command equivalent to:

```bash
rsync \
  -rlt \
  --size-only \
  --whole-file \
  --dry-run \
  --partial \
  --partial-dir=.rsync-partial \
  --no-owner \
  --no-group \
  --no-perms \
  --protect-args \
  --itemize-changes \
  --stats \
  -e "<strict SSH command using port 23>" \
  <local-root-with-trailing-slash> \
  <storage-box-username>@<storage-box-username>.your-storagebox.de:<mapped-relative-remote-root-with-trailing-slash>
```

The precise flags may be adjusted after compatibility testing, but preserve these semantics:

- `--size-only` is for the initial adoption only. Existing upload tools may not have preserved source mtimes.
- Same relative path and same size are treated as already adopted during the fast initial pass.
- `--whole-file` avoids wasting CPU on block deltas for already-compressed media.
- The source trailing slash semantics must be intentional and covered by tests.
- No compression for ordinary JPEG, HEIC, MP4, ZIP, and similar already-compressed content.
- No deletion.
- Start with a dry run, parse its result, and show a human summary before transferring.
- Do not run `--checksum` over the entire existing collection by default; it would read all file contents on both sides.

Show a summary whose values are calculated dynamically:

```text
Existing backup checked

<count> items are already backed up
<count> items need uploading
<formatted bytes> to upload

[Start backup]
```

Offer full checksum verification only as an advanced troubleshooting action, with a clear warning that it can be very slow.

### 4.2 Fast subsequent backups

Normal later runs must not enumerate the complete remote tree and must not rescan every existing remote file.

Use Android MediaStore generation tracking for indexed images and videos:

- Persist `MediaStore.getVersion(context, volumeName)`.
- Persist the last fully successful `MediaStore.getGeneration(context, volumeName)`.
- At the start of a run, snapshot a `targetGeneration`.
- Query rows where `GENERATION_ADDED` or `GENERATION_MODIFIED` is greater than the previous successful generation and less than or equal to `targetGeneration`.
- Query image and video media types.
- Filter results to the configured local root prefixes.
- Use `RELATIVE_PATH` and `DISPLAY_NAME`; do not depend on the deprecated `_data` column.
- If MediaStore's version changes, consider the generation checkpoint invalid and require a full reconciliation.
- Advance the stored generation to `targetGeneration` only after all applicable rsync invocations complete successfully.
- If a file is added after `targetGeneration`, it must remain eligible for the next run.

For every configured root with changed files, create an app-private, NUL-delimited file list containing paths relative to that root. Invoke rsync with behaviour equivalent to:

```bash
rsync \
  -rlt \
  --from0 \
  --files-from=<app-private-file-list> \
  --whole-file \
  --partial \
  --partial-dir=.rsync-partial \
  --no-owner \
  --no-group \
  --no-perms \
  --protect-args \
  --itemize-changes \
  --stats \
  -e "<strict SSH command using port 23>" \
  <local-root-with-trailing-slash> \
  <storage-box-username>@<storage-box-username>.your-storagebox.de:<mapped-relative-remote-root-with-trailing-slash>
```

Requirements for file lists:

- Support spaces, Unicode, emoji, newlines, leading dashes, apostrophes, and other valid Android filenames.
- Use `--from0`; never create newline-delimited lists.
- Reject absolute entries and any entry containing a `..` path component.
- Write lists incrementally to app-private storage.
- If there are no changed files, do not open a network connection.
- Do not create one SSH/SFTP connection per file.
- One rsync invocation per changed root is the default. Run sequentially first; add at most two-way root concurrency only if measurement proves it useful and cancellation remains correct.

### 4.3 Non-MediaStore content

Although photos and videos are the primary case, selected directories can contain non-indexed or `.nomedia` content.

Each mapping must offer:

- `Media only — Fast` (default for known media folders): MediaStore generation strategy.
- `All files — Slower`: recursively scan local filesystem metadata and compare it against a compact local Room index, then pass only new/modified relative paths to rsync.

Never enumerate the full remote tree during a normal incremental run. A full remote reconciliation is a distinct, explicit operation.

### 4.4 Recovery and full audits

Require or offer a complete reconciliation when:

- The app is used for the first time.
- App data was cleared or the app was reinstalled.
- MediaStore version changed.
- Storage Box account/hostname changed.
- A local-to-remote mapping changed.
- The persisted checkpoint is corrupt or logically impossible.
- The user selects `Check entire backup` in troubleshooting.

## 5. Android storage access

This is a sideloaded backup app and true rsync requires ordinary filesystem paths. Use Android's special all-files access only because it is central to the app's backup function.

- Declare `MANAGE_EXTERNAL_STORAGE`.
- Provide an honest, plain-language explanation before opening the system settings grant screen.
- Check `Environment.isExternalStorageManager()` before filesystem operations.
- Do not attempt to access other apps' private `Android/data` contents.
- Use the first-party system folder picker (`ACTION_OPEN_DOCUMENT_TREE`) for folder selection UX.
- For MVP, accept only the primary local `ExternalStorageProvider` and resolve a tree such as `primary:DCIM/Camera` to a canonical path beneath `/storage/emulated/0`.
- Treat the picker URI as a selection token, not as a path supplied blindly to native code.
- Canonicalize and validate the resolved path.
- Reject cloud providers, virtual documents, roots outside allowed shared storage, and paths that cannot be represented safely for rsync.
- Detect overlapping roots and prevent duplicate configuration. Example: if `Pictures` is selected, reject `Pictures/Screenshots` as already covered.
- Persist enough information to redisplay and revalidate each mapping.

Investigate whether `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` are necessary in addition to all-files access for the selected target SDK and chosen MediaStore queries. Request only permissions actually required on the target device.

## 6. Native rsync and SSH integration

Actual rsync is required. Do not silently replace it with recursive SFTP listing.

### 6.1 Mandatory engineering spike

Before building most UI, complete a vertical technical spike on a real or equivalent ARM64 Android environment:

1. Cross-compile or otherwise reproducibly build an ARM64 Android-compatible rsync executable from pinned source.
2. Provide an Android-compatible SSH transport executable. Evaluate a pinned OpenSSH client, Dropbear `dbclient`, or a narrowly scoped libssh/libssh2-based helper.
3. Confirm that the chosen SSH client supports strict known-host verification and the selected private-key format.
4. Package the executables in the APK without downloading executable code at runtime.
5. Verify execution from the installed app's native library directory on the Galaxy S23+ target API.
6. Authenticate on port 23.
7. Run a harmless rsync dry run against the explicitly selected test root (`Matteo/` for this development environment).
8. Capture stdout, stderr, exit code, cancellation, and process death behaviour.

The likely pragmatic packaging approach is to place executable ELF files under `jniLibs/arm64-v8a` with native-library-compatible filenames, enable native library extraction where required, resolve them through `ApplicationInfo.nativeLibraryDir`, and execute them with `ProcessBuilder`. Treat this as an implementation hypothesis that must be proven on-device, not as an unquestioned assumption.

If current Android restrictions make that approach unreliable, implement a supported JNI/native-service alternative. Do not extract an executable to ordinary writable app storage and assume it will remain executable. Do not download code after installation.

### 6.2 Reproducible build and licensing

- Pin rsync and SSH transport source versions.
- Add scripts under `tools/native/` that reproduce ARM64 builds from macOS or a documented container.
- Pin the Android NDK version in Gradle and build documentation.
- Verify source archives/checkouts by commit or checksum.
- Do not commit opaque third-party binaries without their exact source, version, licence, and reproduction path.
- Add `THIRD_PARTY_NOTICES.md`.
- Rsync 3.x is GPLv3; comply with redistribution requirements for the sideloaded APK and document how corresponding source is provided.
- Preserve notices for the selected SSH implementation.

### 6.3 Safe process execution

- Construct commands as `List<String>` for `ProcessBuilder`; never invoke `sh -c` with interpolated user values.
- Keep the SSH remote-shell argument controlled by the app; do not permit arbitrary extra arguments in the UI.
- Validate username, hostname, port, local roots, and relative remote roots.
- Keep remote destinations relative to the Storage Box home.
- Use strict host-key checking; never use `StrictHostKeyChecking=no` or an equivalent accept-all verifier.
- Set connection and keepalive timeouts.
- Redact credentials and sensitive filenames from production logs.
- Parse progress defensively. Do not make correctness depend on localized human rsync output.
- Treat exit code `0` as success. Handle partial-transfer and vanished-source exit codes explicitly, with tests and user-safe retry behaviour.
- Cancellation must terminate the process tree, leave safe partial files, and retain the pending checkpoint.

## 7. Hetzner onboarding

The onboarding is for non-technical users and must not require a terminal.

### Step 1: Welcome

Show only:

```text
PiffBackup

Back up your photos and videos to your private Storage Box.

[Set up backup]
```

### Step 2: Connect Storage Box

Fields:

- Username, initially empty and editable.
- Password, never prefilled or persisted in plaintext.
- Existing top-level backup folder, initially empty and editable. Accept a trailing slash but persist the normalized name without it. Limit the name to letters, numbers, dot, underscore, and hyphen so it can be passed safely to Hetzner's restricted shell.

Derive the default hostname as `<username>.your-storagebox.de`, while allowing an advanced override.

Use port 23. If SSH support or external reachability is unavailable, show a focused explanation and a button opening the Hetzner Console, with these instructions:

1. Open the Storage Box.
2. Enable `SSH Support`.
3. Enable `External Reachability`.
4. Return to PiffBackup and retry.

### Step 3: Establish durable authentication

Preferred flow:

1. Generate a dedicated app SSH keypair compatible with both the onboarding SSH library and the native rsync SSH transport.
2. Connect once using the supplied password.
3. Invoke Hetzner's `install-ssh-key` command and stream the public key through stdin.
4. Disconnect.
5. Reconnect with the key and run a harmless command/test.
6. Erase the password from memory and do not persist it.

If automatic key installation cannot be made reliable, provide a carefully documented fallback. Do not silently store a raw password.

Protect the private key at rest using Android Keystore-backed encryption and app-private storage. If the native SSH process needs a file, decrypt it only into app-private temporary state for the job, restrict permissions, and reliably clean it after completion. Ensure process death cleanup on the next launch.

Use trust-on-first-use for the non-technical default:

- Record and pin the first server host key.
- Clearly fail if it changes.
- Provide an optional advanced fingerprint comparison against Hetzner Console.
- Never silently replace a pin.

### Step 4: Verify the existing destination

Use Hetzner's supported, harmless `ls -d <backup-root>/` command to prove that the user-entered SSH-relative backup root exists. In this development environment, use `Matteo/` for real-device checks.

- Do not create or delete user media for this check.
- If a temporary marker is necessary, put it in a dedicated `.piffbackup-test` location, verify it through the selected protocol, and remove it immediately.
- Never prefill or hard-code a backup root.

### Step 5: Select and map folders

Allow the user to add any number of local roots, one system picker interaction per root.

For each local root:

- Suggest a matching existing remote directory beneath the configured backup root by name.
- Let the user choose an existing remote directory using a simple remote folder browser.
- Or let the user create a new subdirectory beneath the configured backup root.
- Let the user select `Media only — Fast` or `All files — Slower`.
- Detect overlaps and duplicate remote mappings.

### Step 6: Preview adoption

Run dry-run reconciliation, then show calculated counts and bytes. Do not start a potentially long upload without a clear summary and confirmation during initial adoption.

## 8. Home screen and navigation

The normal home screen must be exceptionally simple.

Use a single activity and a small number of fragments. No bottom navigation, navigation drawer, web UI, custom design system, advertising, analytics, or social features.

Normal home content:

1. One large status card:
   - `Everything is backed up`
   - `New items are ready to back up`
   - `Backing up · <percentage>`
   - `Backup paused`
   - `Needs attention`
2. One primary button:
   - `Back up now`
   - `Pause` during an active backup
   - `Resume` when paused
3. One compact line for last successful backup.
4. Two simple navigation rows:
   - `Folders · <dynamic count>`
   - `Settings`

During discovery:

```text
Looking for new photos and videos…
```

When changes are found, calculate and show:

```text
<dynamic count> new or changed items
<formatted bytes>

[Start backup]
```

After the initial adoption, support a setting to start uploading automatically after discovery, allowing the normal action to remain one tap.

## 9. UI technology and accessibility

Use lightweight, first-party Android UI technology:

- Kotlin.
- XML layouts with ViewBinding.
- AndroidX and Material Components for Views.
- Material 3 styling and dynamic colour where supported.
- First-party system folder picker, notification UI, permission settings, and platform dialogs.
- RecyclerView only where lists are necessary.
- No Jetpack Compose unless measurement demonstrates that it materially improves the product without violating the lightweight requirement; default is no Compose.
- No Hilt/Dagger. Use small, explicit constructor-based dependency wiring.
- No embedded browser for application UI.
- Respect system dark mode, font scaling, screen readers, reduced motion, and touch target guidance.
- Do not communicate state using colour alone.
- Supply concise English and Italian string resources; follow the system locale.

## 10. Background execution and durability

No Android app can guarantee that its process will never be killed. PiffBackup must use supported platform APIs and make process death harmless.

For a manual `Back up now` action:

- On API 34+, use a User-Initiated Data Transfer `JobService` with `setUserInitiated(true)`.
- Declare `RUN_USER_INITIATED_JOBS`.
- Publish the required system notification immediately.
- Run transfer work off the main thread.
- Call `jobFinished()` correctly.
- Provide graceful pause/cancel actions in the notification.

For API 33 fallback and optional scheduled backup:

- Use WorkManager.
- Promote long-running transfer work to a foreground worker with a `dataSync` notification.
- Declare the correct foreground-service permissions/type.
- Use unique work so two global backup sessions cannot race.
- Apply unmetered-network, charging, and battery-not-low constraints according to user settings.
- Do not hold a manual wake lock when the platform job API already manages execution.

Durability requirements:

- Before starting transfer, persist the previous generation, target generation, root mappings involved, generated file-list paths, total files, total bytes, and job status.
- Advance the successful generation checkpoint only after every root succeeds.
- On process death, retain the pending job and retry the same generation window.
- Rsync must skip already completed files and reuse `.rsync-partial` state.
- On launch, detect abandoned temporary credentials and clean them safely.
- Never report `Everything is backed up` based on an interrupted or partially successful run.

Android 15+ foreground execution limits must be respected. Long operations must remain restartable rather than relying on an immortal service.

Provide an optional reliability helper that opens the app's system battery settings and explains Samsung's `Unrestricted` option. Do not keep a permanent foreground service alive between backups.

## 11. Data model

Use Room. Keep the database small and focused; do not insert one row per remote file merely because the remote collection is large.

Suggested entities, subject to refinement:

```text
StorageBoxProfile
- id
- username
- hostname
- port
- remoteBasePath            // required normalized user input; no default
- encryptedCredentialRef
- pinnedHostKey
- setupCompleted

FolderMapping
- id
- displayName
- treeUri
- canonicalLocalPath
- relativeMediaStorePrefix
- relativeRemotePath
- mode                      // MEDIA_FAST or ALL_FILES
- enabled
- createdAt

MediaCheckpoint
- volumeName
- mediaStoreVersion
- successfulGeneration
- updatedAt

PendingBackupJob
- id
- previousGeneration
- targetGeneration
- status
- totalFiles
- totalBytes
- completedFiles
- completedBytes
- createdAt
- updatedAt

PendingRootWork
- jobId
- folderMappingId
- fileListPath
- status
- rsyncExitCode

BackupRun
- id
- startedAt
- finishedAt
- result
- discoveredFiles
- uploadedFiles
- uploadedBytes
- sanitizedErrorCode
```

For `ALL_FILES` mode, add a local file metadata table keyed by mapping ID and relative path. Do not use that table for remote destructive decisions.

Use migrations from the first shipped schema. Do not enable destructive fallback migrations in release builds.

## 12. Logging, privacy, and security

- No analytics or crash-reporting SDK in MVP.
- No telemetry leaves the device.
- Never log passwords, private keys, raw command lines containing secret paths, or complete personal filenames in release builds.
- Use structured internal error codes and redacted logs.
- Store only the minimum configuration required.
- Disable Android backup of stored credentials and private app state unless a secure, deliberate restore design is implemented.
- Protect exported components; default `android:exported="false"` unless a component must be exported.
- Use immutable PendingIntents where possible.
- Validate every Intent and persisted URI before use.
- Prevent path traversal in both local and remote paths.
- Treat filenames as untrusted data.

## 13. Lightweight requirements

- Prefer one `app` module unless a separate native-tools build module materially improves reproducibility.
- Enable R8 minification and resource shrinking for release.
- Avoid Compose, Hilt, general-purpose sync frameworks, embedded web servers, and unused protocol libraries.
- Do not bundle SMB; the SMB URL only identifies the existing destination. Runtime backup uses rsync over SSH.
- Stream MediaStore cursors and file-list creation.
- Do not hash all existing media by default.
- Do not copy media into app cache before upload.
- Keep transfer buffers bounded.
- Measure release APK size, cold startup, idle memory, discovery time, and transfer overhead. Record results in `PERFORMANCE.md`.

## 14. Project structure

A reasonable starting structure is:

```text
PiffBackup/
├── PROJECT.MD
├── README.md
├── PERFORMANCE.md
├── THIRD_PARTY_NOTICES.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/.../piffbackup/
│       │   │   ├── PiffBackupApp.kt
│       │   │   ├── ui/
│       │   │   ├── onboarding/
│       │   │   ├── folders/
│       │   │   ├── backup/
│       │   │   ├── mediastore/
│       │   │   ├── rsync/
│       │   │   ├── security/
│       │   │   ├── scheduling/
│       │   │   └── data/
│       │   ├── res/
│       │   └── jniLibs/arm64-v8a/
│       ├── test/
│       └── androidTest/
└── tools/
    └── native/
        ├── README.md
        ├── build-rsync.sh
        ├── build-ssh-client.sh
        └── checksums.txt
```

Use the actual package name consistently. Default to `dev.hummingdot.piffbackup` unless the repository owner specifies another application ID before the first signed release.

## 15. Testing requirements

### Unit tests

Cover at least:

- SMB-to-SSH path translation assumptions as configuration tests.
- Canonical local root resolution.
- Remote relative-path validation.
- Overlapping root detection.
- MediaStore generation window selection, including upper bounds.
- MediaStore version reset behaviour.
- Mapping MediaStore `RELATIVE_PATH` entries to selected roots.
- NUL-delimited rsync file-list generation with adversarial filenames.
- Rsync argument construction without shell interpolation.
- Source trailing-slash semantics.
- Progress parsing.
- Exit-code classification.
- Checkpoint advancement only after total success.
- Process-death recovery and replay.
- Human-readable file counts and byte formatting without fixed scale assumptions.

### Integration tests

Build a local test environment using a containerized SSH server with rsync. It must test:

- Empty source and destination.
- Existing destination with preserved timestamps.
- Existing destination with different timestamps but same sizes.
- New files only.
- Modified files.
- Unicode, spaces, newlines, emoji, apostrophes, and leading-dash filenames.
- Interrupted large upload and resume.
- Authentication failure.
- Host-key change.
- Unreachable server.
- Partial-transfer exit.
- Multiple folder mappings.
- No remote deletion after local deletion.

### Android instrumentation tests

Cover:

- All-files permission missing and later granted.
- Folder picker result validation.
- Persisted mapping display.
- UIDT notification and cancellation on supported API.
- WorkManager fallback.
- App/process restart with pending work.
- Accessibility labels and large font.

### Performance tests

Use generated test datasets at multiple configurable scales. The planning estimates must not appear as fixed application constants. Measure:

- Initial file-list reconciliation time.
- Incremental discovery time when only a small fraction changed.
- Peak memory while producing a large `--files-from` list.
- Rsync startup overhead.
- Transfer throughput for many small images and several large videos.
- Cancellation latency.
- Resume overhead.

Do not run destructive performance tests against any real user destination. Use `Matteo/` only for explicitly scoped development checks and keep connection verification read-only.

## 16. Delivery phases

Implement in this order:

1. **Native feasibility spike**: prove packaged ARM64 rsync + strict SSH transport on Android.
2. **Core command engine**: validated mappings, dry run, progress, cancellation, exit codes.
3. **MediaStore incremental planner**: version/generation checkpoints and `--files-from` generation.
4. **Room durability**: profiles, mappings, pending jobs, recovery.
5. **Hetzner onboarding**: password-once key installation, host pinning, and read-only verification of the user-entered backup root.
6. **Initial adoption flow**: remote mapping, dry-run summary, confirmed upload.
7. **Minimal home/folder/settings UI**.
8. **UIDT and WorkManager execution**.
9. **Security, accessibility, localization, performance, and release hardening**.

Current implementation status (2026-08-25): Phases 1–6 are implemented and
buildable. The first Phase 7 navigation slice is also implemented: successful
backup is no longer a terminal screen, while the broader home/history/settings
experience remains. Phases 8–9 remain pending. A user-driven live setup and
initial upload succeeded on an ARM64 Samsung SM-P610 using the explicit
`Matteo/` development root; `PERFORMANCE.md` records the observed baseline and
its limitations. No destructive remote test was performed.

After each phase, keep the project buildable and commit logically grouped changes if Git is available.

## 17. Definition of done

The project is done when all of the following are true:

- A clean checkout builds on a documented macOS setup.
- The native rsync and SSH tools are reproducibly buildable for `arm64-v8a`.
- A signed or debug APK installs on a Galaxy S23+.
- Onboarding connects to `<storage-box-username>.your-storagebox.de:23` without terminal use.
- The app requires, verifies, persists, and uses the existing user-entered backup root safely, without a hard-coded default.
- The user can configure any number of non-overlapping local roots and map them to remote folders.
- Initial adoption previews and skips same-path/same-size existing media without deleting anything.
- After a successful backup, the completion view lets the user check saved mappings for new files, change folders, or change the connection; it is never a terminal screen.
- Later runs use MediaStore generations and rsync `--files-from` rather than scanning the entire remote collection.
- The counts discussed in planning are nowhere hardcoded as limits or assumptions.
- A manual backup continues through supported background APIs, exposes notification progress, and survives interruption through restartable state.
- The home screen remains comprehensible to a non-technical user.
- Release builds are minified, credentials are protected, host keys are pinned, and logs are redacted.
- Unit, integration, instrumentation, and performance tests described above pass or have explicitly documented device-only limitations.
- `README.md` explains building, installing, onboarding, testing, native-tool reproduction, and known limitations.
- `PERFORMANCE.md` records real measurements rather than unsupported claims.

## 18. Authoritative references

Use current official documentation when implementing platform-sensitive behaviour:

- Android MediaStore generations: https://developer.android.com/reference/android/provider/MediaStore.MediaColumns
- Android all-files access: https://developer.android.com/training/data-storage/manage-all-files
- Android shared document/folder access: https://developer.android.com/training/data-storage/shared/documents-files
- Android user-initiated transfers: https://developer.android.com/develop/background-work/background-tasks/uidt
- Android WorkManager: https://developer.android.com/develop/background-work/background-tasks/persistent
- Android foreground-service timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout
- Android Keystore: https://developer.android.com/privacy-and-security/keystore
- Android NDK/ABI documentation: https://developer.android.com/ndk/guides/abis
- Hetzner SSH/rsync access: https://docs.hetzner.com/storage/storage-box/access/access-ssh-rsync-borg/
- Hetzner SSH key installation: https://docs.hetzner.com/storage/storage-box/backup-space-ssh-keys/
- Hetzner Storage Box protocol/path overview: https://docs.hetzner.com/storage/storage-box/access/access-overview/
- Rsync project and licence: https://github.com/RsyncProject/rsync and https://www.samba.org/rsync/GPL.html

When documentation and observed device behaviour differ, document the discrepancy, add a focused compatibility test, and choose the safest non-destructive behaviour.
