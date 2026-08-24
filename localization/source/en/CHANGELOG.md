# Changelog

All notable repository checkpoints are recorded here. This project has not
made a public release; candidate labels describe local validation artifacts,
not published availability.

## Unreleased

- Added exact logical accounting for incoming, duplicated, callback, and
  result `ParcelFileDescriptor` ownership.
- Made pre-start deadline expiry produce one deterministic `TIMEOUT` terminal
  in the queue phase.
- Consolidated the JVM test count in `verification.properties` and added the
  repository-owned offline gate.
- Hardened CI with pinned Android SDK caching and bounded retry loops.
- Added the reviewed English/Simplified Chinese generated documentation and
  Android-resource workflow; eight additional locale slots remain empty until
  real reviewed translations exist.
- Recorded the R4 decision to keep Lua-level `pcall`/`xpcall` unavailable and
  drafted the host-coordinated V2 structured/multiple-result model.
- Added zero-argument `autojs.now()`, retained the bounded `string.format` and
  pseudo-random surface, and required explicit integer seeds for
  `math.randomseed` to avoid returning the upstream state-address seed.
- Added `console.info`/`console.warn` as stdout/stderr aliases without expanding
  the frozen two-stream wire enum.
- Specified a design-only, non-advertised `module.snapshot.v2` with dotted ASCII
  names, aggregate per-execution quotas, and cache metrics.
- Corrected the native instrumentation expectation for an unavailable Host
  module capability from a Lua runtime error to `HOST_CAPABILITY`.

## 0.1.0-rc.1 — historical local candidate

- Built and signed the first Provider-enabled local candidate.
- Archived focused device, ABI, compatibility, and rollback evidence for that
  exact historical revision.
- No public tag or release was created.
