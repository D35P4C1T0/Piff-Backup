# Phase 7 — Minimal home, folders, and settings

Status: implemented and verified by host tests, APK compilation, and Android
lint on 2026-08-25. Real-device visual validation remains pending because the
previous wireless ADB endpoint was no longer listening.

## Home

A completed setup now opens directly on the normal home screen instead of a
terminal success page. The home surface contains:

- one status card for backed up, discovery, ready, active, paused, and
  attention states;
- one context-sensitive primary action (`Back up now`, `Start backup`, `Pause`,
  or `Resume`);
- the latest successful backup time from a bounded Room query;
- a dynamic `Folders · <count>` row; and
- a `Settings` row.

`Back up now` starts discovery directly with the saved mappings. When changes
are found, the same home card shows the calculated count and bytes before the
user starts transfer. If no upload is needed, the successful checkpoint is
advanced without a redundant confirmation. During a foreground transfer the
home card shows progress; pausing cancels the current native process safely and
keeps the frozen preview available for resume.

The proven Phase 6 reconciliation engine remains underneath this UI. Until
Phase 8 wires the durable incremental executor and scheduler, a later manual
check still performs a full safe reconciliation rather than claiming the final
MediaStore-generation execution path.

## Folders

The existing protected system-picker and remote-browser flow now acts as the
normal folder-management screen. It redisplays persisted mappings, supports
adding and removing individual mappings, keeps overlap validation, and offers a
safe return to Home that discards unsaved edits. Saving changes still requires
the explicit check step so a new or changed mapping is reconciled before it can
be reported as backed up.

## Settings

Settings shows the configured server, user-entered backup root, and pinned
server fingerprint. It also provides:

- an opt-in `Start uploading automatically after discovery` preference;
- plain-language Samsung `Unrestricted` battery guidance and a link to the
  app's system settings; and
- the existing protected connection-change flow.

The auto-start preference is local app-private state. It never bypasses
discovery, enables deletion, or changes the selected folders.

## Verification and safety

- Home state validation covers dynamic mapping counts, valid and invalidated
  checkpoints, last-success retention, ready totals, and progress bounds.
- The Room instrumentation gate checks the bounded latest-success query.
- Host unit tests, debug APK, instrumentation APK compilation, minified release
  APK, and lint are the Phase 7 build gate.
- English and Italian resources cover every new user-facing string.
- Home and Settings use separate lightweight ViewBinding layouts to keep the
  activity layout below the lint view-count threshold.
- No network command, upload, remote listing, or remote deletion was run while
  implementing or verifying Phase 7.

## Deferred to Phase 8

- API 34+ User-Initiated Data Transfer `JobService` execution.
- API 33 WorkManager foreground fallback and notification controls.
- Durable incremental discovery/transfer orchestration, process-death replay,
  and persistent pause/resume across app restarts.
