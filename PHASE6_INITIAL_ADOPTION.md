# Phase 6 — Initial adoption

Status: implemented and verified on the host and an ARM64 Samsung SM-P610
running API 33 on 2026-08-24.

## Folder access and mapping

PiffBackup now explains why direct file access is required before opening
Android's app-specific all-files settings. This is an intentionally sideloaded
backup app: the packaged native rsync process needs ordinary filesystem paths,
and a Storage Access Framework grant alone does not turn a picker URI into a
path that rsync can traverse.

The app still uses `ACTION_OPEN_DOCUMENT_TREE` for every user-selected root.
It treats the returned URI only as a selection token, accepts only the primary
`ExternalStorageProvider`, canonicalizes the corresponding path beneath primary
shared storage, rejects the shared-storage root and `Android/data` or
`Android/obb`, and persists the read grant. Local and remote overlap validation
runs before a draft mapping can be accepted and again transactionally when all
mappings are saved.

Each mapping offers `Media only — Fast` or `All files — Slower`. The UI allows
any number of roots, suggests a same-named existing remote directory, and lets
the user navigate one remote level at a time or choose a new child directory.
The new directory is not created during browsing or preview; rsync creates the
destination only after explicit transfer confirmation.

## Remote folder browser

The browser uses actual packaged rsync over the already pinned, key-only native
SSH transport. `--list-only --dirs --protect-args` requests only the current
directory, so browsing does not recursively enumerate the remote collection.
The remote path remains a validated argument after `--`; it is never inserted
into a shell command.

Rsync 3.4.2 ignores a custom output format in list-only mode. A device test
therefore proves and parses its fixed `LC_ALL=C` long-list format, accepts only
directory records with safe immediate child names, and rejects truncated or
failed output. Spaces, apostrophes, Unicode, emoji, and leading dashes remain
valid. Newline-containing directory names cannot be represented by this simple
line-oriented browser and are ignored safely.

## Frozen preview and confirmation

Before opening a network connection, Phase 6 freezes every selected root into
an app-private NUL-delimited file list:

- `Media only — Fast` queries indexed images and videos through the stable
  MediaStore generation snapshot and filters them to the selected roots.
- `All files — Slower` walks regular local files without following symlinks.

The NUL format preserves spaces, Unicode, emoji, apostrophes, leading dashes,
and newlines. Preview and transfer use the same exact list with `--from0` and
`--files-from`, so the calculated summary describes the files the confirmation
will process. Empty roots do not open a connection.

Preview retains the Phase 2 adoption semantics: recursive relative paths,
`--size-only`, `--whole-file`, `--dry-run`, strict key-only SSH, no checksum,
no compression, and no deletion. Streaming item records intentionally contain
only change metadata and lengths, not personal filenames. The preview is
rejected if rsync does not account for every frozen file.

The user sees calculated already-backed-up items, items requiring upload, and
bytes before an explicit `Start backup` action. Confirmed roots run sequentially
with progress and cancellation. A single Room transaction writes the successful
MediaStore checkpoint and run summary only after every applicable rsync process
returns exit code 0. Failure, cancellation, configuration changes, or process
death leave the checkpoint absent, so rerunning reconciliation is safe and
already transferred same-size files are skipped.

Adoption file lists have a dedicated no-backup directory. Normal completion
deletes the exact lists immediately; process-death cleanup deletes only exact
abandoned adoption-list names on the next launch and cannot touch durable
incremental lists or unrelated files.

## Verification

The Phase 6 gate completed with:

- 54 passing host unit tests;
- 16 passing Android tests on the wireless SM-P610, including the packaged
  ARM64 rsync list-only compatibility test;
- successful debug, instrumentation, and minified release APK builds;
- Android lint with no errors; and
- an unsigned R8-minified release APK of 4,019,362 bytes.

Tests cover protected non-recursive browsing, safe parser rejection, exact
NUL-list contents for both mapping modes, source/destination overlap checks,
exact abandoned-list cleanup, primary picker-token validation, checkpoint and
run-summary atomicity, and failure retention. All Android fixtures stay beneath
app-private storage. No live Storage Box command was run, no upload occurred,
and the real `Bianca/` directory was not read or changed.

## Deferred to later phases

- Phase 7 replaces the completion placeholder with the minimal home, folders,
  and settings navigation and exposes ongoing mapping management.
- Phase 8 moves potentially long preview/transfer work into supported UIDT or
  WorkManager execution with durable pending adoption state. Phase 6's current
  foreground execution is cancellation- and retry-safe, but it does not claim
  to survive process death in place.
- Phase 9 performs the final all-files-access, accessibility, localization,
  performance, and release review.

Live account validation still requires an explicit user-run preview and
confirmed transfer. Never use destructive or performance fixtures against the
real `Bianca/` destination.
