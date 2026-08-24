# Reviewed Lua utility subset

Status: **IMPLEMENTED WITHOUT OPENING `os`**

Decision date: 2026-08-24

## Outcome

R4 keeps the native library allowlist at base, math, string, table, and UTF-8.
It does not compile or open `loslib.c`, and the global `os` table remains
unavailable. The reviewed convenience surface is:

- the existing PUC Lua 5.4.8 `string.format` implementation;
- the existing pseudo-random `math.random` implementation;
- a controlled `math.randomseed(seed1[, seed2])` wrapper that requires explicit
  integer seeds; and
- the new zero-argument `require("autojs").now()` wall-clock function.

No Host capability, Binder call, protocol field, descriptor, Android object, or
provider capability is added by this decision. All four APIs execute inside the
existing bounded native invocation.

## Reviewed source and threat boundary

The immutable CMake inventory already admits `lstrlib.c` and `lmathlib.c`, so
`string.format`, `math.random`, and `math.randomseed` existed before this R4
review. It deliberately excludes `loslib.c`; therefore `os.time` cannot be
enabled accidentally through the normal library bootstrap. The JNI bootstrap
also opens libraries individually and rejects `luaL_openlibs`.

The pinned PUC Lua `math.randomseed()` no-argument branch derives two seeds from
the current time and the address of the active `lua_State`, then returns both
seeds. Returning the second value would reveal a native address component to an
untrusted script. The R4 wrapper captures the original PUC closure but admits
only one or two explicit Lua integers before calling it. Consequently:

- `math.randomseed(7)` and `math.randomseed(7, 11)` remain deterministic and
  return the two effective seeds;
- `math.randomseed()`, floating-point seeds, extra arguments, and non-integers
  fail as ordinary runtime errors; and
- the internally generated initial PRNG state is never returned to the script.

`math.random` is a pseudo-random generator, not a cryptographic entropy source.
It must not be used for keys, tokens, nonces, signatures, authorization, or any
other security decision. A future secure-random API would require an explicit
bounded byte-count contract and a separately reviewed Android/native entropy
source; it must not silently replace `math.random` semantics.

## Exact `autojs.now()` contract

`require("autojs").now()`:

- accepts exactly zero arguments;
- returns one signed 64-bit Lua integer;
- represents Unix epoch milliseconds from the Android process wall clock;
- polls the existing cancellation/deadline control immediately before reading
  the clock; and
- performs no Host call and has no timezone, locale, formatting, filesystem, or
  process side effect.

The value is a wall clock and may move forward or backward when the device clock
is corrected. It is therefore suitable for timestamps, but not for measuring
timeouts or elapsed durations. The runtime continues to use its private
monotonic clock for deadlines; no script-visible monotonic deadline primitive is
introduced here.

## `string.format` decision

`string.format` remains the unmodified PUC Lua 5.4.8 implementation. R4 does not
create a second formatting dialect or a Host formatter. Its output allocations
are charged to the execution's bounded Lua allocator, returned scalar strings
remain capped at 64 KiB, and console use remains subject to the separate output
chunk/credit/total limits. Like every admitted native C-library function, one
individual formatting call is not instruction-hook preemptible; the allocator
and process watchdog remain the containment layers for that interval.

## Rejected surface

The following remain unavailable:

- `os.time`, `os.date`, `os.clock`, `os.difftime`, and the complete `os` table;
- environment, shell, filesystem, locale, temporary-file, and process APIs such
  as `os.getenv`, `os.execute`, `os.remove`, `os.rename`, `os.setlocale`, and
  `os.tmpname`;
- a script-visible sleep API; and
- a cryptographic-randomness claim for `math.random`.

Opening only selected fields from `luaopen_os` is also rejected: it would admit
the full OS implementation into the binary inventory and create an unnecessary
negative allowlist. Any future calendar or timezone API should instead be a
small fixed-shape `autojs` API with its own limits and native tests.

## Evidence and regression gate

`NativeLuaRuntimeInstrumentationTest.reviewedTimeFormatAndRandomSubsetStaysNarrow`
executes through the real JNI boundary and asserts:

- `os == nil`;
- bounded `string.format` behavior;
- explicit deterministic seeding plus an in-range `math.random` result;
- an integer `autojs.now()` close to the Java wall clock;
- rejection of arguments to `autojs.now`; and
- rejection of zero-argument `math.randomseed()`.

The repository verifier pins the zero-argument clock contract, explicit-seed
wrapper, safe-library inventory, absent OS library, and test names. On
2026-08-24 the complete 11-test `NativeLuaRuntimeInstrumentationTest` class
passed on the 16 KiB x86_64 `emulator-5554` with native execution enabled and
Provider discovery disabled. A physical arm64 run remains separate evidence.
