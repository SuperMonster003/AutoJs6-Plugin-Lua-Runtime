# UI toast capability V1

Status: **IMPLEMENTED PROVIDER-SIDE — HOST FOLLOW-UP REQUIRED**

Decision date: 2026-08-25

## Outcome

R4-C adds one minimal, negotiated UI feedback entry point:

```lua
require("autojs").ui.toast("Saved")
```

The Provider maps that function only to the exact Host capability
`ui.toast.v1`. The script cannot choose a capability name, duration, Android
context, view, icon, position, gravity, callback, or any other presentation
option. A successful call returns no Lua values. It means only that the Host
accepted the request for enqueueing; it does not prove that Android has already
made the toast visible.

The capability is additive to frozen Lua protocol 1.0 because capability names
and `LuaValue` request/result trees are already negotiated by the existing Host
broker. It adds no AIDL method, descriptor, protocol enum, or parcelable field.

## Fixed request and acknowledgement

The bridge accepts exactly one Lua string and performs exactly one Provider-to-
Host capability invocation:

```text
capability -> "ui.toast.v1"
arguments  -> {text=string}
result     -> {accepted=true}
```

Both maps are closed. An absent or extra key, a wrong value type, or
`accepted=false` is rejected. The Provider validates the acknowledgement before
returning control to Lua. Host failures, malformed acknowledgements, cancellation,
and deadline expiry use the existing deterministic terminal mapping; there is no
local Android toast fallback.

The response deliberately says `accepted`, not `shown`. The future Host
implementation must complete the call only after it has accepted the text into
its Host-owned UI lane. Android may suppress or delay a toast for platform or
foreground-policy reasons outside this capability's delivery guarantee.

## Text boundary

The argument must be a Lua value whose exact type is string. Numeric coercion,
missing arguments, extra arguments, and an empty string are rejected locally
before Host dispatch.

The encoded text must be strict UTF-8 and 1 through 1,024 bytes inclusive. The
limit is measured in UTF-8 bytes, not UTF-16 code units, Unicode scalar values,
or grapheme clusters. Overlong encodings, truncated sequences, surrogate code
points, and values above U+10FFFF are invalid. Valid whitespace, line breaks,
and NUL are not normalized or silently removed; the Host receives the decoded
string exactly as admitted by the Provider.

The Kotlin fixed-shape bridge independently repeats the non-empty, byte-limit,
and strict UTF-8 checks before constructing `LuaValue.StringValue`. This keeps a
future native-call-site regression from widening the Binder request.

## Per-execution quota

One execution may attempt at most four valid toast dispatches. The native
`ExecutionControl` owns the counter, so all main-thread and coroutine calls in
the same `lua_State` share the same quota while a new execution starts at zero.

The slot is charged after local type, length, and UTF-8 validation but before
allocating the JNI request or invoking the Kotlin bridge. A Host denial,
malformed acknowledgement, timeout, cancellation race, Binder failure, or lost
acknowledgement does not refund the slot. Once four calls have been attempted,
every later call fails locally without crossing JNI or Binder. This remains true
even if `coroutine.resume` catches the Lua error.

The four-call ceiling is intentionally narrower than the protocol-wide Host-call
lifetime ceiling. It limits UI spam without consuming or redefining quota for
`device.info`, `module.snapshot.v1`, or future capabilities.

## Dispatch and retry rule

The fixed bridge calls `LuaHostCapabilityInvoker.invoke` exactly once. The
production `BinderLuaHostCapabilityInvoker` already performs exactly one
`broker.invoke(...)`, then waits on that call's single terminal callback while
polling cancellation and deadline. Neither layer contains a retry loop.

After any dispatch, the Provider must never issue a replacement call, including
when it cannot determine whether the Host accepted the first call. A new call ID
would risk displaying the same feedback twice; replaying the old call ID would
violate the session policy. Scripts may start a new explicit call only while
their execution-local four-call quota remains, and the Provider never does so
on their behalf.

## Negotiation and current Host boundary

The Provider advertises `ui.toast.v1` in `LuaProviderMetadata.capabilities`.
Actual execution still requires the Host/provider intersection to grant the
capability. A Host that does not list it supplies no grant, so calling
`autojs.ui.toast` terminates through the established
`DENIED`/`HOST_CAPABILITY` path rather than hanging, crashing, displaying a
Provider-process toast, or downgrading to another API.

A read-only audit of the adjacent AutoJs6 workspace on 2026-08-25 found that its
current `LuaRuntimeHostCapabilities.ENABLED` contains only `device.info` and
`module.snapshot.v1`; it has no Lua `ui.toast.v1` dispatcher. That dirty Host
workspace is outside this Provider change and is not modified here. End-to-end
visual delivery therefore remains a coordinated Host follow-up, while the
Provider shape, validation, quota, denial behavior, and JNI smoke can be
completed and verified independently.

## Required evidence

Provider completion requires all of the following:

- an Android-free JVM test covering the exact grant request/acknowledgement,
  malformed acknowledgement rejection, input boundaries, and deterministic
  denial;
- native instrumentation proving one successful fixed-shape dispatch and no Lua
  return value;
- native instrumentation proving that call five and oversized or malformed
  UTF-8 text do not reach the Host invoker;
- repository checks pinning the Lua table shape, JNI descriptor, byte and call
  limits, response validator, metadata entry, and one-dispatch/no-retry Binder
  structure; and
- the unchanged full local verifier/JVM gates plus a fresh complete native
  instrumentation run on the reviewed emulator.

Host implementation and visible-device UI assertion are separate coordinated
evidence and must not be inferred from a fake-invoker Provider smoke.
