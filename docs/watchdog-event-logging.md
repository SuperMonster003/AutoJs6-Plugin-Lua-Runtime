# Lua watchdog fail-stop event logging

Status: **IMPLEMENTED — PROVIDER DEFAULT-OFF**

Implementation date: 2026-08-25

## Contract

Every owned watchdog fail-stop now attempts one fixed-shape Android log event
immediately before terminating the dedicated `:lua_runtime` process. The
Android logcat tag is the constant `AutoJs6LuaWatchdog`. The message contains
exactly two space-delimited ASCII `key=value` fields:

```text
event=lua_runtime_fail_stop reason=<closed_reason_tag>
```

The reason mapping is closed and exhaustive:

| Process termination reason | Structured reason tag |
|---|---|
| `DEADLINE_CLEANUP_EXPIRED` | `deadline_cleanup_expired` |
| `STOP_CLEANUP_EXPIRED` | `stop_cleanup_expired` |
| `WATCHDOG_CONTROL_FAILURE` | `watchdog_control_failure` |

`LuaWatchdogLogContract` owns the Android tag, event tag, all three reason-tag
constants, and the exhaustive enum mapping. There is no generic string or map
entry point through which a caller can add fields. `AndroidLuaWatchdogEventLogger`
is the only production adapter and emits the contract through
`Log.e(LOGCAT_TAG, message)`.

This is an operational aggregation event, not a terminal callback or durable
crash record. It does not modify the frozen protocol, runtime-info capability
list, JNI bridge, or script-visible API. Logcat availability, retention,
permissions, collection, and export remain Host/device policy.

## Ordering and failure behavior

All deadline, stop, and scheduler-control failures converge on
`LuaExecutionWatchdog.terminateProcess`. The lifecycle ordering is:

1. while owning the watchdog lock, prove exact token ownership and poison the
   process guard;
2. synchronously offer the exact reason/token to the private crash-diagnostic
   observer;
3. release the lifecycle lock;
4. invoke the structured event logger with the same closed reason; and
5. invoke `AndroidLuaRuntimeProcessTerminator`, which uses `killProcess` and an
   abrupt `Runtime.halt` fallback.

The diagnostic observer and event logger are independently wrapped so an I/O
failure, Android logging failure, or test logger exception cannot suppress or
delay the terminator call. A stale scheduled callback fails the token check and
therefore emits no log and kills no replacement execution. Repeated stop calls
do not extend grace or create duplicate tasks. Once an owned event poisons the
guard, no later fail-stop reason can be emitted from that process even if a test
terminator unexpectedly returns.

The production watchdog explicitly receives `AndroidLuaWatchdogEventLogger`;
the default injected logger is a no-op so Android-free unit construction does
not depend on logcat. This default is not reachable from the production
`ProcessExecutionResources` wiring, which is pinned by the repository verifier.

## Privacy boundary

The tag and message are entirely constant except for the closed enum mapping.
They contain no execution token, request ID, PID, UID, source name, source body,
source hash, argument, result, output, exception, stack trace, module data,
Host-capability payload, timing, memory value, device identity, or arbitrary
label. Android logcat itself supplies ordinary process metadata; this feature
does not duplicate it into the message.

The durable private crash record remains the correlation surface for failure
kind, phase, and the first eight SHA-256 bytes. Those values are intentionally
not copied into logcat. See
[`crash-diagnostic-v1.md`](crash-diagnostic-v1.md).

## Verification

`LuaExecutionWatchdogTest.everyFailStopReasonUsesTheClosedStructuredLogContract`
constructs independent deadline, stop, and scheduler-failure watchdogs with an
injected recorder. It asserts that the logger receives the three enum values
exactly once and in the closed enum order, pins the logcat tag, and pins all
three complete messages.

`terminationObserverRunsBeforeTerminatorAndCannotSuppressFailStop` injects both
a diagnostic observer and logger that throw. Its observed sequence must still
be exactly `diagnostic`, `log`, `terminate`, proving both ordering and failure
containment. Existing stale-token and normal-finish tests prove cancelled
callbacks do not emit or terminate.

The hostile Python boundary mutates/removes the logger call, a reason tag, the
Android `Log.e` adapter, and production injection; every mutation must fail.
The completed implementation tree passes 44/44 Python tests and 59/59 JVM
tests through the repository-owned offline gate with Provider discovery and
network access disabled. The Roadmap completion criterion is source/JVM based;
no new device claim is made for this item.
