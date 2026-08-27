# Lua crash diagnostic V1

Status: **IMPLEMENTED — DEVICE VERIFIED**

Implementation date: 2026-08-25

Protocol input: frozen Lua protocol 1.0. No protocol AAR, AIDL method, terminal
schema, or JNI Host-capability shape is changed by this feature.

## Outcome and scope

The dedicated `:lua_runtime` process now leaves one minimal, process-private
record when either of these events prevents it from publishing an ordinary
terminal callback:

- the process dies while inside the untrusted native runner; or
- the process-wide watchdog reaches a deadline/stop cleanup limit or loses
  control of its scheduler and must fail-stop the process.

On the next process start, `getRuntimeInfo()` adds the content-free marker
`diagnostic.last-abnormal-termination.v1` to `LuaRuntimeInfo.capabilities` when
a valid record exists. The executable Host capabilities are now exactly
`device.info`, `module.snapshot.v1`, `storage.kv.v1`, and `ui.toast.v1`; the
diagnostic marker is observational state, is never routed through the
Host-capability broker, and does not create a Lua API.

This is one last-event indicator, not a crash history, telemetry upload,
symbolicated native tombstone, Java exception log, or replacement for Android
tombstones/logcat. The complete private record is deliberately not encoded in
`LuaRuntimeInfo`.

## Private record

The backing file is below `context.applicationContext.noBackupFilesDir` in
`lua-runtime-diagnostics/last-abnormal-termination.v1`. The document names the
relative components for review; callers never receive the absolute filesystem
path. `noBackupFilesDir` keeps it app-private and excludes it from Auto Backup.

The canonical format is exactly 20 bytes in big-endian order:

| Offset | Bytes | Field | Validation |
|---:|---:|---|---|
| 0 | 4 | magic | fixed `A6LD` integer magic |
| 4 | 1 | version | exactly `1` |
| 5 | 1 | failure kind | closed enum below |
| 6 | 1 | execution phase | closed enum below |
| 7 | 1 | hash-prefix length | exactly `8` |
| 8 | 8 | source SHA-256 prefix | first eight digest bytes, never source bytes |
| 16 | 4 | CRC32 | checksum of bytes 0–15 |

The reader consumes at most 21 bytes so an oversized file is detected without
an unbounded allocation. A wrong size, magic, version, enum, prefix length, or
checksum is treated as malformed and deleted; an I/O read failure is reported
as “no usable diagnostic.” `AtomicFile.startWrite`/`finishWrite` publishes the
fixed record synchronously, and `failWrite` restores the previous valid file
when publication fails.

No source body, source name, arguments, return value, output, error text, stack
trace, module content, Host-capability payload, device identity, wall-clock
timestamp, PID, or arbitrary label is retained. The eight-byte SHA-256 prefix
is only a bounded correlation hint and must not be treated as a unique script
identifier.

## Closed enums

Failure kinds are persisted with stable one-byte codes:

| Code | Kind | Meaning |
|---:|---|---|
| 1 | `NATIVE_CRASH` | managed control did not return after entering the native runner |
| 2 | `DEADLINE_CLEANUP_EXPIRED` | end-to-end deadline plus cleanup grace expired |
| 3 | `STOP_CLEANUP_EXPIRED` | explicit stop/death cleanup grace expired |
| 4 | `WATCHDOG_CONTROL_FAILURE` | the watchdog could not arm or maintain its fail-stop task |

Execution phases are `QUEUE` (1), `SOURCE_VALIDATION` (2), and
`NATIVE_EXECUTION` (3). The coordinator acquires the declared request digest
with the same unforgeable process-local token as the active-execution and
watchdog gates. Source validation advances the phase before the potentially
blocking PFD read. Native entry occurs only after the source verifier has
checked length, strict UTF-8, and the full SHA-256, so a native crash records a
verified digest prefix. A watchdog failure during source validation records the
declared digest prefix because the blocking source has not returned to permit
verification.

## Publication lifecycle

Native code cannot reliably execute Kotlin after a fatal signal. Immediately
before calling the untrusted native runner, the coordinator therefore writes a
provisional `NATIVE_CRASH/NATIVE_EXECUTION` record synchronously. Any managed
return—success or a typed Lua/native failure—runs `nativeExecutionReturned()`
from `finally` and clears that provisional file. While the execution is still
active, `getRuntimeInfo()` suppresses the provisional marker so a concurrent
metadata read cannot misreport a live execution as a previous crash.

The watchdog poisons its owned token first, synchronously invokes the diagnostic
observer while holding the watchdog lifecycle lock, and only then invokes the
process terminator. The observer overwrites the provisional record with the
exact watchdog reason and current phase and marks it committed. A racing worker
return or session cleanup cannot clear a committed record. Observer exceptions
are contained so a storage failure cannot suppress mandatory fail-stop.

After process death, no active in-memory token exists. A fresh runtime process
validates the private file and exposes only the marker through
`getRuntimeInfo()`. Reads are non-consuming, so repeated discovery calls remain
stable. The next verified native execution replaces the stale record with its
own provisional entry and clears it after a healthy managed return; a later
`getRuntimeInfo()` then omits the marker. A second abnormal termination instead
atomically replaces the record with the latest event.

Failure to write the provisional native record prevents entry into the native
runner and follows the existing internal-failure path. Failure to read or
delete a malformed old record never invents a diagnostic. These fail-closed
choices prioritize avoiding unrecorded native entry and avoiding false status.

## Verification boundary

Android-free tests fix the 20-byte codec, defensive digest copies, corruption
and oversize rejection, provisional suppression/healthy clearing, watchdog
overwrite persistence, conditional marker behavior, and diagnostic-before-kill
ordering even when the observer throws. The session-controller test proves that
source validation and verified native execution are bracketed in order.

The opt-in debug fault instrumentation resets the private store, injects the
real JNI crash, observes Binder death, binds a fresh `:lua_runtime` PID/nonce,
decodes `getRuntimeInfo()`, and checks both the content-free marker and the
private `NATIVE_CRASH/NATIVE_EXECUTION` record with the expected eight digest
bytes. It then executes `return 7` and asserts that both file and marker are
gone. The native wedge and hanging-pipe watchdog cases additionally check exact
deadline kind and phase. The harness stays debug-only, explicit-only,
Provider-default-off, and physically absent from release variants.

Repository static checks reject source/stack retention, a full digest,
non-atomic publication, unconditional marker advertising, runner bracketing
removal, watchdog-observer removal, and recovery-assertion removal.

## Device evidence

The committed implementation revision
`d882dea986dd95f1f0e6aeb484618ca99f703dfb` passed the repository-owned offline
gate before artifact construction: 44/44 Python hostile/static tests and 58/58
JVM tests, with immutable inputs ready, Provider discovery disabled, and network
access disabled. A clean, offline build then used
`native=true`, `provider=false`, and `faultHarness=true` to create the exact
artifacts below:

| Artifact | Version | Bytes | SHA-256 |
|---|---:|---:|---|
| `app-x86_64-debug.apk` | 40 | 1,843,067 | `1552e47be94542b0fab5fa28624691f2c7c3ee3e493c14ece9dc9816c5d75e8f` |
| `app-debug-androidTest.apk` | 0 | 946,983 | `a105d92e2fe476d246e0b57d1109bb5a22a1e7d82f65a6e96f92c9901035167f` |

Both APKs use signer SHA-256
`2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8`.
They were installed only with explicit `adb -s emulator-5554` commands. The
target was API 37, ABI list `x86_64,arm64-v8a`, 16,384-byte page size, and build
fingerprint
`google/sdk_gphone16k_x86_64/emu64xa16k:17/CE2A.260420.019/15611780:userdebug/dev-keys`.
Three physical devices and other emulators were visible, but no command targeted
them; `connectedAndroidTest` was not used.

The complete `LuaRuntimeFaultRecoveryInstrumentationTest` ran 4/4 in 7.984
seconds. The JNI crash case recovered in a new PID/nonce and observed
`NATIVE_CRASH/NATIVE_EXECUTION`; the native wedge observed
`DEADLINE_CLEANUP_EXPIRED/NATIVE_EXECUTION`; the blocked pipe observed
`DEADLINE_CLEANUP_EXPIRED/SOURCE_VALIDATION`. Every case matched the expected
first eight SHA-256 bytes through the private record and observed the
content-free runtime-info marker. Each then completed `return 7` and verified
the record and marker were absent. The existing FD and independent-peer-death
regression also passed, and a final `run-as` listing showed the private
diagnostic directory empty.

```text
CRASH_DIAGNOSTIC_INSTRUMENTATION_PASS serial=emulator-5554 api=37 abis=x86_64,arm64-v8a pageSize=16384 revision=d882dea986dd95f1f0e6aeb484618ca99f703dfb appVersionCode=40 testVersionCode=0 tests=4 appBytes=1843067 appSha256=1552e47be94542b0fab5fa28624691f2c7c3ee3e493c14ece9dc9816c5d75e8f testBytes=946983 testSha256=a105d92e2fe476d246e0b57d1109bb5a22a1e7d82f65a6e96f92c9901035167f signerSha256=2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8 nativeCrash=pass nativeWedge=pass blockedSource=pass healthyClear=pass fdRegression=pass physicalDevicesUntouched=true
```
