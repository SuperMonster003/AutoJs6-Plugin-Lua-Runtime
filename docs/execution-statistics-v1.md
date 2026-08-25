# Lua per-execution statistics protocol evolution

Status: **需宿主协议演进 — FIELD DESIGN COMPLETE, NOT IMPLEMENTED**

Design date: 2026-08-25

Protocol input reviewed: frozen AutoJs6 revision
`3b7378758c5a4f68e8680a78cf2c541c23628489`, Lua protocol 1.0.

Read-only compatibility audit: the Lua protocol and tagged-wire sources at the
adjacent Host checkout revision
`4a9718d63923834c9a99fd70e0cd58c898e138f6` are unchanged from that frozen
revision. Unrelated dirty Host files were not modified.

## Decision

The current Provider cannot expose peak Lua memory, instruction-hook invocation
count, accepted output bytes, and load/execute/teardown timings as structured
per-execution data without a coordinated Host protocol change.

The Binder callback already transports an opaque metadata byte array, and the
tagged-wire format can evolve through negotiated fields. However, the frozen
`lua-runtime-api.aar` has no statistics model, schema, codec, validation, or
terminal property through which the Provider can create data that the current
Host can consume. `getRuntimeInfo()` is Provider-scoped and has no execution
identity. It is not a safe per-execution side channel.

This record therefore closes the R4 design gate only. The Provider continues to
advertise protocol 1.0, does not advertise `execution.stats.v1`, emits the exact
V1 terminal schemas, and collects no otherwise-unobservable statistics. No
runtime or end-to-end statistics implementation is claimed.

## Frozen V1 boundary

The staged AAR exposes these terminal models and wire fields:

| Terminal callback | Frozen model fields | Frozen schema tags |
|---|---|---|
| `onCompleted` | `requestId`, `value`, `elapsedMillis` | `SCHEMA_RESULT`: 1, 2, 3 |
| `onFailed` | `requestId`, `code`, `phase`, `message`, `retryDisposition` | `SCHEMA_ERROR`: 1 through 5 |
| `onCancelled` | `requestId`, `reason`, `elapsedMillis` | `SCHEMA_CANCELLATION`: 1, 2, 3 |

Success and cancellation contain only a coarse, end-to-end millisecond value;
failure has no elapsed-time field. None of the three models has an extension
map or a statistics property. The callback AIDL has no independent statistics
event: it contains only `onStarted`, `onOutput`, `onCompleted`, `onFailed`, and
`onCancelled`.

`ILuaRuntimeProvider.getRuntimeInfo()` takes no request ID and returns a static
`LuaRuntimeInfo`. That model describes protocol range, Provider identity,
runtime identity, ABIs, capabilities, and limits. It cannot identify a completed
execution, distinguish simultaneous retained sessions, define read-once versus
cached behavior, or make terminal publication atomic with a later lookup. The
current process-wide concurrency limit of one does not repair those identity and
lifetime defects and must not become an implicit protocol guarantee.

The tagged-wire reader deliberately ignores an unknown *optional* field and
rejects an unknown field marked `requiredForReader`. This makes a coordinated
extension possible, but it does not create a JVM/API carrier in the frozen AAR.
Manually writing an unknown optional field from this Provider would produce
bytes that the current Host silently discards, would fork the canonical codec,
and would not satisfy structured observability.

## Rejected V1 encodings

- Replacing `LuaExecutionResult.value` with a statistics map would change the
  script's return value and still could not cover failure or cancellation.
- Sending statistics through stdout/stderr would consume output credits and
  byte quota, interleave diagnostics with script output, and expose no terminal
  atomicity.
- Appending text to `LuaExecutionError.message` would be unstructured, apply
  only to failures, and consume a bounded user-facing diagnostic field.
- A result `ParcelFileDescriptor` would violate the current zero-descriptor Lua
  result contract and is unavailable on failure and cancellation callbacks.
- Persisting “last statistics” and returning them from `getRuntimeInfo()` would
  race the next execution and cached metadata reads, and it has no request-ID,
  retention, acknowledgement, or authorization semantics.
- Adding an unsolicited callback or trailing Binder argument would change the
  frozen AIDL. The generated AIDL contract also does not claim strict trailing
  Parcel rejection, so trailing-data tricks are not an extension mechanism.

## Required protocol field inventory

The Host protocol owner should add a nested document provisionally named
`SCHEMA_EXECUTION_STATISTICS`. Its final numeric schema ID must be allocated in
the canonical Host source; this repository must not invent a competing ID.

The V1 statistics document is fixed-size and contains only counters and
monotonic durations:

| Tag | Field | Wire type | Validation and meaning |
|---:|---|---|---|
| 1 | `validityFlags` | `int32` | Closed bit set described below; no unknown V1 bits. |
| 2 | `peakLuaAllocatorBytes` | `int64` | Non-negative peak live bytes observed by the bounded Lua allocator from `lua_newstate` through `lua_close`; includes all admitted coroutines, excludes JVM/native stack and source buffers. |
| 3 | `instructionHookInvocations` | `int64` | Non-negative number of entries into the count hook across the main state and child/nested coroutines; it is not an estimate of exact instructions and excludes explicit control polls in built-ins. |
| 4 | `acceptedOutputUtf8Bytes` | `int64` | Non-negative UTF-8 payload bytes accepted by the session's validation, credit, sequence, and total-byte gate across stdout and stderr; excludes wire framing and rejected chunks. |
| 5 | `loadNanos` | `int64` | Non-negative monotonic duration for Lua state creation, safe-library opening, argument/module setup, and text-only `luaL_loadbufferx`; valid on a failed load when its validity bit is set. |
| 6 | `executeNanos` | `int64` | Non-negative monotonic duration from hook arming through protected chunk execution, terminal-status classification, and V1 result boxing; synchronous output and Host capability calls are included. |
| 7 | `teardownNanos` | `int64` | Non-negative monotonic duration of the one owning `lua_close`; zero is meaningful only when its validity bit is set. |

All seven fields are singular and required inside
`SCHEMA_EXECUTION_STATISTICS`. Every metric is encoded as a signed value because
the wire API has signed `int32`/`int64` primitives; validation rejects negative
values. Peak allocator bytes must not exceed the request's admitted memory
limit, and accepted output bytes must not exceed its admitted output limit.
Durations use the process monotonic clock, never wall time. The existing
`elapsedMillis` remains the created-to-terminal duration and can include queue,
source verification, and Binder work, so it is not required to equal the three
native phase durations.

`validityFlags` prevents a pre-native failure from being confused with a real
zero measurement:

| Bit | Constant | Meaning |
|---:|---|---|
| `0x01` | `PEAK_LUA_ALLOCATOR_BYTES_VALID` | The allocator counter was initialized and remained internally consistent. |
| `0x02` | `INSTRUCTION_HOOK_INVOCATIONS_VALID` | The shared hook counter was initialized. |
| `0x04` | `ACCEPTED_OUTPUT_UTF8_BYTES_VALID` | The session output counter was snapshotted for this terminal. |
| `0x08` | `LOAD_NANOS_VALID` | The load timer was entered and stopped, including an ordinary load failure. |
| `0x10` | `EXECUTE_NANOS_VALID` | The execute timer was entered and stopped, including cancellation, deadline, OOM, Host-call failure, or runtime failure. |
| `0x20` | `TEARDOWN_NANOS_VALID` | `lua_close` returned and its duration was captured. |

An invalid field is encoded as zero and its bit is clear. A normal terminal
before native entry therefore still carries a valid output-byte count but has
zero native metrics with their bits clear. A native process crash or watchdog
fail-stop cannot deliver a terminal callback; it belongs to the separate crash
diagnostic record, not to fabricated execution statistics.

The protocol models must gain an optional property so a new reader can still
decode a protocol-1.0 terminal:

```text
LuaExecutionResult.statistics: LuaExecutionStatistics?
LuaExecutionError.statistics: LuaExecutionStatistics?
LuaExecutionCancellation.statistics: LuaExecutionStatistics?
```

The nested document uses the next free terminal tag in each existing schema:

| Parent schema | Statistics tag |
|---|---:|
| `SCHEMA_RESULT` | 4 |
| `SCHEMA_ERROR` | 6 |
| `SCHEMA_CANCELLATION` | 4 |

When statistics were negotiated, the parent document field is marked
`requiredForReader = true`. When they were not negotiated, the field must be
absent and the encoded V1 terminal must remain byte-for-byte compatible with
the frozen codec. A future structured `SCHEMA_RESULT_V2` must allocate its own
statistics tag while reusing the same nested statistics schema; it must not
wrap or reinterpret a script value.

## Negotiation and compatibility

The feature requires both `LuaProtocolVersion(1, 1)` and the exact capability
`execution.stats.v1`. The Provider may advertise that protocol maximum and
capability only after all Host and Provider work below is present. A Host that
wants statistics includes the capability in `requiredCapabilities`; session
policy then requires statistics on every success, failure, or cancellation
terminal belonging to that negotiated execution.

| Host | Provider | Selected request | Required outcome |
|---|---|---|---|
| V1-only | statistics-capable | 1.0 without capability | Exact frozen terminal bytes; no statistics field. |
| statistics-capable | V1-only | negotiated 1.0 | Execute normally without statistics; Host must not request the capability. |
| statistics-capable | statistics-capable | 1.1 plus capability | Every delivered terminal contains one validated statistics document. |
| statistics-capable | statistics-capable | 1.1 without capability | No statistics field; other 1.1 features may operate independently. |
| any | Provider emits statistics without negotiation | any | Protocol violation; no content sniffing or retry of an executed script. |

The field is required-for-reader only in the negotiated branch. This makes an
incorrect emission fail loudly at an old reader instead of being mistaken for
successful observability. There is no downgrade retry after execution.

`getRuntimeInfo()` remains the discovery location for the static protocol range
and `execution.stats.v1` capability only. It never returns one execution's
counter values.

## Host-side changes required

- Add protocol 1.1, the `execution.stats.v1` capability constant,
  `LuaExecutionStatistics`, its validity constants, the Host-allocated schema
  ID, and the three parent tags to canonical `lua-runtime-api` source.
- Extend all three terminal codecs and validations, keeping the 1.0 encoding
  byte-for-byte stable when `statistics == null`.
- Make `LuaExecutionSessionPolicy` require exactly one statistics document on
  every negotiated terminal and reject one on an unnegotiated terminal.
- Preserve statistics in the engine outcome/diagnostic surface without logging
  script value, output text, arguments, source name, source hash, or error text.
- Add golden bytes, round trips, negative/overflow/unknown-flag tests, malformed
  nested-document tests, and the full negotiated/unnegotiated compatibility
  matrix.
- Rebuild and restage the three immutable protocol AARs from one clean,
  committed Host revision through the existing intake workflow.

## Provider-side changes required

- Consume the coordinated AARs, advertise protocol 1.1 and
  `execution.stats.v1` only in the complete implementation, and preserve the
  selected request version/capability through terminal encoding.
- Extend `MemoryBudget` with a checked peak, and extend the execution control
  shared through `LUA_EXTRASPACE` with one non-wrapping hook counter.
- Snapshot the existing session-owned accepted-output byte counter atomically
  with terminal claim.
- Refactor native execution to one measured finalization path so ordinary
  success, syntax/runtime error, OOM, cancellation, deadline, and Host-call
  failure all close the state before publishing statistics. An allocator
  accounting failure clears the peak-valid bit rather than presenting an
  untrusted value.
- Attach the same immutable statistics snapshot to success, failure, and
  cancellation; pre-native queue/source failures use validity bits rather than
  invented measurements.
- Add JVM assertions for all terminal branches and Android-native assertions
  for allocator peak, main/child hook counts, UTF-8 output bytes, phase timing,
  partial failure masks, and exact post-`lua_close` allocator recovery.

## Current JVM guard

`LuaExecutionStatisticsProtocolBoundaryTest` asserts the staged protocol is
still 1.0, that the frozen result/error/cancellation documents contain only
their published tags, that their JVM models expose no statistics accessor, and
that `getRuntimeInfo()` and the callback AIDL provide no execution-scoped
statistics method. It is an executable reason for this “需宿主协议演进” outcome.

When the coordinated Host protocol lands, that boundary test must be replaced
in the same change by codec and lifecycle assertions for the field inventory
above. Merely deleting it while retaining the frozen AAR is a regression.

## Privacy and non-goals

Statistics contain no script source or hash, source name, arguments, result
value, output text, error message, module name/body, capability argument/result,
wall-clock timestamp, device identity, or arbitrary labels. They are not a
telemetry upload policy, persistent history, profiler, instruction trace,
allocation trace, or crash record. Host storage, retention, UI, and export
remain separately reviewed concerns.
