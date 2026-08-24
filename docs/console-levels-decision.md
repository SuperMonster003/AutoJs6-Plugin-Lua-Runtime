# Console level mapping decision

Status: **IMPLEMENTED AS TWO-STREAM ALIASES**

Decision date: 2026-08-24

## Outcome

R4 adds `require("autojs").console.info(string)` and
`require("autojs").console.warn(string)` as API aliases. It does not add wire
streams, protocol enum values, capabilities, or callback methods:

| Lua entry point | Native bridge | Protocol stream | Wire code |
|---|---|---|---:|
| `print(...)` | bounded stringifying print bridge | `LuaOutputStream.STDOUT` | 1 |
| `autojs.console.log(string)` | exact-one-string bridge | `LuaOutputStream.STDOUT` | 1 |
| `autojs.console.info(string)` | exact alias of `console.log` | `LuaOutputStream.STDOUT` | 1 |
| `warn(string)` | exact-one-string error bridge | `LuaOutputStream.STDERR` | 2 |
| `autojs.console.error(string)` | exact-one-string error bridge | `LuaOutputStream.STDERR` | 2 |
| `autojs.console.warn(string)` | exact alias of `console.error` | `LuaOutputStream.STDERR` | 2 |

The native constants are `kStdoutStreamWireCode = 1` and
`kStderrStreamWireCode = 2`. The JVM boundary test pins those values to the
frozen `LuaOutputStream` enum. The inventory remains exactly `STDOUT` and `STDERR`.

## Why aliases instead of new streams

The current Host protocol transports ordered stdout/stderr chunks. Adding
`INFO` and `WARN` enum members locally would make the frozen Provider and Host
interpret different wire values and would require coordinated AAR evolution.
For current scripts, the important behavior is severity routing: informational
messages follow normal output, while warnings follow diagnostic output. The
two-stream mapping provides that behavior without changing negotiation or wire
compatibility.

The mapping is intentionally lossy. A Host cannot distinguish `log` from
`info`, or `error` from `warn`, after receiving a chunk. Scripts that need
machine-readable level identity must wait for a separately negotiated protocol
extension; they must not encode hidden control prefixes that this Provider
claims to interpret.

## Unchanged limits

All six entry points use the existing synchronous output emitter and preserve:

- callback sequence ordering;
- output credits;
- the 32 KiB per-chunk ceiling;
- the execution-wide total-output ceiling;
- strict UTF-8 decoding at the Kotlin boundary; and
- fail-closed rejection when output cannot be delivered.

`console.log`, `console.info`, `console.error`, `console.warn`, and global `warn`
accept exactly one non-empty Lua string. Global `print` retains its reviewed
multi-argument behavior: it stringifies values, joins them with tabs, appends a
newline, then uses the same bounded stdout emitter. There is no unrestricted
fallback to a process stream or logcat.

## Evidence and future gate

`NativeLuaRuntimeInstrumentationTest.nativeCoreAndRunnerReturnV1Scalars`
executes all four `autojs.console` methods through JNI and asserts the ordered
two-stream result. `NativeLuaRuntimeBoundaryTest.
consoleLevelAliasesRetainExactlyTwoWireStreams` pins the protocol enum inventory
and wire values without loading JNI. The repository verifier requires both
aliases to bind to their reviewed native functions exactly once. On 2026-08-24
the complete 11-test native instrumentation class passed on the 16 KiB x86_64
`emulator-5554` with Provider discovery disabled.

A future distinct-level wire design requires canonical Host protocol constants,
minor-version/capability negotiation, Provider metadata changes, unknown-level
rejection, V1 downgrade behavior, and Host/Provider conformance tests. Until all
of those land together, this two-stream mapping is the complete console-level
contract.
