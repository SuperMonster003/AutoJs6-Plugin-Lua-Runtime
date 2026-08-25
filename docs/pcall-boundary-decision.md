# Lua-level `pcall` / `xpcall` boundary decision

Status: **REJECTED FOR R4**

Decision date: 2026-08-24

Scope: the untrusted script-visible base-library surface; internal native
protected calls remain required.

## Decision

R4 will not expose Lua-level `pcall` or `xpcall`. The native bootstrap must
continue to execute both of these removals after opening the reviewed base
library:

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

Not adopted in R4. A private sentinel would improve identity, but a complete
solution still needs a native wrapper that checks the out-of-band
`TerminationReason` after every nested protected call and rethrows before
returning `(false, error)` to Lua. The wrapper must prove that the sentinel
cannot escape through logging, result conversion, module caching, or a custom
error handler.

### Native wrapper around `lua_pcallk`

Potentially viable later, but intentionally deferred. The controlled coroutine
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

## Reconsideration gate

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

Until that matrix exists, rejection is the completed R4 decision—not an
unimplemented promise to expose the functions.
