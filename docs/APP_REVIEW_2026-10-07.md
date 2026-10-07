**PiffBackup app review — 7 October 2026**

Reviewed commit `58d5d00`. The recommendation is to make correctness, recovery, and restore confidence the next release theme. Additional providers should follow that work: they will otherwise multiply the current edge cases.

The foundation is useful: explicit mappings, one-way transfers, no deletion options in the command builder, bounded native output, NUL-delimited file lists, Room transactions, configuration revisions, AES-GCM credentials, private temporary key storage, and strict host-key checking after enrollment. Preserve those choices.

**Verification and limits**

- Ran `testDebugUnitTest lintDebug assembleDebug` offline with Android Studio's bundled Java runtime: build successful; 71 tests across 22 suites, zero failures or errors.
- Lint completed with 16 warnings. The trust-manager warning points into transitive Bouncy Castle code; this review did not establish a reachable insecure TLS path in the app. It needs reachability analysis, not a blanket claim that SSH authentication is insecure.
- Read application code, tests, resources, CI, native build documentation, and the provided home screenshot. Did not run the UI, Android instrumentation, or remote Hetzner transfers.
- Local disposable-folder experiments using macOS `/usr/bin/rsync` (OpenRSYNC, protocol 29 compatibility) reproduced exit-code-zero stale content with both size-only comparison and same-second timestamps. These validate the comparison semantics, not the exact bundled Android executable.
- The initial-adoption size-only policy is explicitly requested in the original specification. The defect below is reusing it for later reconciliation and silently treating size matches as verified content.

Severity: P1 means fix before relying on the behavior in a release; P2 means material reliability or usability debt. Findings based on code traces are distinguished from runtime reproductions.

**1. P1 — Later reconciliation can permanently accept stale content**

Evidence: [RsyncCommand.kt:109](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/rsync/RsyncCommand.kt:109), [InitialAdoptionCoordinator.kt:253](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/adoption/InitialAdoptionCoordinator.kt:253), and [BackupExecutor.kt:238](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/scheduling/BackupExecutor.kt:238).

Trigger: back up a file, change its content without changing its length, then force reconciliation by changing mappings or losing the MediaStore checkpoint. Reconciliation uses the adoption builder's `--size-only`; the preview sees no upload, and confirmation skips that root while recording current local metadata and a new checkpoint. The changed file can then disappear from subsequent discovery.

Local reproduction: source `NEW!`, destination `OLD!`, different timestamps, `-rlt --size-only`: exit 0, destination remains `OLD!`.

Fix: introduce explicit comparison policies for initial adoption, routine incremental upload, and reconciliation. Restrict size-only adoption to the initial user-selected policy. Use a content-aware reconciliation when the baseline is untrusted, or clearly expose the choice and retain an unverified state. Never label a size match as content verification. Acceptance: same-size edits survive mapping changes and checkpoint resets. [Official rsync comparison documentation](https://download.samba.org/pub/rsync/rsync.1).

**2. P1 — Incremental discovery and transfer disagree on what changed**

Evidence: [AllFilesMetadataPlanner.kt:66](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/allfiles/AllFilesMetadataPlanner.kt:66) and [RsyncCommand.kt:69](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/rsync/RsyncCommand.kt:69).

The planner detects millisecond timestamp changes. Rsync's default quick check compares integer seconds. A same-length edit within one second is staged, skipped by rsync, and committed into the local baseline on exit 0. MediaStore generation changes can encounter the same problem when filesystem size/time remains unchanged.

Local reproduction: source and destination contain different four-byte strings; timestamps end in `.8` and `.1` of the same second. Default `-rlt` exits 0 and preserves the old destination content.

Fix: define a transfer policy that honors the planner's selected files. Options include `--ignore-times` for already selected incremental candidates, checksums restricted to candidates, or finer timestamp comparison with a content fallback. Account for restart bandwidth before choosing. Acceptance: a same-length subsecond edit actually changes the destination; a retry remains safe. [Official timestamp and checksum semantics](https://download.samba.org/pub/rsync/rsync.1).

**3. P1 — A file changing after preview can be baselined without upload**

Evidence: [InitialAdoptionCoordinator.kt:249](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/adoption/InitialAdoptionCoordinator.kt:249).

Code trace: preview an All-files root with zero uploads; modify an existing file while reviewing; confirm. Confirmation snapshots the new metadata, skips the root because its old summary had zero uploads, and applies the new snapshot. The next scan sees the edited file as unchanged even if its remote copy is old. This also affects roots excluded from durable confirmation because the preview said they had no work.

Fix: capture metadata with the preview and validate it at confirmation. Invalidate or replan roots changed since preview. At completion, only acknowledge versions known to have been transferred or compared under the chosen policy. Acceptance: editing a previewed no-op file before confirmation leaves it pending or uploads it.

**4. P1 — Recreating the activity with a normal-backup preview can crash**

Evidence: [MainActivity.kt:360](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/MainActivity.kt:360) and [MainActivity.kt:1176](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/MainActivity.kt:1176).

Code trace: retain a reconciliation preview after an earlier successful backup, then rotate the activity. The application-scoped coordinator retains the preview; the new activity's `latestHomeState` is null. Loading the profile calls `showAdoptionPreview`, which requires that state to be non-null. There is also no normal-preview rendering path for zero changed items: `NEW_ITEMS_READY` requires a positive count.

Fix: build a complete screen state from persisted data plus the preview, with an explicit no-work branch. Rendering should not require previous rendering to initialize state. Acceptance: rotate at every preview state, including zero uploads and a cancelled transfer.

**5. P1 — First upload and some reconciliation uploads bypass durable background execution**

Evidence: [MainActivity.kt:1201](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/MainActivity.kt:1201), [MainActivity.kt:1382](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/MainActivity.kt:1382), and [MainActivity.kt:1628](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/MainActivity.kt:1628).

Initial confirmation runs native transfers on the activity executor, without a durable pending job or foreground worker. Automatic normal reconciliation also calls this path directly; failed durable preparation falls back to it. Rotation cancels it, and process death loses preview ownership. The largest upload therefore has weaker guarantees than routine incremental work.

Fix: represent adoption and checkpoint-reset reconciliation as durable job kinds, with their own checkpoint preconditions, and schedule all confirmed uploads through one executor. Persist the job before transferring. Acceptance: first upload survives screen-off, activity recreation, and process death; resume uses the same job and never advances the checkpoint on partial failure. Follow the [Android UIDT lifecycle](https://developer.android.com/develop/background-work/background-tasks/uidt).

**6. P1 — Deleted staged files can block progress indefinitely**

Evidence: [RsyncExit.kt:22](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/rsync/RsyncExit.kt:22), [BackupExecutor.kt:184](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/scheduling/BackupExecutor.kt:184), and [IncrementalBackupCoordinator.kt:43](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/scheduling/IncrementalBackupCoordinator.kt:43).

Delete a file after staging. A missing explicit file-list entry can fail on every retry. Exits 23/24 are retryable, discovery returns the same active job, and mappings cannot change while it is active. No discard/reconcile operation is exposed. Local OpenRSYNC reproduction returned exit 23 for a missing staged entry.

Fix: distinguish transient transport errors from persistent source/permission/quota problems. Add bounded automatic retries and a durable reconcile/discard action. Record vanished files as an explicit exception to backup coverage while preserving existing remote copies; do not blindly accept every partial-transfer exit as success. Acceptance: deleting one staged file does not trap the entire profile, and unrelated pending files can complete.

**7. P2 — Historical failures remain current forever**

Evidence: [PiffBackupDao.kt:79](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/data/PiffBackupDao.kt:79) and [MainActivity.kt:474](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/MainActivity.kt:474).

Code trace: a job becomes `FAILED` or `NEEDS_RECONCILIATION`; a later backup succeeds. `latestProblemJob` still returns the old job, and home checks it before the successful checkpoint. Nothing marks it resolved. Old job artifacts also remain referenced and escape orphan cleanup.

Fix: persist resolution/supersession, distinguish history from active problems, and clean artifacts once recovery no longer needs them. Acceptance: recovery returns home to a healthy state while retaining a readable historical failure record.

**8. P2 — Returning to the app can show obsolete transfer state**

Evidence: [MainActivity.kt:1530](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/MainActivity.kt:1530) and [MainActivity.kt:1618](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/MainActivity.kt:1618).

Code trace: leave home while uploading; upload completes while the activity is stopped; return to the same activity. The listener was removed, events are not replayed, and resume does not reload backup state. Home can continue showing a running job already completed and cleaned up.

Fix: observe durable state using lifecycle-aware Room flows, with progress as an overlay keyed by job ID. Reload on return until that is implemented. Acceptance: background success, failure, and pause are visible immediately on return.

**9. P2 — Cancellation has a native-process registration race**

Evidence: [BackupExecutor.kt:66](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/scheduling/BackupExecutor.kt:66) and [BackupExecutor.kt:135](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/scheduling/BackupExecutor.kt:135).

Code trace: request stop after the loop's cancellation check but before `running.set(process)`. The stop flag is set, but there is no registered process to cancel; the executor then waits for the transfer. API-33 coroutine cancellation also wraps blocking work and reaches the worker's catch only after that work unwinds. Native cancellation itself waits up to two seconds and can be invoked from main-thread callbacks.

Fix: associate the running process with job ownership, recheck stop immediately after registration, connect coroutine cancellation directly to process cleanup, and move blocking termination away from the UI thread. Test cancellation during process creation, waiting, completion, and scheduler replacement; verify rsync's SSH child exits too.

**10. P2 — Connection changes mutate the active known-host file before commit**

Evidence: [HetznerOnboardingCoordinator.kt:69](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/onboarding/HetznerOnboardingCoordinator.kt:69) and [KnownHostStore.kt:39](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/onboarding/KnownHostStore.kt:39).

Code trace: start changing an existing connection to another hostname; successful authentication overwrites the profile's known-host file. Cancel destination selection or fail saving the profile. The database still identifies the old endpoint, but its native trust file contains the new endpoint. Existing backups can fail despite the cancelled change.

Fix: use a temporary enrollment identity, then promote it only after destination verification and profile commit. Make the database pin authoritative and regenerate native trust material from it. Acceptance: abandoning or failing a connection change leaves the previous connection usable.

**11. P2 — Home claims current coverage without checking current files**

Evidence: [HomeScreenState.kt:38](/Users/matteo/AndroidStudioProjects/PiffBackup/app/src/main/java/com/d35p4c1t0/piffbackup/ui/HomeScreenState.kt:38).

A valid historical checkpoint renders “Everything is backed up” on launch, even after new photos or documents arrive. A manual no-op check does not record a new last-check time. The screenshot reinforces the broader claim.

Fix: show “Last backup completed” with timestamp and scope. Persist separate last-discovered, last-uploaded, and last-verified times. Show “No changes found at …” only after discovery, and treat remote verification as a separate fact. Acceptance: reopen after adding a file and the UI does not assert unobserved current coverage.

**Additional engineering improvements**

- Validate metadata sidecars fully. `isValid` checks only the magic header, and EOF while reading a record length is treated as clean termination even after consuming a partial integer. Add format version, expected record count, integrity digest/footer, strict EOF handling, and corruption tests. This is primarily a recovery/rework risk; it is not evidence of remotely lost content.
- Move application startup recovery and orphan scanning off the main thread. `PiffBackupApp.onCreate` currently runs database recovery through `runBlocking`; large state and slow storage can delay first draw. Gate actions on a visible initialization state rather than swallowing initialization exceptions.
- Store actual transferred file counts and bytes. Completion records planned totals as uploads even if rsync skipped entries. Separate discovered, compared, transferred, and verified metrics, and measure planning, connection, transfer, and commit durations separately.
- Decode stdout as a stream. Constructing a UTF-8 string independently for each byte chunk can corrupt filenames split across chunk boundaries. Use an incremental decoder and keep UI observers unable to break pipe draining.
- Treat broad exception handling as a boundary, not the error model. Preserve cancellation, map known failures to actionable categories, and expose sanitized diagnostics such as stage, job ID, error kind, stop reason, and retry count.
- Reconsider automatic `--whole-file` for mutable large documents and partial retries. Benchmark default rsync delta behavior against whole-file for media, many small files, and interrupted large files. Do not promise faster performance without measurements.
- Bound failed-job retention, metadata storage, and retry churn. Add reconciliation/discard operations, storage usage reporting, and controlled pruning of local artifacts.

**Product improvements, ordered by value**

| Priority | Improvement | User outcome |
| --- | --- | --- |
| First | Restore a file or folder into a new local destination | Users can prove their backup is useful before a disaster |
| First | Verification action with sampled and full modes | Detect missing/stale remote content; make cost and verification scope explicit |
| First | Backup history with failure reason and recovery action | Replace generic “Try again” with an understandable next step |
| First | Per-folder status, included file types, and last check | Show coverage and explain Media-only omissions |
| First | Wi-Fi/unmetered, charging, and battery preferences | Avoid surprise mobile-data and battery consumption |
| Next | Version preservation or documented server snapshot support | Recover a good version after a bad edit overwrites a file |
| Next | Real scheduled discovery and backup | Protect files without remembering to press a button |
| Next | Exclusions and temporary/hidden-file policy | Keep caches and volatile working files out of backups |
| Next | Guided device replacement and configuration export | Reconnect mappings safely without exporting plaintext credentials |
| Next | Verified first enrollment and key rotation/revocation | Make server identity and lost-device recovery understandable |
| Later | Provider abstraction, then one additional rsync/NAS target | Expand reach while preserving tested safety guarantees |
| Later | SD-card support, more architectures, and optional SAF transport | Reach more devices with an explicit storage/transport design |
| Later | Optional client-side encrypted archive backend | Protect remote contents from the storage provider with a deliberate recovery-key design |

One-way upload without remote deletion still overwrites changed files. Version preservation is therefore a distinct feature, not something the current deletion guard provides.

For enrollment, `PinningHostKeyVerifier` currently accepts the first presented key automatically before password authentication. Subsequent pinning is valuable, but first-use server identity is unverified. Add an expected fingerprint or verification step before sending the password, and retain the first verified pin even if destination setup is interrupted. [OpenSSH describes host-key checking and first enrollment](https://man.openbsd.org/ssh.1).

Scheduled backups need a separate execution policy: Android user-initiated jobs generally require a visible app or an allowed exception. Do not reuse the current UIDT scheduler blindly for timer-driven work. [Android scheduling conditions](https://developer.android.com/develop/background-work/background-tasks/uidt).

**Architecture that supports those improvements**

1. Keep Room as the source of truth. Define job kinds for adoption, incremental upload, reconciliation, verification, and restore; define typed comparison policies separately.
2. Give each operation explicit durable states: planning, awaiting confirmation, queued, running, waiting to retry, paused, succeeded, failed, superseded. Bind OS scheduling state to the durable job without equating “queued” with “transferring.”
3. Move workflows from the 1,684-line activity into a ViewModel and use cases. Keep the current XML/Material UI; a UI framework rewrite is not required to solve the problems.
4. Make process execution a cancellable transport boundary with owned resources, typed results, bounded diagnostics, and actual transfer metrics.
5. Define transport capabilities before adding providers: preview, resume, verification, version preservation, restore, and path constraints. Require each backend to pass a shared contract suite.
6. Keep remote mutation behind explicit job intent. Restore should target a new local directory by default; remote retention/deletion should remain separately authorized and tested.

**Suggested release sequence and acceptance gates**

| Release theme | Scope | Completion gate |
| --- | --- | --- |
| Correctness | Comparison policies; preview invalidation; missing-source recovery; resolved failures | Same-size and subsecond edits reach the destination; no stale version is silently acknowledged |
| Reliability | Durable first upload; cancellation; lifecycle state; connection staging | Pause, rotate, kill, restart, and network loss preserve job ownership and safe checkpoint rules |
| Confidence | Restore, verification, history, honest coverage, per-folder status | Restore a representative folder and compare contents; explain every omitted or failed file |
| Convenience | Scheduling, network/battery controls, exclusions | Automated runs respect constraints and surface actionable failures |
| Expansion | Provider contract, NAS target, broader storage support | Every backend passes the same correctness and recovery suite |

CI currently compiles instrumentation tests but does not execute them. Add local-only device runs for the bundled native tools and Room recovery, using API 33 and at least one API 34+ device. Standard x86 emulators cannot validate the current ARM64-only executable set without additional packaging. Add activity lifecycle tests, real local rsync content comparisons, process-death tests, sidecar corruption tests, and controlled retry tests. Continue avoiding live user destinations in automated tests.

The central acceptance scenario should be: back up a folder, edit same-size files, interrupt a transfer, delete one pending source, recreate the activity, resume, then restore into a fresh location and compare the contents. Passing that scenario is more valuable than adding several storage providers.
