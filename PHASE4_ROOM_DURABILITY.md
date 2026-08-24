# Phase 4 — Room durability

Status: implemented and verified on the host and an ARM64 Samsung SM-P610
running API 33 on 2026-08-24.

## Persisted model

Room schema version 1 stores profiles, explicit folder mappings, MediaStore
checkpoints, pending backup jobs, per-root work, successful run summaries, and
compact local metadata for the future `All files — Slower` path. It does not
store one row per remote file. Credentials are represented only by an
app-private encrypted-credential reference; raw passwords and keys do not
belong in Room.

The exported version-1 JSON schema is committed as the migration baseline.
The production builder has no destructive-migration fallback. Every future
schema change must add and test an explicit migration from this first schema.

Configuration writes are transactional. Mapping roots remain canonical and
beneath the allowed shared-storage root, remote roots remain beneath the
configured relative backup base, folder-picker tokens are restricted to the
primary ExternalStorageProvider, and overlapping mappings are rejected.
Changing a profile or its mappings increments a configuration revision and is
blocked while pending work exists.

## Durable generation protocol

Phase 3 incremental plans are persisted through one adapter that matches every
planned local/remote root to exactly one enabled `Media only — Fast` mapping.
Before transfer starts, one transaction stores:

- the MediaStore version and previous/target generation window;
- the configuration revision and immutable local/remote mapping snapshots;
- the exact app-private NUL-list path for every changed root;
- 64-bit per-root and aggregate file/byte totals; and
- job/root status and timestamps.

Only one global active backup job is permitted. A root can start only when its
persisted file list still exists, is non-empty, and remains inside the one
allowed app-private list directory.

Each result is recorded transactionally. The successful checkpoint advances
to the target generation in the same transaction that marks the job successful,
and only after every root succeeds. Failure or cancellation retains the old
checkpoint, original generation window, lists, mapping snapshots, and safe
`.rsync-partial` retry semantics. Thus interrupted work cannot produce an
“Everything is backed up” state.

On launch, a formerly running root/job becomes retryable with the same window.
Recovery validates configuration and checkpoint revisions, generation and
counter invariants, mapping snapshots, root ordering/statuses, aggregate totals,
and required list files. Missing or corrupt state is marked as needing
reconciliation instead of advancing a checkpoint. Successful-job cleanup
deletes only the exact persisted list files and pending rows; unrelated files
and the run summary survive.

## Verification

The Phase 4 verification run completed with:

- 37 passing host unit tests;
- successful debug, instrumentation, and lint builds;
- 8 passing tests on the connected API 33 device: the 3 packaged-native tests
  plus 5 Room durability tests; and
- Room coverage for database close/reopen recovery, cancellation retention,
  all-roots checkpoint atomicity, exact cleanup, missing-list reconciliation,
  configuration locking, and direct persistence of Phase 3 plans.

All durability tests use unique databases and files beneath app-private cache.
They do not query personal media, open a network connection, or name, inspect,
or modify a real user destination.

## Deferred to later phases

- Phase 5 establishes encrypted key material and pinned host identity during
  onboarding; Room will retain only references and the host-key pin.
- Phase 6 records initial-adoption runs and establishes the first successful
  checkpoint after confirmed transfer.
- Phase 8 drives these pending jobs through UIDT/WorkManager and passes native
  process results into the transactional result API.
- The `All files — Slower` planner will populate the local metadata table in a
  later phase without using it for remote deletion decisions.
