# Controlled Lua coroutine boundary

Status: **IMPLEMENTED — PROVIDER DEFAULT-OFF**

Decision date: 2026-08-25

Scope: PUC Lua 5.4.8 `coroutine` support inside one bounded native execution.
This is a language-library addition only. It does not add a protocol capability,
advertise a Provider that ordinary builds keep disabled, create a Java thread,
or allow a coroutine to outlive its owning `lua_State`.

## Exact admitted inventory

The CMake inventory adds exactly one standard-library implementation file:

```text
src/lcorolib.c
```

The JNI bootstrap opens it explicitly with
`{LUA_COLIBNAME, luaopen_coroutine}`. It still does not call `luaL_openlibs`, and
`linit.c`, `ldblib.c`, `liolib.c`, `loslib.c`, and `loadlib.c` remain absent.
Consequently the addition does not admit the debug, filesystem, process, OS,
package-search, or dynamic C-module surfaces.

The exposed table is the stock PUC Lua 5.4.8 coroutine table: `create`, `resume`,
`running`, `status`, `wrap`, `yield`, `isyieldable`, and `close`. Lua-level
`pcall` and `xpcall` remain removed. Metatable discovery/mutation, binary chunk
creation, and unrestricted loaders remain removed as well.

## Why the execution hook reaches child coroutines

The vendored and tree-locked Lua 5.4.8 `lua_newthread` implementation performs
all of the following before returning a child state:

```c
L1->hookmask = L->hookmask;
L1->basehookcount = L->basehookcount;
L1->hook = L->hook;
resethookcount(L1);
memcpy(lua_getextraspace(L1), lua_getextraspace(g->mainthread), LUA_EXTRASPACE);
```

The provider stores its `ExecutionControl*` in the main state's
`LUA_EXTRASPACE` before opening libraries or loading untrusted source. It then
installs `execution_hook` with `LUA_MASKCOUNT` and a 10,000-instruction count
before calling the untrusted chunk. Any coroutine created by that chunk thus
receives both the active count hook and the same private control pointer.
Nested coroutines repeat the hook copy from their parent while continuing to
copy the same main-thread extraspace.

There is no hook installed during trusted library bootstrap, argument mapping,
or text parsing. No untrusted Lua executes in those phases. Native code polls
the same deadline/cancellation control immediately around them, and the
process-level cleanup watchdog remains the fail-stop boundary for time spent in
one non-preemptible native operation.

## Deadline and cancellation semantics

`execution_hook` polls the monotonic deadline and the non-blocking Java
cancellation probe through the shared `ExecutionControl`. It stores the first
terminal reason outside every Lua stack before raising a fixed Lua error.

Stock `coroutine.resume` is itself a protected boundary and converts a child
error to `(false, error)`. That does not make a control event catchable here:
after the outer trusted `lua_pcall` returns, JNI checks the retained
`TerminationReason` before inspecting Lua status or any result. A script that
returns the `resume` Boolean after a child deadline/cancel therefore still
receives `DEADLINE_EXCEEDED` or `CANCELLED`, never a successful scalar. If it
continues running, the main state's inherited count hook sees the already-set
reason and interrupts again.

`coroutine.wrap` propagates the Lua error instead of returning a Boolean, but
the same out-of-band terminal check remains authoritative. This does not reopen
Lua-level `pcall` or `xpcall`; the separate R4 rejection and reconsideration
matrix remains in force.

## Allocator ownership across resume and yield

Every coroutine belongs to the same Lua global state and therefore uses the
same `bounded_allocator` and `MemoryBudget` as the main thread. Allocations
retained while a child yields remain charged. Resuming does not create a second
budget, and `lua_close` releases the main thread and all child stacks through
that same allocator.

The budget now also retains a sticky `limit_exceeded` bit. This is necessary
because stock `coroutine.resume` can turn a child `LUA_ERRMEM` into
`(false, error)`. Once the allocator rejects a request for crossing the hard
limit, JNI refuses to box any later apparent result. It closes the complete Lua
state, requires `accounting_failed == false` and `used == 0`, and then reports
`MEMORY_LIMIT`. The script cannot convert a real quota breach into success by
examining or ignoring the `resume` result.

An underlying system `realloc` failure that did not cross the configured quota
continues to propagate through Lua's normal `LUA_ERRMEM` mapping. The sticky bit
does not loosen the existing hard limit or allocator mismatch fail-stop.

## Native Android conformance matrix

`NativeLuaRuntimeInstrumentationTest` contains four focused cases in addition
to the existing main-thread hook and allocator coverage:

- `coroutineInfiniteLoopHonoursInheritedDeadlineHook` enters an infinite loop
  only inside a child and requires `DEADLINE_EXCEEDED` even though `resume`
  receives the hook error;
- `coroutineCancellationCannotBeSwallowedByResume` defers cancellation until
  the first execution hook poll, then requires `CANCELLED` and proves the child
  hook called the probe;
- `coroutineYieldResumeRetainsAllocatorAccounting` retains six 32 KiB strings
  across six yield/resume boundaries and completes with exact teardown; and
- `coroutineOomCannotBecomeSuccessfulAndTheProcessRemainsReusable` catches the
  child OOM through `resume`, attempts to return success, still receives
  `MEMORY_LIMIT`, and immediately executes `return 7` in the same process.

The class continues to assert `native=true`, `provider=false`, disabled
production services, controlled V1 results, and all prior native boundaries.
Device evidence is artifact-specific and is recorded below only after an exact
APK pair has completed the whole class on an Android emulator.

## Remaining limits

- A coroutine is cooperative; it does not create parallel execution or a Java
  thread.
- The count hook cannot preempt one long C-library call. The bounded allocator,
  surrounding control polls, and process watchdog remain the containment
  layers for that interval.
- Coroutine objects remain unsupported V1 result values and cannot cross the
  JNI or frozen wire boundary.
- Coroutines cannot outlive the execution-local module cache, arguments,
  output bridge, Host bridge, deadline, allocator, or `lua_State`.
- `pcall`/`xpcall`, debug hooks, unrestricted loaders, OS/process APIs, I/O,
  package search, and dynamic native modules remain unavailable.

## Recorded device evidence

No device receipt is claimed by this implementation section. The exact clean
revision, APK identities, emulator/API/ABI, test count, and instrumentation
receipt are appended only after the committed implementation passes the full
native Android class.
