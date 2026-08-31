# Performance measurements

Status: first real-device baseline captured on 2026-08-25. This is an observed
baseline, not an optimization or parallel-transfer claim.

## Environment

- Samsung SM-P610, Android API 33, `arm64-v8a`
- Wi-Fi 5; instantaneous tablet transmit link reported 58 Mbit/s at -64 dBm
- User-entered `Matteo/` Storage Box development root
- One `All files — Slower` mapping and one sequential native rsync process
- Packaged rsync 3.4.2 and Dropbear 2025.89

## Initial transfer baseline

| Files | Payload | Observed rsync process | Approximate payload rate |
| ---: | ---: | ---: | ---: |
| 18 | 57,864,205 bytes | about 21 seconds | about 22 Mbit/s |

The user explicitly reviewed the dry-run summary and started the upload through
the app. A passive ADB poll sampled the rsync process at one-second intervals;
the duration and derived rate are therefore approximate. The run completed
successfully and no remote deletion was requested or performed.

## Measurement caveats

- The current Room backup-run start time begins when preview is created, so it
  includes time spent reviewing the summary and is not a transfer-only metric.
- Wi-Fi PHY rate is not equivalent to application or internet throughput.
- This single small sample cannot establish expected performance for many small
  files, large videos, retries, or different network conditions.

Before comparing transfer strategies, add monotonic per-rsync duration and byte
instrumentation that does not log filenames or credentials.

## Build artifact measurements

The Phase 5 host build on 2026-08-24 produced an unsigned, R8-minified ARM64
release APK of 3,922,578 bytes. The corresponding unminified debug APK was
25,479,064 bytes.

The Phase 6 build on the same date produced an unsigned, R8-minified ARM64
release APK of 4,019,362 bytes and an unminified debug APK of 25,502,264 bytes.
These are reproducible artifact sizes, not runtime or transfer-performance
claims.

The Phase 7 build on 2026-08-25 produced an unsigned, R8-minified ARM64 release
APK of 4,080,343 bytes and an unminified debug APK of 25,691,940 bytes. The
increase contains the complete Home, folder-management, Settings, English, and
Italian UI slice.

The Phase 9 build on 2026-08-31 produced an unsigned, R8-minified and
resource-shrunk ARM64 release APK of 4,415,663 bytes and an unminified debug APK
of 27,299,112 bytes. The increase includes Phase 8 durable execution plus the
Phase 9 metadata snapshot planner and its recovery path.

## Phase 9 local-only device measurements

Measured on a Xiaomi Mi 9T Pro running Android API 33. These checks used only
app-private/test-specific local files; they did not connect to a Storage Box.

| Measurement | Observed value |
| --- | ---: |
| Debug cold activity start (`am start -W`, TotalTime) | 918 ms |
| Debug idle process total PSS | 104,230 KiB |
| Scan and stage 1,000 new local files | 105 ms |
| Scan and compare 1,000 unchanged local files | 119 ms |
| Metadata sidecar for 1,000 files | 41,644 bytes |
| Local rsync of 100 × 1 KiB files | 58 ms |
| Local no-op rsync repeat | 56 ms |

Startup and memory are debug-build observations and include framework/UI costs;
they are not release-build targets. Discovery and rsync figures are single-run
bounded diagnostics, not throughput guarantees. The persistent performance
instrumentation test records the same local-only fields without logging file
names.

## Next experiments

1. Capture internal planning, rsync startup, transfer, cancellation, and resume
   timings separately.
2. Build reproducible, non-destructive datasets in the explicitly designated
   `Matteo/` development root for many-small-file and large-file cases.
3. Compare the current sequential baseline with at most two bounded independent
   streams, first across mappings and only then across deterministic shards.
4. Verify cancellation, retry, checkpoint atomicity, and unchanged no-deletion
   semantics before retaining any parallel mode.

Do not run destructive performance tests against any real user destination.
`Matteo/` is a development test root for this environment, not a product
default.
