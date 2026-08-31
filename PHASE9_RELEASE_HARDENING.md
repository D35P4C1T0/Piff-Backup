# Phase 9 — release hardening

Status: complete on 2026-08-31.

Phase 9 closes the security, accessibility, localization, performance, and
release-preparation requirements in `reference/PROJECT.MD`. No Phase 9 test
connected to a Storage Box or mutated a remote destination.

## All-files incremental discovery

Normal `All files — Slower` runs no longer force a full rsync reconciliation.
The planner walks each enabled local root once and compares `(relative path,
size, modified time)` against the Room `all_files_metadata` index in bounded
256-item batches. Only new or changed paths enter the NUL-delimited rsync list;
local removals update the local index but never request remote deletion.

Every scan also streams a complete candidate metadata baseline to an atomic,
app-private binary sidecar. A pending All-files root cannot start or recover
without both its file list and sidecar. Room replaces the baseline inside the
same transaction that records rsync success. Paused, failed, killed, or corrupt
work cannot advance it. Successful-job cleanup removes only the exact generated
list and sidecar; orphan cleanup is constrained to their private directory and
known filename shapes.

Initial adoption and full reconciliation derive their candidate baseline from
the exact immutable preview list. Files created after preview are intentionally
absent from that baseline and remain discoverable on the next normal run.

Existing installations that predate this metadata index safely enumerate one
complete All-files candidate list once; rsync remains one-way and uses no delete
option. A successful run establishes the faster metadata baseline thereafter.

## Security and privacy

- Release builds suppress the two bounded native/bootstrap diagnostics;
  production logs contain no credential, remote path, or personal filename.
- The merged release manifest was inspected. App-owned internal service,
  receiver, and provider components are explicitly non-exported; the launcher
  is exported by design. Exported AndroidX system hooks are guarded by platform
  `BIND_JOB_SERVICE` or `DUMP` permissions.
- Notification intents are explicit and immutable. Job IDs, local roots,
  remote paths, tree URIs, MediaStore filenames, and metadata sidecars retain
  strict validation before use.
- Android backup remains disabled, no analytics/crash SDK was added, SSH host
  keys remain pinned, and no rsync command enables remote deletion.

## Accessibility and localization

- Every screen has a named accessibility pane and a semantic heading. Dynamic
  status remains a polite live region; decorative progress indicators are not
  announced as duplicate content.
- Screen changes return the scroll container to the top and emit one window
  state change. Transfer-progress animation follows Android's animator-enabled
  setting, including reduced-motion configurations.
- Material controls retain platform touch targets, layouts scroll at enlarged
  text sizes, status is expressed with text rather than color alone, and the
  Material theme supports dark mode.
- English and Italian resource sets have identical keys. The manifest declares
  both supported locales so Android 13 per-app language settings work.

## Release and verification

Host gates:

- `testDebugUnitTest`
- `lintDebug`
- `assembleDebug`
- `assembleDebugAndroidTest`
- `assembleRelease`, including R8 minification and resource optimization

Device gate: 20 local-only instrumentation tests pass on a Xiaomi Mi 9T Pro,
Android 13/API 33, `arm64-v8a`. Coverage includes packaged native binaries,
strict command behavior, encrypted credentials, initial-adoption interruption,
Room process-death recovery, metadata success atomicity, and bounded local
performance measurements.

The unsigned optimized release APK is 4,415,663 bytes. Cold startup, idle
memory, metadata discovery, and local rsync overhead are recorded in
`PERFORMANCE.md`.

## Remaining distribution/manual checks

- A production signing identity and store distribution channel are outside the
  repository, so the release artifact remains unsigned.
- TalkBack gesture traversal and visual inspection across every configured
  folder count still require a human device pass; lint and semantic structure
  are automated, but they cannot replace assistive-technology judgment.
- Transfers remain sequential. Parallel rsync streams are still an optional
  future experiment and must preserve bounded resource use, cancellation, and
  checkpoint atomicity before adoption.
