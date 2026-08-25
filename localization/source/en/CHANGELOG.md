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
- Admitted only PUC Lua 5.4.8 `lcorolib.c`, preserving inherited deadline and
  cancellation hooks plus one shared allocator across coroutine yield/resume;
  caught child OOM now remains a sticky `MEMORY_LIMIT` failure.
- Added `console.info`/`console.warn` as stdout/stderr aliases without expanding
  the frozen two-stream wire enum.
- Specified a design-only, non-advertised `module.snapshot.v2` with dotted ASCII
  names, aggregate per-execution quotas, and cache metrics.
- Corrected the native instrumentation expectation for an unavailable Host
  module capability from a Lua runtime error to `HOST_CAPABILITY`.
- Added symmetric grant/deny JVM coverage for every registered Host capability;
  rejected grants remain bounded and map through `DENIED` to
  `HOST_CAPABILITY`.
- Added the Provider-side `ui.toast.v1` fixed bridge with a closed
  `{text=string}` / `{accepted=true}` shape, a 1,024-byte strict UTF-8 limit,
  four charged calls per execution, and a single never-retried Host dispatch;
  visible delivery remains a coordinated Host follow-up.
- Specified a design-only, non-advertised `storage.kv.v1` contract with stable
  script-principal isolation, bounded keys/values/operations, atomic Host-side
  persistence, explicit clear semantics, and no Provider mutation retries.
- Audited the frozen Lua terminal/runtime-info models, recorded per-execution
  statistics as requiring Host protocol evolution, specified the exact nested
  fields, validity mask, terminal tags, and protocol-1.1 capability negotiation,
  and added a JVM guard proving no V1 statistics carrier is being assumed.
- Added debug-only remote `/proc/self/fd` accounting and proved exact baseline
  recovery across batched success, digest-failure, cancellation, callback-death,
  and broker-death paths.
- Added hanging-pipe source fail-stop/rebind coverage plus an isolated Binder
  peer process for independent callback/broker death; both debug services remain
  physically excluded from release variants.
- Narrowed the release-candidate Gradle guard to actual APK/AAB artifact tasks,
  allowing unsigned release intermediates to be generated for the fault-harness
  exclusion audit while every publishable package remains guarded; the canonical
  audit now removes stale build outputs and forces fresh intermediates with
  `:app:clean` plus `--rerun-tasks`.

## 0.1.0-rc.1 — historical local candidate

- Built and signed the first Provider-enabled local candidate.
- Archived focused device, ABI, compatibility, and rollback evidence for that
  exact historical revision.
- No public tag or release was created.
