# Lua-level `pcall` / `xpcall` boundary decision

Status: **R5 RE-EVALUATED — REJECTED AGAIN**

R4 decision date: 2026-08-24

R5 re-evaluation date: 2026-08-27

Scope: the untrusted script-visible base-library surface; internal native
protected calls remain required.

## R5 re-evaluation outcome

R5 keeps Lua-level `pcall` and `xpcall` unavailable. No candidate wrapper was
found that improves the language surface without weakening the process-control
boundary. In particular, wrapping the stock base functions after they return
is too late for `xpcall`: its untrusted message handler has already run while a
deadline, cancellation, control-probe failure, or allocator failure is active.
A complete `lua_pcallk` replacement would additionally need a private,
unforgeable control sentinel plus continuation logic for yield/resume and OOM
paths. That implementation does not exist in this revision and must not be
approximated with error-text matching.

The re-evaluation matrix is explicit native Android instrumentation rather than
an assumption derived only from source removal:

- `pcallAndXpcallRemainAbsentInMainAndCoroutines` proves both globals are nil on
  the main stack and inside a child coroutine;
- `rejectedNestedPcallCannotCatchOrDispatch` attempts two nested catch layers
  around `device.info` and proves the body makes zero Host calls;
- `rejectedXpcallCannotRunMessageHandler` places `ui.toast.v1` in a custom
  message handler and proves the handler makes zero Host calls;
- `coroutineOomCannotBecomeSuccessfulAndTheProcessRemainsReusable` retains the
  catchable `coroutine.resume` comparison boundary, proves a caught allocator
  failure cannot become success, and immediately reuses the process; and
- the main/coroutine deadline and cancellation tests retain their out-of-band
  `TerminationReason` classification before result conversion.

This matrix validates the selected rejection boundary. It does not claim that
stock protected calls or an unsubmitted wrapper are safe. A future proposal
must bring its own candidate implementation and pass the next reconsideration
gate below before either global is exposed.

## Recorded R5 native evidence

On 2026-08-27 the complete `NativeLuaRuntimeInstrumentationTest` class ran on
API 37 `emulator-5560`. The device reported `ro.kernel.qemu=1`, ABI list
`x86_64,arm64-v8a`, and page size 16,384 bytes. Only the isolated
`.native_test` application and its instrumentation APK were installed; no
physical device was installed, uninstalled, or used as evidence.

All 20 tests passed in 2.009 seconds, including the three new rejection tests,
ordinary runtime classification, main/coroutine deadline and cancellation,
yield/resume allocator accounting, caught OOM refusal, and immediate process
reuse. The application carried versionCode 62 and versionName `0.1.0-rc.2`.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app-nativeTest-x86_64-debug.apk` | 1,816,671 | `6ec04c3fc79adc8299ac7a1ff81faaa8f6cd77ff140630552722fe3a34e4cf7e` |
| `app-nativeTest-debug-androidTest.apk` | 940,778 | `f5ce4fa3d35d6cc1299fd5425515e78dbe767a3e4a993525eb8c7c59d70459f7` |

Both APKs used signer certificate SHA-256
`2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8`.
The exact semantic receipt is:

```text
PCALL_REEVALUATION_PASS serial=emulator-5560 api=37 abis=x86_64,arm64-v8a pageSize=16384 tests=20 failures=0 pcallAbsent=pass coroutineAbsent=pass nestedCatchDispatches=0 xpcallHandlerDispatches=0 deadline=pass cancel=pass caughtOom=pass processReusable=pass providerServices=absent physicalDeviceUsed=false
```

## Decision

R5 continues not to expose Lua-level `pcall` or `xpcall`. The native bootstrap
must execute both removals after opening the reviewed base library:

```cpp
remove_global(state, "pcall");
remove_global(state, "xpcall");
```

This decision makes no protocol or native capability change. Internal uses of
`lua_pcall` remain part of the trusted implementation boundary for library
installation, argument mapping, module loading, main-source execution, and
result conversion.

## Security property being preserved

Deadline, cancellation, and cancellation-probe failure are process-control
events, not ordinary script errors. `execution_hook` records a private
`TerminationReason` outside the Lua stack and raises a fixed Lua error. After
the outer protected call returns, native code checks that out-of-band reason
before a Lua status or result can become observable.

That check prevents a caught interruption from being converted into a
successful result, but it does not by itself force the untrusted chunk to
return. With ordinary Lua `pcall`, a script could repeatedly:

1. enter a long-running function;
2. catch the count-hook error;
3. ignore it and enter the function again.

Once a `TerminationReason` exists, every later hook poll raises again, but the
script can keep catching those errors. The process watchdog eventually
fail-stops the dedicated process, so isolation is retained, yet a routine
cancel or deadline would unnecessarily become process destruction. The R4
boundary instead requires control-plane interruption to be non-catchable and
to unwind directly to the trusted outer call.

`xpcall` adds another problem: its message handler is untrusted Lua code that
runs while an interruption is already active. A looping or allocating handler
would complicate the exact timeout, allocator, and teardown invariants.

## Alternatives evaluated

### Re-enable the stock base functions

Rejected. Stock `pcall` and `xpcall` cannot distinguish a control-plane hook
error from an ordinary business error. They violate the non-catchable
interruption property above.

### Identify interruption by error text

Rejected. A string is forgeable by the script, can be transformed by an
`xpcall` handler, and is not a stable machine identity. The fixed human-readable
hook text is diagnostic only.

### Raise a private light-userdata sentinel and wrap `pcall`

Still not adopted in R5. A private sentinel would improve identity, but a
complete solution still needs a native wrapper that checks the out-of-band
`TerminationReason` after every nested protected call and rethrows before
returning `(false, error)` to Lua. The wrapper must prove that the sentinel
cannot escape through logging, result conversion, module caching, or a custom
error handler.

### Native wrapper around `lua_pcallk`

Potentially viable later, but still deferred. The controlled coroutine
library is now admitted, and its outer control-plane/allocator matrix covers
resume, yield, deadline, cancellation, and OOM without exposing Lua-level
protected calls. That evidence does not validate a new `pcallk` continuation
wrapper. Nested protected calls, yielded continuations, repeated catches, and
`xpcall` handler behavior would still require their own native/device matrix.

## Required invariants

- Script-visible `pcall` and `xpcall` remain absent.
- The hook stores deadline/cancel/control failure in `TerminationReason`, which
  is owned outside the Lua stack.
- The outer native call checks the stored reason before interpreting Lua status
  or boxing any result.
- Business errors remain ordinary `RUNTIME_ERROR` failures; they are not
  confused with timeout or cancellation.
- The watchdog remains a last-resort cleanup boundary, not the normal
  implementation of script-level error handling.

## Next reconsideration gate

This decision may be reopened only when one implementation is accompanied by
native Android evidence for all of the following:

- ordinary business errors are catchable and returned with normal Lua `pcall`
  semantics;
- deadline, explicit cancel, close, callback death, and cancellation-probe
  failure are not catchable through one or many nested wrappers;
- an `xpcall` handler cannot run after a control-plane interruption has been
  claimed, or `xpcall` remains unavailable;
- OOM during wrapper/error construction preserves allocator accounting and
  deterministic `lua_close`;
- the behavior is defined across coroutine resume/yield if coroutine support is
  admitted;
- repeated catches cannot defer termination until the process watchdog kills
  an otherwise healthy runtime process.

Until one concrete candidate passes that matrix, rejection is the completed R5
decision—not an unimplemented promise to expose the functions.
