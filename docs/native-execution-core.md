# Native execution core checkpoint

Status: **COMPILED, PACKAGED, AND DEVICE-EXECUTED / PROVIDER DEFAULT-OFF**.

The R3 native core is a blocking, process-local seam. Immutable protocol AARs
and PUC Lua 5.4.8 are staged, and native-enabled/provider-disabled debug builds
compile and package both admitted ABIs. The Binder service conditionally
selects the native runner only when `LUA_NATIVE_ENABLED` is true; provider
discovery remains independently default-off. Focused device evidence covers
the native boundary, process recovery, and an explicitly enabled production
Provider pilot; this does not enable Provider discovery in ordinary builds.

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

It does not receive an Android `Context`, file descriptor, Binder object,
session controller, Binder interface, Android `Context`, or arbitrary Java
object. The caller owns the serial worker and terminal race. The default-off
runner validates the protocol value again, freezes it into a bounded
process-private blob, and exposes the decoded execution-local value as
`require("autojs").arguments`. Selecting the adapter does not load JNI; the
library is loaded only after an admitted native execution reaches the native
boundary; the provider switch may remain false for isolated native tests.

## Per-call native lifecycle

Each JNI call:

1. validates native hard ceilings and polls cancellation
2. creates one `lua_State` with the bounded allocator
3. opens only base, math, string, table, and UTF-8 libraries
4. removes `dofile`, `load`, `loadfile`, `pcall`, `xpcall`, `getmetatable`,
   `setmetatable`, `print`, `warn`, and `string.dump`
5. installs a restricted `require("autojs")` module exposing controlled console
   calls, the bounded argument snapshot, and the fixed `device.info()` call
6. loads the source through `luaL_loadbufferx(..., "t")`
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
the retained reason also overrides a later Lua result. The coroutine library is
excluded until hook inheritance and interruption behavior have compiled native
conformance evidence.

Lua-level metatable discovery and mutation are also unavailable. This prevents
an untrusted chunk from installing an infinite `__gc` or `__close` handler that
would otherwise execute during teardown after the protected call and could make
`lua_close` non-terminating.

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
explicit scalar mapping.

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

## Known limits before enablement

- The only admitted module is the built-in `autojs` module. It contains
  controlled console calls, the execution-local argument snapshot, and one
  fixed-shape `device.info()` bridge. There is no general module loader, general
  host capability broker, Java bridge, or table/array/map execution result
  mapping.
- `device.info()` always emits one empty-map request for the exact
  `device.info` capability and accepts only the six-field bounded map documented
  by M3.3. Binder dispatch is never retried. Its worker-side wait polls cancel
  and deadline, and callback UID, execution ID, call ID, terminal uniqueness,
  and zero descriptors are validated before the response reaches JNI.
- Console output is synchronous and must be accepted by the session's existing
  sequence, credit, chunk, and total-byte limits; it never falls back to
  unrestricted Lua `print` or `warn`.
- There is no coroutine library in the source-only MVP.
- Lua hooks cannot preempt source parsing, time spent inside one long native
  C-library operation, or native heap teardown. The bridge polls immediately
  before and after loading and protected execution, while the allocator still
  bounds Lua-owned memory. Metatable removal prevents user finalizers from
  making teardown infinite. A token-bound process-level cleanup watchdog now
  fail-stops the dedicated process after the request deadline or stop request
  plus a two-second grace. Focused debug fault evidence covers kill, rebind,
  new PID, and recovery; broader release validation remains deferred.
- The cancellation probe executes synchronously on the Lua worker thread and
  must remain non-blocking.
- Native crashes and Android process rebuild remain deferred Android gates. ABI
  packaging plus 16 KiB ELF/ZIP alignment pass the debug artifact gate.

`NativeLuaRuntimeBoundaryTest` records defensive source and argument snapshots,
UTF-8, scalar mapping, console wire boundaries, and result-limit expectations.
The repository JVM gate also covers watchdog token, stop, finish, and
scheduler-failure races.
Focused Android tests cover native execution; the official Provider smoke is
the cross-process happy-path gate.
