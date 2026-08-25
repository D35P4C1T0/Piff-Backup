# Phase 8 — Background execution

Status: implemented on 2026-08-25.

## Execution paths

- A manual backup on API 34+ is scheduled as a user-initiated data-transfer
  `JobService`. Scheduling occurs from the visible Home action, declares
  `RUN_USER_INITIATED_JOBS`, requires a network, supplies the estimated upload
  bytes, and publishes its notification before transfer work begins.
- API 33 uses one unique WorkManager request. Its `CoroutineWorker` is promoted
  immediately with a `dataSync` foreground notification.
- The notification and Home screen use the same progress events and both offer
  Pause. Resume schedules the retained durable job rather than rediscovering a
  different generation window.

There is no permanent service and no manual wake lock. Android may stop either
execution path; native rsync is cancelled, `.rsync-partial` remains reusable,
and the pending job stays restartable.

## Planning and durability

`Media only — Fast` mappings use the Phase 3 MediaStore generation planner.
The generated NUL-delimited file lists and generation window are persisted
before scheduling. An empty plan advances only to the stable snapshot that was
just checked.

`All files — Slower`, or a MediaStore reset that requires reconciliation,
keeps the Phase 6 dry-run review. After the user presses Start, ownership of
that exact reviewed list moves to a durable reconciliation job. It continues
to use `--size-only`; generation-incremental jobs do not, so same-size media
modifications are not accidentally skipped.

The checkpoint advances only after every root reports success. A stopped root
becomes retryable or paused, and launch recovery converts abandoned running
state into retryable state. Startup cleanup deletes only unreferenced,
recognizably generated file lists; lists referenced by a pending job survive
process death.

## Safety

- No rsync command contains a delete option.
- Strict host-key checking and the encrypted, temporary private-key flow are
  unchanged.
- Notification actions use immutable explicit `PendingIntent`s.
- The app requests Android 13+ notification permission before scheduling so
  progress and Pause remain visible.
- Automated tests use temporary local files only. They never connect to a
  Storage Box.

## Verification

Host gate:

```text
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
BUILD SUCCESSFUL
```

Samsung SM-P610 (Android 13 / API 33), wireless ADB:

```text
Room durability + initial-adoption handoff + native packaging
OK (13 tests)
```

The suite includes an explicit regression for converting an unchanged
post-adoption reconciliation preview into durable background work without
changing the configuration revision. The API 34 UIDT branch is compiled
against API 37 but still requires a device/emulator running API 34+ for an
end-to-end platform test. A real remote background transfer remains a
user-driven check; no automated remote operation was performed.
