# Phase 2 — core command engine

Status: implemented and verified on the host and an ARM64 Samsung SM-P610
running API 33 on 2026-08-24. Remote integration remains intentionally pending
until an isolated test server is available.

## Safety model

- Local roots are canonicalized and must remain under the caller-provided
  shared-storage root. Canonicalization rejects traversal and symlink escapes.
- Remote roots are relative, component-validated paths and must remain under
  the configured user-entered base. Absolute paths, empty components, `.` and
  `..` components, NUL, and line breaks are rejected.
- Local mappings and remote mappings may not overlap one another.
- Commands are passed directly to `ProcessBuilder` as argument lists. No shell
  interpolation is used.
- Rsync commands reject every `--delete` and `--delete-*` argument. The builder
  uses no checksum or compression for initial adoption.
- An inaccessible local root fails before rsync starts. An empty accessible
  root is non-destructive because deletion is unavailable.
- SSH uses port validation, key-only batch mode, strict known-host checking, an
  app-controlled home directory, a 60-second idle timeout, and rsync's
  60-second I/O timeout.
- Parser records deliberately omit filenames. Production code does not log
  command arguments, credentials, or rsync output.

## Initial-adoption commands

The preview and confirmed-transfer builders share these semantics:

- `-rlt --size-only --whole-file`
- safe partial transfer via `.rsync-partial`
- no owner, group, or permission propagation
- protected arguments and intentional source/destination trailing slashes
- strict Dropbear `dbclient` transport
- one mapping per invocation

The preview adds `--dry-run`. It requests rsync's itemized output twice so
unchanged files are included, then streams filename-free `%i:%l` records into
64-bit counts and bytes. Summary correctness is independent of the bounded
diagnostic-output capture and does not retain a line per file. This gives the
dynamic “already backed up”, “needs uploading”, and “bytes to upload” values
without parsing localized statistics. The transfer adds total progress output;
progress parsing is best effort and is never used to decide correctness.

Cancellation first requests normal process termination and uses a bounded
forced fallback. Stdout and stderr are drained concurrently with bounded
capture, while progress is observed incrementally. Cancellation takes
precedence in result classification. Rsync exit 23 (partial transfer) and 24
(vanished source) have explicit retryable partial-result classifications.

## Verification

The Phase 2 verification run completed with:

- 24 passing host unit tests covering local and remote traversal, mapping
  overlap, controlled argument construction, strict SSH, no-delete semantics,
  source trailing slashes, output injection/malformed records, 64-bit overflow,
  split progress records, cancellation, bounded output, and exit codes.
- A successful debug APK and instrumentation APK build.
- Android lint: 0 errors and 2 expected warnings (the deliberately pinned
  Gradle version and the specification's ARM64-first ABI choice).
- 2 passing device instrumentation tests. The Phase 2 test ran packaged rsync
  only between disposable app-cache directories and confirmed that a same-path,
  same-size file is counted as adopted while a new five-byte file is counted for
  upload.

No hostname, credential, network connection, Storage Box path, or user backup
folder was used by this verification.

## Deferred integration checks

Before any real transfer is exposed, use a dedicated test identity and unique
disposable destination on an equivalent SSH server to verify authentication,
host-key mismatch rejection, unreachable-host timeouts, cancellation latency,
and that no `rsync` or `dbclient` process survives cancellation or app death.
This is also where the SMB `/backup/<backup-root>/` to SSH-relative
`<backup-root>/` mapping must be confirmed. A real user destination must not be
used for destructive tests.
