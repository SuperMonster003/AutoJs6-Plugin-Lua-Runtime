# UI toast capability V1

Status: **IMPLEMENTED HOST/PROVIDER — API 37 END-TO-END VERIFIED**

Decision date: 2026-08-25

Host delivery date: 2026-08-27

## Outcome

R4-C adds one minimal, negotiated UI feedback entry point:

```lua
require("autojs").ui.toast("Saved")
```

The Provider maps that function only to the exact Host capability
`ui.toast.v1`. The script cannot choose a capability name, duration, Android
context, view, icon, position, gravity, callback, or any other presentation
option. A successful call returns no Lua values. It means that the Host accepted
the request into its UI lane and invoked Android Toast on the Host main thread.
The acknowledgement does not promise how long the platform will keep the Toast
visible or override Android foreground/suppression policy.

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

The response deliberately says `accepted`, not `shown`. The implemented Host
completes the call only after `Toast.makeText(...).show()` has run on its main
thread. Android may still suppress or delay presentation when the user has
disabled notifications/Toasts for the Host, or for other platform and
foreground-policy reasons outside this capability's guarantee.

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

## Negotiation and coordinated Host boundary

Provider metadata and the Host allowlist both contain `ui.toast.v1`. Actual
execution still requires their negotiated intersection. A Host that does not
list it supplies no grant, so calling `autojs.ui.toast` terminates through the
established `DENIED`/`HOST_CAPABILITY` path rather than hanging, crashing,
displaying a Provider-process toast, or downgrading to another API.

AutoJs6 Host revision `2db8355a5` adds the matching dispatcher. It repeats the
closed-map, strict UTF-8, 1–1,024-byte, and four-call checks, then invokes a
Host-owned `AndroidLuaToastSink` on the Android main thread. The Host calls the
sink exactly once and acknowledges only `{accepted=true}`. Invalid input,
missing grant, quota exhaustion, or sink failure has no alternate UI lane and
is never retried.

## Required evidence and completion

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

Host implementation and visible-device UI evidence remain separate from the
fake-invoker Provider smoke. Both are now present; the fake invoker is retained
only for exact boundary regression.

## Provider implementation

Implementation revision
`e26fbc1356dc9e98a0fdf11e4ab732f06079eac4` adds the complete Provider side:

- `NativeLuaHostCapabilityBridge.showToast(byte[])` repeats the 1–1,024-byte
  strict UTF-8 admission, emits only `ui.toast.v1` with `{text=StringValue}` and
  accepts only `{accepted=true}`;
- `autojs_ui_toast` enforces exact Lua type/arity, independently validates UTF-8,
  shares a four-attempt `ExecutionControl` counter across all coroutines, charges
  before JNI allocation, invokes `showToast` once, and returns zero Lua values;
- `install_autojs_module` publishes only `autojs.ui.toast` for this capability;
- R8 retains the exact `void showToast(byte[])` JNI descriptor;
- `LuaProviderMetadata.capabilities` now contains `device.info`,
  `module.snapshot.v1`, and `ui.toast.v1` in reviewed order; and
- the generic Binder invoker remains one `broker.invoke(...)` followed by a
  callback wait; repository checks reject a second dispatch.

## Exact Provider verification

On 2026-08-25, a clean build from implementation revision
`e26fbc1356dc9e98a0fdf11e4ab732f06079eac4` used
`native=true/provider=false/faultHarness=false`, versionCode 37, the pinned
offline inputs, and the repository debug-artifact gate. Results were:

- 42/42 Python repository/adversarial tests and 50/50 JVM tests passed;
- all arm64-v8a, x86_64, and universal debug APKs passed single-signer,
  ABI-inventory, 16 KiB ELF LOAD, 16 KiB ZIP, BuildConfig, resource, and manifest
  checks;
- the x86_64 app APK is 1,790,161 bytes with SHA-256
  `ab62c4bb40f77259f7d5f9eaad5ca213a72186ef8dea0897bd491ca4774aab5a`;
- the Android test APK is 945,715 bytes with SHA-256
  `74b1be6024b78bf366d93d19b062841b1064a75c78f67ec490b8df2db5d5763e`;
- both installed APKs use the debug certificate SHA-256
  `2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8`;
  and
- the complete `NativeLuaRuntimeInstrumentationTest` class passed 17/17 on API
  37 x86_64 `emulator-5554`, whose ABI list is `x86_64,arm64-v8a` and page size
  is 16,384 bytes. The installed app reported versionCode 37 and remained
  disabled (`enabled=0`); the test package remained versionCode 0.

The toast smoke preserved a multibyte message and embedded NUL, returned no Lua
value, and made exactly one fake-Host invocation per explicit call. Separate
cases proved that a malformed/false acknowledgement and a missing grant map to
`HOST_CAPABILITY`; the fifth and subsequent calls, 1,025-byte text, bad
continuation, overlong, surrogate, above-U+10FFFF, and truncated UTF-8 never
reached the Host invoker. An exactly 1,024-byte message succeeded in a new
execution, proving both the inclusive boundary and execution-local counter reset.

The machine also had physical devices attached, but none was installed,
uninstalled, or queried for package mutation. Every package operation and test
command used explicit serial `emulator-5554`; `connectedAndroidTest` was not
used.

Canonical receipt:

```text
UI_TOAST_PROVIDER_PASS serial=emulator-5554 api=37 abis=x86_64,arm64-v8a pageSize=16384 revision=e26fbc1356dc9e98a0fdf11e4ab732f06079eac4 appVersionCode=37 testVersionCode=0 tests=17 jvmTests=50 pythonTests=42 provider=false faultHarness=false appBytes=1790161 appSha256=ab62c4bb40f77259f7d5f9eaad5ca213a72186ef8dea0897bd491ca4774aab5a testBytes=945715 testSha256=74b1be6024b78bf366d93d19b062841b1064a75c78f67ec490b8df2db5d5763e signerSha256=2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8 fixedShape=pass quota=pass utf8=pass ack=pass denial=pass noRetry=pass
```

The read-only Host audit was repeated at AutoJs6 revision
`4a9718d63923834c9a99fd70e0cd58c898e138f6` while that workspace contained 27
pre-existing changes. Its `LuaRuntimeHostCapabilities.ENABLED` still listed
only `device.info` and `module.snapshot.v1`. No Host file was changed in that
historical Provider revision. The receipt above therefore closes only the
Provider-side R4-C criterion; the following evidence closes the later Host
follow-up.

## R5 Host implementation and end-to-end evidence

AutoJs6 Host capability revision `2db8355a5` adds:

- `ui.toast.v1` to the Host allowlist and session capability intersection;
- an exact Host dispatcher accepting only `{text=StringValue}` and returning
  only `{accepted=true}`;
- independent strict UTF-8, 1–1,024-byte, and four-dispatch checks;
- `AndroidLuaToastSink`, which posts to the main looper when needed and invokes
  Android `Toast.makeText(..., LENGTH_SHORT).show()` exactly once; and
- JVM denial, wrong-shape, byte-boundary, independent quota, and no-fallback
  coverage.

On 2026-08-27 the independently installed debug Host and official Provider were
tested together on API 37 `emulator-5560`. The opt-in
`LuaOfficialRuntimeSmokeTest` passed 1/1 and executed the Toast call through the
real Provider process and Binder broker, alongside device info, persistent
storage, and module snapshot checks. With notifications enabled for the Host,
the unique marker reached the Host-owned Android Toast UI lane and was observed
on the dedicated emulator; no fake broker or Provider-process Toast was
involved. A control run with that user permission disabled was accepted by the
Host but explicitly suppressed by Android `NotificationService`, matching the
`accepted`, not `shown`, contract above.

The same Provider build's complete native class passed 23/23, retaining exact
request/acknowledgement, four-call quota, malformed UTF-8, denial, and no-retry
coverage. Host focused JVM tests passed 35/35. Every install and test command
named `emulator-5560`; connected physical devices were not mutated or used as
evidence.
