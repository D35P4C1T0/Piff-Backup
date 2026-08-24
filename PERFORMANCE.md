# Performance

No representative performance measurements exist yet. Phase 1 measures only
native process startup duration on the target device and records it in the
local diagnostic output. Transfer and large-dataset measurements begin in the
later performance phase and will use generated data in disposable locations,
never the real `Bianca/` folder.

Runtime results will not be added here until measured on identified hardware
with a documented dataset and build variant.

## Build artifact measurements

The Phase 5 host build on 2026-08-24 produced an unsigned, R8-minified ARM64
release APK of 3,922,578 bytes. This is a reproducible build-artifact size, not
a runtime or transfer-performance claim. The corresponding unminified debug APK
was 25,479,064 bytes because it retains the complete onboarding cryptography
dependency graph for debugging.

The Phase 6 build on the same date produced an unsigned, R8-minified ARM64
release APK of 4,019,362 bytes and an unminified debug APK of 25,502,264 bytes.
No live adoption timing or throughput was measured; those results still require
a documented disposable dataset and must never use the real `Bianca/` folder.
