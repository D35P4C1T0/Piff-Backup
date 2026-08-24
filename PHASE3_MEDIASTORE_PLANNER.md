# Phase 3 — MediaStore incremental planner

Status: implemented and verified on the host and an ARM64 Samsung SM-P610
running API 33 on 2026-08-24. The planner is deliberately persistence-agnostic;
Phase 4 will store its checkpoints and pending work in Room.

## Generation protocol

The Android source reads `MediaStore.getVersion()` before and after
`MediaStore.getGeneration()` to produce a stable target snapshot. Planning is
incremental only when all of these conditions hold:

- a previous fully successful checkpoint exists for the same volume;
- the MediaStore version is unchanged;
- the generation did not move backwards;
- the version remained stable around the target-generation read; and
- the app has full image and video access.

Missing or invalid checkpoints require a full reconciliation. Partial Android
14 selected-photo access also requires reconciliation rather than silently
advancing past inaccessible older items.

The query selects image and video rows whose `GENERATION_ADDED` or
`GENERATION_MODIFIED` is greater than the previous successful generation and no
greater than the snapshotted target. It uses only `RELATIVE_PATH` and
`DISPLAY_NAME`; it never reads the deprecated `_data` column. Rows outside the
configured folder prefixes are ignored, and rows newer than the target remain
eligible for the next run.

The planner exposes a proposed checkpoint but returns it from the completion
gate only when every planned root reports success. A failed or cancelled root
therefore cannot advance the generation. With no changed roots, the empty set
completes successfully without opening a network connection.

## File-list and command safety

Each selected root gets at most one app-private temporary file list, created
lazily on the first matching row. Entries are written incrementally as UTF-8
bytes followed by NUL, so the planner does not retain the changed collection in
memory. The path model:

- accepts spaces, Unicode, emoji, embedded newlines, leading dashes,
  apostrophes, and backslashes;
- rejects absolute paths, NUL, empty components, `.` and `..` components; and
- calculates every entry relative to its selected local root.

An incremental rsync command can only be built from a non-empty planned list.
It uses `--from0`, `--files-from=<app-private-file>`, `-rlt`, `--whole-file`,
safe partial files, protected arguments, strict SSH, and total progress. It
does not use adoption-only `--size-only`, checksums, compression, dry-run, or
any deletion option. The source trailing slash remains intentional.

Plans preserve configured mapping order, yielding one rsync invocation per
changed root for the later sequential executor. There is never one connection
per file.

## Verification

The Phase 3 verification run completed with:

- 37 passing host unit tests in total. New coverage includes checkpoint
  absence/version/volume/rewind/access invalidation, generation boundaries,
  rows added after the target, modified rows, prefix filtering, overlapping and
  escaping paths, cleanup after a malformed row, empty-plan behavior, raw NUL
  encoding, completion gating, and incremental command flags.
- Successful debug and instrumentation APK builds.
- Android lint: 0 errors and 2 expected warnings (the deliberately pinned
  Gradle version and the specification's ARM64-first ABI choice).
- 3 passing device instrumentation tests. The new test used packaged rsync and
  a disposable app-cache source/destination to prove `--from0` transfer of
  spaces, emoji, apostrophes, a leading dash, and an embedded newline while an
  unlisted file remained untouched.

The device test did not query the owner's MediaStore, request media access,
contact a server, or name or modify the real `Bianca/` destination.

## Deferred to later phases

- Phase 4 persists profiles, checkpoints, file lists, pending jobs, and recovery
  state in Room.
- Runtime permission and partial-access UX belongs to onboarding and the later
  UI/hardening phases.
- Initial adoption establishes the first successful checkpoint in Phase 6.
- Background execution consumes plans sequentially in Phase 8.
- “All files — Slower” metadata indexing for non-MediaStore content remains a
  distinct planner path; it must not enumerate the remote tree.
