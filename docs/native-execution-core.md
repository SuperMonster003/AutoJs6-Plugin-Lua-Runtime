# Native execution core checkpoint

Status: **SOURCE ONLY / NOT COMPILED / NOT RUN**.

The R3 native core is a blocking, process-local seam. It is not connected to
the production Binder service at this checkpoint. Provider discovery and the
native build remain default-off, the protocol AARs are not staged, and PUC Lua
has not been vendored.

## Ownership boundary

`NativeLuaRuntime.execute()` receives only:

- a defensive snapshot of already bounded UTF-8 source bytes
- a logical source label
- a Lua allocator limit
- an execution timeout
- a non-blocking cancellation probe

It does not receive an Android `Context`, file descriptor, Binder object,
session controller, output callback, capability broker, or arbitrary Java
object. The caller owns the serial worker and terminal race. The source-only
runner adapter accepts only `nil` and the host's empty-map representation of
"no arguments"; it rejects every non-empty argument value until explicit Lua
argument binding is implemented. The adapter is not injected into the service.

## Per-call native lifecycle

Each JNI call:

1. validates native hard ceilings and polls cancellation
2. creates one `lua_State` with the bounded allocator
3. opens only base, math, string, table, and UTF-8 libraries
4. removes `dofile`, `load`, `loadfile`, `pcall`, `xpcall`, `getmetatable`,
   `setmetatable`, `print`, `warn`, and `string.dump`
5. loads the source through `luaL_loadbufferx(..., "t")`
6. installs a count hook for cancellation and a monotonic deadline
7. executes through `lua_pcall`
8. admits zero or one scalar result
9. closes the state through deterministic RAII on every native return path

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
is presented as the protocol `LuaValue` until the unconnected runner adapter
performs that explicit scalar mapping.

## Known limits before enablement

- There is no stdout/stderr implementation or output-credit integration.
- There is no module loader, host capability broker, argument mapping, or
  table/array/map result mapping.
- There is no coroutine library in the source-only MVP.
- Lua hooks cannot preempt source parsing, time spent inside one long native
  C-library operation, or native heap teardown. The bridge polls immediately
  before and after loading and protected execution, while the allocator still
  bounds Lua-owned memory. Metatable removal prevents user finalizers from
  making teardown infinite, but a process-level cleanup watchdog remains an
  enablement gate.
- The cancellation probe executes synchronously on the Lua worker thread and
  must remain non-blocking.
- Native crashes, Android process rebuild, ABI packaging, and 16 KiB alignment
  remain deferred Android gates.

`NativeLuaRuntimeBoundaryTest` records the defensive snapshot, UTF-8, scalar
mapping, and result-limit expectations. These tests have not been run.
