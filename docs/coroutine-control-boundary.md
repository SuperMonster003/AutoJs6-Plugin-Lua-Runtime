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

On 2026-08-25 the complete class was rebuilt from clean implementation revision
`fc964d448c85f950c27667e7891fbb7d337fe73e` with
`native=true`, `provider=false`, and `faultHarness=false`. The same canonical
invocation produced 49/49 JVM tests plus fresh x86_64, arm64-v8a, universal, and
Android-test APKs. `verify_debug_artifacts.ps1` confirmed versionCode 35, the
expected two ABI inventory, one signer, `provider=false`, `faultHarness=false`,
and 16 KiB ELF/ZIP alignment.

The coroutine device run installed only the x86_64 application and its test APK
on API 37 `emulator-5554`. The device reported ABI list
`x86_64,arm64-v8a`, `ro.kernel.qemu=1`, and page size 16,384 bytes.

| Artifact | versionCode | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| `app-x86_64-debug.apk` | 35 | 1,787,857 | `244345a1eb0512ca8cb018012614713d43eef3aab500d0086a4d1978199ccaac` |
| `app-debug-androidTest.apk` | Android test default 0 | 943,215 | `81dfc66b720b05fb8ded3cfbe34ef43c0a5b1a44172531c3f821ca00f8e12cdb` |

Both APKs had signer certificate SHA-256
`2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8`.
The complete `NativeLuaRuntimeInstrumentationTest` class passed 15/15,
including all four coroutine cases and the immediate post-OOM reuse proof. The
exact receipt was:

```text
COROUTINE_INSTRUMENTATION_PASS serial=emulator-5554 api=37 abis=x86_64,arm64-v8a pageSize=16384 revision=fc964d448c85f950c27667e7891fbb7d337fe73e appVersionCode=35 testVersionCode=0 tests=15 provider=false faultHarness=false appBytes=1787857 appSha256=244345a1eb0512ca8cb018012614713d43eef3aab500d0086a4d1978199ccaac testBytes=943215 testSha256=81dfc66b720b05fb8ded3cfbe34ef43c0a5b1a44172531c3f821ca00f8e12cdb signerSha256=2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8 deadline=pass cancel=pass yieldResumeAccounting=pass caughtOom=pass processReusable=pass
```

No physical device was installed, uninstalled, queried for mutation, or used as
evidence. The installed emulator application remains Provider-disabled and is
not a release candidate.
