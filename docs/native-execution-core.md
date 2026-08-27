# Native execution core checkpoint

Status: **COMPILED, PACKAGED, DEVICE-EXECUTED, AND MANDATORY**.

The native core is a blocking, process-local seam. Immutable protocol AARs and
PUC Lua 5.4.8 are staged, and every app variant compiles and packages both
admitted ABIs. The production Binder service is wired directly to the native
runner. `providerDebug` and `providerRelease` expose the production services;
the `nativeTestDebug` and `faultTestDebug` manifests physically remove those
services while retaining the native core for isolated instrumentation.

## Ownership boundary

`NativeLuaRuntime.execute()` receives only:

- a defensive snapshot of already bounded UTF-8 source bytes
- a logical source label
- a private, versioned snapshot of the already validated V1 argument tree
- a Lua allocator limit
- an execution timeout
- a non-blocking cancellation probe
- a synchronous, bounded stdout/stderr emitter owned by the session controller
- a process-local, fixed-shape `device.info` adapter; the native side cannot
  choose a capability name or pass arbitrary arguments
- a process-local `module.snapshot.v1` adapter that accepts only one flat ASCII
  module name and returns at most one frozen 64 KiB UTF-8 text snapshot

It does not receive an Android `Context`, file descriptor, Binder object,
session controller, Binder interface, Android `Context`, or arbitrary Java
object. The caller owns the serial worker and terminal race. The mandatory
runner validates the protocol value again, freezes it into a bounded
process-private blob, and exposes the decoded execution-local value as
`require("autojs").arguments`. Constructing the adapter does not load JNI; the
library is loaded only after an admitted native execution reaches the native
boundary. Isolated test variants remove Provider services through manifest
overlays instead of runtime flags.

## Per-call native lifecycle

Each JNI call:

1. validates native hard ceilings and polls cancellation
2. creates one `lua_State` with the bounded allocator
3. opens only base, coroutine, math, string, table, and UTF-8 libraries
4. removes `dofile`, `load`, `loadfile`, `pcall`, `xpcall`, `getmetatable`,
   `setmetatable`, and `string.dump`
5. installs controlled global `print`/`warn` output bridges, the reviewed
   explicit-seed PRNG wrapper, and a restricted `require` exposing `autojs`
   plus execution-local frozen modules loaded only through the fixed module
   snapshot capability
6. loads the main source and every admitted module through
   `luaL_loadbufferx(..., "t")`
7. installs a count hook for cancellation and a monotonic deadline
8. executes through `lua_pcall`
9. admits zero or one scalar result
10. closes the state through deterministic RAII on every native return path

The allocator rejects a resize before `realloc`, permanently denies new
allocations if Lua ever reports an old size larger than the admitted live-byte
count, and still honors frees so deterministic close can drain existing blocks.
The success path explicitly closes the state and checks that invariant again
before exposing the result.

The cancellation reason is stored outside the Lua stack. Lua-level `pcall` and
`xpcall` are unavailable so a script cannot repeatedly swallow the hook error;
the retained reason also overrides a later Lua result, including the
`(false, error)` returned by `coroutine.resume`. R5 re-evaluated R4's reviewed
rejection and reached the same decision. The conditions for
reopening that decision are recorded in
[pcall-boundary-decision.md](pcall-boundary-decision.md).

Lua-level metatable discovery and mutation are also unavailable. This prevents
an untrusted chunk from installing an infinite `__gc` or `__close` handler that
would otherwise execute during teardown after the protected call and could make
`lua_close` non-terminating.

## Controlled coroutine boundary

The inventory admits only PUC Lua 5.4.8 `lcorolib.c` and opens it explicitly;
`linit.c` remains excluded. The pinned `lua_newthread` copies the active hook,
count, and mask from its parent and copies `LUA_EXTRASPACE` from the main state.
Because JNI stores the private `ExecutionControl*` in that main-state slot
before untrusted execution, every child and nested child sees the same deadline,
cancellation probe, and retained terminal reason.

All coroutine stacks share the owning state's allocator. Bytes retained across
yield/resume remain charged, and state close drains the main and child stacks
through the same budget. A sticky allocator-limit marker prevents stock
`coroutine.resume` from converting a child OOM into apparent script success;
JNI closes the state, checks exact allocator release, and reports
`MEMORY_LIMIT`. The complete rationale, source-level inheritance proof, test
matrix, and limitations are recorded in
[coroutine-control-boundary.md](coroutine-control-boundary.md).

## Deliberately narrow V1 result boundary

The native bridge currently admits only:

- nil
- Boolean
- signed 64-bit integer
- finite double
- a valid UTF-8 string no larger than 64 KiB

Multiple returns, tables, functions, threads, userdata, light userdata,
non-finite numbers, invalid UTF-8, and oversized strings fail closed. No result
is presented as the protocol `LuaValue` until the runner adapter performs that
explicit scalar mapping. The host-coordinated V2 proposal is design-only and is
recorded in [result-model-v2.md](result-model-v2.md); it does not alter this V1
boundary or the frozen protocol inputs.

## Bounded argument boundary

The provider maps every admitted V1 argument kind: top-level nil, Boolean,
signed 64-bit integer, finite double, UTF-8 text, bytes, dense arrays, and
string-key maps. Arrays use Lua's 1-based integer keys. Text, bytes, and map
keys are installed with length-aware Lua APIs, so embedded null bytes cannot
truncate data or change a key. Empty arrays and maps both become empty Lua
tables, and text/bytes both become Lua strings; those distinctions are not
recoverable inside Lua.

The public protocol validator first enforces depth, node, data, per-container,
per-item, and map-key quotas and rejects nil inside containers. Kotlin then
creates a defensive, versioned private snapshot. Native code independently
checks its magic/version, every length and count, the same quotas, finite
floating-point values, dense-container nil rules, and exact end-of-buffer before
publishing the module. Native mapping occurs inside a protected call and every
Lua table allocation remains charged to the execution allocator. The snapshot
does not contain Java or Android objects and cannot be written back to the host.

## Reviewed utility and console surface

The safe-library inventory continues to expose PUC Lua 5.4.8 `string.format`
and the math PRNG without opening the `os` library. `math.random` remains
pseudo-random and carries no cryptographic guarantee. The controlled
`math.randomseed(seed1[, seed2])` wrapper requires one or two explicit integer
seeds; it rejects the upstream no-argument branch so the seeds derived from the
wall clock and `lua_State` address are not returned to an untrusted script.

`require("autojs").now()` accepts no arguments and returns signed 64-bit Unix
epoch milliseconds from the process wall clock. It polls execution control and
makes no Host/Binder call. It is a timestamp API, not a monotonic duration API;
runtime deadlines continue to use a private monotonic clock. The complete
review and rejected OS/process surface are recorded in
[safe-standard-library-subset.md](safe-standard-library-subset.md).

`autojs.console.info` is an exact stdout alias of `console.log`, while
`autojs.console.warn` is an exact stderr alias of `console.error`. Together
with global `print`/`warn`, all six entry points still use only the frozen
`LuaOutputStream.STDOUT`/`STDERR` wire values. No protocol enum or capability
was added. See [console-levels-decision.md](console-levels-decision.md).

`require("autojs").ui.toast(text)` is the fixed `ui.toast.v1` Host-capability
bridge. It accepts exactly one non-empty strict UTF-8 string of at most 1,024
bytes, sends the closed request `{text=string}`, and accepts only the closed
acknowledgement `{accepted=true}`. One execution may attempt four toast calls;
the shared main/coroutine counter is charged before the sole JNI/Binder
dispatch and is never refunded or retried. Success returns no Lua values and
means accepted for Host enqueueing, not visibly displayed. The Provider
contract and coordinated Host delivery evidence are recorded in
[ui-toast-v1.md](ui-toast-v1.md).

`require("autojs").storage` is the negotiated `storage.kv.v1` bridge for
file-backed scripts. Its fixed `get`, `put`, `remove`, and `clear` functions use
closed `LuaValue` maps, a 252 KiB canonical value ceiling, 64 operations and 32
mutations per execution, separate 1 MiB read/write ledgers, and no retry. Native
code converts scalar or raw dense/map tables without metamethods; Provider
Kotlin and Host Kotlin independently repeat key, value, shape, and quota checks.
The stable principal and app-private persistence remain entirely Host-owned.
See [storage-kv-v1.md](storage-kv-v1.md).

## Known limits

- The admitted modules are the built-in `autojs` module and flat ASCII names
  resolved by `module.snapshot.v1`. There is no general module loader: no path,
  URI, package search, binary chunk, dynamic C module, or Java bridge crosses
  the native boundary. Each text snapshot is bounded to 64 KiB, loaded in text
  mode, cached per execution (including `false` and nil-as-true), and a loading
  cycle fails closed.
- Dotted names, aggregate module-source quotas, and cache-hit metrics are
  design-only in [module-snapshot-v2.md](module-snapshot-v2.md). The exact
  `module.snapshot.v2` capability is neither implemented nor advertised, and a
  future V2 failure must never be retried under V1.
- `module.snapshot.v1` sends exactly `{name=string}` and accepts only
  `{found=false}` or `{found=true, source=bytes, sha256=bytes}`. Kotlin repeats
  the UTF-8 and byte-limit validation and recomputes the 32-byte SHA-256 before
  JNI; Binder dispatch is never retried.
- `device.info()` always emits one empty-map request for the exact
  `device.info` capability and accepts only the six-field bounded map documented
  by M3.3. Binder dispatch is never retried. Its worker-side wait polls cancel
  and deadline, and callback UID, execution ID, call ID, terminal uniqueness,
  and zero descriptors are validated before the response reaches JNI.
- `storage.kv.v1` is advertised but requested only for a stable file-backed
  script principal selected by the Host. In-memory sources receive no storage
  grant. Older peers fail as `HOST_CAPABILITY`; there is no Provider-local
  persistence, namespace selection, alternate shape, or retry.
- `ui.toast.v1` is advertised by Provider metadata, but it is available only
  when the Host/provider capability intersection grants it. A Host without the
  matching dispatcher produces deterministic `DENIED`/`HOST_CAPABILITY`; there
  is no Provider-process Android toast fallback. The coordinated R5 Host now
  implements the dispatcher and API 37 real-Provider smoke covers its UI lane;
  fake-invoker tests retain exact validation/quota/no-retry coverage.
- Console output is synchronous and must be accepted by the session's existing
  sequence, credit, chunk, and total-byte limits. Global `print` plus
  `console.log`/`console.info` route to controlled stdout; global `warn` plus
  `console.error`/`console.warn` route to controlled stderr. No unrestricted
  Lua output fallback exists.
- Coroutines are cooperative and execution-local. They create no Java thread,
  cannot outlive the owning state, and remain unsupported V1 result values.
- Lua hooks cannot preempt source parsing, time spent inside one long native
  C-library operation, or native heap teardown. The bridge polls immediately
  before and after loading and protected execution, while the allocator still
  bounds Lua-owned memory. Metatable removal prevents user finalizers from
  making teardown infinite. A token-bound process-level cleanup watchdog now
  fail-stops the dedicated process after the request deadline or stop request
  plus a two-second grace. Focused debug fault evidence now covers native
  crash/wedge, a source pipe whose writer remains open, kill/rebind/new-PID
  recovery, independent callback and broker process death, and exact return to
  a warmed `/proc/self/fd` baseline after success, source failure, cancellation,
  and peer-death batches. Broader release validation remains deferred.
- The cancellation probe executes synchronously on the Lua worker thread and
  must remain non-blocking.
- Native crashes and Android process rebuild remain deferred Android gates. ABI
  packaging plus 16 KiB ELF/ZIP alignment pass the debug artifact gate.

`NativeLuaRuntimeBoundaryTest` records defensive source and argument snapshots,
UTF-8, scalar mapping, the exact two-stream console wire boundary, and
result-limit expectations, plus every registered capability's grant/denial
boundary and the closed toast request/acknowledgement. Focused native
instrumentation additionally covers
`autojs.now()`, `string.format`, explicit PRNG seeds, zero-seed rejection, and
the four `autojs.console` names, fixed toast dispatch, toast input/quota
rejection before Host invocation, plus coroutine deadline, cancellation,
yield/resume allocator accounting, and OOM recovery.
The complete 15-test class passed on the API 37, 16 KiB x86_64 emulator for
implementation revision `fc964d448c85f950c27667e7891fbb7d337fe73e`; exact
artifact identities and the receipt are in
[coroutine-control-boundary.md](coroutine-control-boundary.md).
After the toast bridge was added, the complete class passed 17/17 on the same
emulator class for implementation revision
`e26fbc1356dc9e98a0fdf11e4ab732f06079eac4`, with Provider discovery and the
fault harness disabled. The historical 50/50 JVM result,
fixed-shape/quota/UTF-8/no-retry matrix, artifact identities, and original Host
follow-up boundary are archived in [ui-toast-v1.md](ui-toast-v1.md).
After `storage.kv.v1` was added, the repository JVM inventory became 61 tests
and the complete native class passed 23/23 on API 37 `emulator-5560`. The
coordinated Host tests passed 35/35 JVM, 3/3 persistent-storage instrumentation,
and 1/1 real Host + official Provider smoke; exact storage and Toast evidence is
recorded in [storage-kv-v1.md](storage-kv-v1.md) and
[ui-toast-v1.md](ui-toast-v1.md).
The repository JVM gate also covers watchdog token, stop, finish, and
scheduler-failure races.
Focused Android tests cover native execution; the official Provider smoke is
the cross-process happy-path gate.
