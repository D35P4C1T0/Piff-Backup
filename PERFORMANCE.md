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
