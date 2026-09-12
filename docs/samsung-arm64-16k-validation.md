# Samsung ARM64 16 KiB device validation

Status: **35/35 DISTINCT JUNIT TESTS PASSED — REAL HOST SMOKE AND VISIBLE TOAST VERIFIED**

Evidence date: 2026-09-10 (Asia/Shanghai)

## Outcome and scope

The owner supplied an RDB-connected Samsung device and authorized useful device
testing. This run adds native ARM64 execution on a physical 16 KiB device to the
previous x86_64 emulator evidence. All 23 native-runtime tests, four Binder
capability-invoker tests, and four fault-recovery tests passed with no failed or
ignored tests. After the owner confirmed that concurrent device testing had
finished, the real-Host follow-up passed three storage-backend tests, one full
capability smoke test, and the independent Host smoke with two Lua executions.
The capability test passed again after enabling notifications for visual Toast
verification. Across both phases, 35 distinct JUnit tests passed; the visual
rerun adds one repeated execution, not another distinct test.

This is supplementary R5 development evidence. It does not reinstate the retired
R4-E release gate or production soak. The tested development APKs have versionCode
62 and differ from the immutable signed rc.2 release artifacts with versionCode
43. Their results do not change that candidate's `deviceVerified=false` or
`runtimeVerified=false` receipt.

## Device

| Property | Observed value |
|---|---|
| Explicit ADB serial | `localhost:31277` |
| Manufacturer / model | Samsung / `SM-A566B` |
| Android / API | 16 / 36 |
| ABI list / kernel architecture | `arm64-v8a` / `aarch64` |
| `getconf PAGE_SIZE` | 16,384 bytes |
| `ro.kernel.qemu` | `0` |
| Installed native/fault app `primaryCpuAbi` | `arm64-v8a` |
| Installed native/fault/Provider app `pageSizeCompat` | `0` for all three |

Build fingerprint:

```text
samsung/a56xnaeea_16kb/a56x:16/BP2A.250605.031.A3/A566BXXU6BYIF_OXM6BYIF:user/release-keys
```

The observations are archived in
[`device-observations.json`](evidence/samsung-arm64-16k-20260910/device-observations.json).
Every device command specified `-s localhost:31277`; no `connectedAndroidTest`
task or unscoped install/test/uninstall command was used.

## Build inputs and artifact identities

The plugin base revision was
`f2cba38f12e19a6d17bed8bce0d5bb5a6b90b402`. Before this task, the worktree already
contained changes to `build.gradle.kts`, `settings.gradle.kts`, and
`version.properties` for the shared platform-version plugin. They were used as
found and are captured in
[`input-worktree.patch`](evidence/samsung-arm64-16k-20260910/input-worktree.patch).
This run does not claim a clean checkout or a new release candidate.

The following offline invocation succeeded in 45 seconds, with 63 tasks executed,
12 restored from cache, and 159 up to date:

```powershell
.\gradlew.bat :app:testProviderDebugUnitTest `
    :app:assembleNativeTestDebug :app:assembleNativeTestDebugAndroidTest `
    :app:assembleFaultTestDebug :app:assembleFaultTestDebugAndroidTest `
    :app:assembleProviderDebug :host-lifecycle-test:assembleDebug `
    --offline --no-daemon --console=plain
```

The JVM task was `UP-TO-DATE`; its XML reports were checked for exactly 61 tests,
zero failures/errors/skips. This is report validation, not a fresh JVM execution.
The build-ready verifier passed, and the Python hostile/static suite ran and
passed 50/50. Device instrumentation was executed afresh.

The initial isolated phase installed the following four APKs. Each package was absent
immediately before installation, each install returned `Success`, and a subsequent
device-side `sha256sum` of its installed `base.apk` matched the local artifact.

| APK | Bytes | SHA-256 |
|---|---:|---|
| `app-nativeTest-arm64-v8a-debug.apk` | 1,873,830 | `141f8b12f7dff1b315cf55bc28454a01dc89352bbafa4a95b905193b76880799` |
| `app-nativeTest-debug-androidTest.apk` | 957,295 | `fc7a1e5bf8faae38c3b427db71ef659d53af212496632a78361b287c3a299313` |
| `app-faultTest-arm64-v8a-debug.apk` | 1,929,080 | `7df7148934ae8cc688d3f9d31f729b0e7bc5aa63feea479e69cd8594a525a9a0` |
| `app-faultTest-debug-androidTest.apk` | 962,427 | `f79c5817c098bdb6af637c891d5bc6482db3bf693ec1aed71c50998273259c87` |

All four passed `apksigner verify` and `zipalign -c -P 16 4`, with the same local
Android debug certificate SHA-256:

```text
2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8
```

The native payloads were independently parsed as little-endian ELF64 AArch64.
Every `PT_LOAD` segment had `p_align=16384` and congruent virtual-address/file
offsets modulo 16,384. Both `.so` ZIP entries were uncompressed and began at a
16,384-byte-aligned offset. The native-test library SHA-256 was
`ef12450f21890c4b1a9df105b76cd058eed102907cdf18e71e1e882c9d511de9`; the
fault-test library SHA-256 was
`2553c6368dc20660642d32261428b873331e42761240c5112c71a6b1c4858586`.

The locally assembled `providerDebug` library was byte-identical to the native-test
library and passed the same ELF/ZIP checks. Its APK was subsequently installed in
the real-Host follow-up below. Exact offsets, package identities, signers, run
times, and output hashes for the initial phase are recorded in
[`receipt.json`](evidence/samsung-arm64-16k-20260910/receipt.json).

## Executed tests

| Suite | Passed | Relevant coverage |
|---|---:|---|
| `NativeLuaRuntimeInstrumentationTest` | 23/23 | Lua scalar results, arguments, text-module caching/cycles, controlled libraries, error mapping, deadline/cancel hooks, coroutine control, OOM and immediate reuse, pcall/xpcall exclusion, device-info/toast/storage bridge shapes and quotas |
| `BinderLuaHostCapabilityInvokerInstrumentationTest` | 4/4 | Execution/call-ID/UID binding, rejection before dispatch, cancellation without retry, rejected PFD closure and accounting |
| `LuaRuntimeFaultRecoveryInstrumentationTest` | 4/4 | Native crash and new-process recovery, watchdog recovery from a native wedge, hanging-pipe containment, OS FD recovery across terminal and independent callback/broker death paths |

The first command ran both classes in the native-test APK. The second selected
only the fault-recovery class, so the shared Binder class was not counted twice:

```powershell
adb -s localhost:31277 shell am instrument -w -r `
    io.github.supermonster003.autojs6.plugin.lua.runtime.native_test.test/androidx.test.runner.AndroidJUnitRunner

adb -s localhost:31277 shell am instrument -w -r `
    -e class io.github.supermonster003.autojs6.plugin.lua.runtime.LuaRuntimeFaultRecoveryInstrumentationTest `
    io.github.supermonster003.autojs6.plugin.lua.runtime.fault_test.test/androidx.test.runner.AndroidJUnitRunner
```

| Run | Instrumentation duration | Completion |
|---|---:|---|
| Native + Binder | 0.866 seconds | `OK (27 tests)`, `INSTRUMENTATION_CODE: -1` |
| Fault recovery | 7.102 seconds | `OK (4 tests)`, `INSTRUMENTATION_CODE: -1` |

Each test emitted success status `0`; there were no assumption skips. Raw output
is archived in
[`native-instrumentation.txt`](evidence/samsung-arm64-16k-20260910/native-instrumentation.txt)
and
[`fault-instrumentation.txt`](evidence/samsung-arm64-16k-20260910/fault-instrumentation.txt).
Installation commands and installed-artifact hash observations are retained in
[`installation.json`](evidence/samsung-arm64-16k-20260910/installation.json).

The fault tests deliberately caused one native crash and two watchdog fail-stops.
The two `event=lua_runtime_fail_stop reason=deadline_cleanup_expired` log entries
belong to the hanging-pipe and native-wedge cases; they are expected test effects.
The tests asserted Binder death, changed PID/nonce, persisted crash diagnostics,
successful `return 7` after recovery, and clearing of the diagnostic after success.
The FD case asserted return to its measured baseline after four success/digest-error
batches, cancellation, and independent callback/broker death. Its numeric baseline
was not printed, so no absolute FD count is claimed. The targeted Android logs are
archived in [`targeted-logcat.txt`](evidence/samsung-arm64-16k-20260910/targeted-logcat.txt).

## Initial pause and isolated-package cleanup

Initially the device reported no AutoJs6 packages. By the real-Host installation
preflight, `org.autojs.autojs6` (versionCode 5279), `org.autojs.autojs6.test`, and a
Python runtime plugin had appeared through activity outside this run. The initial
preflight stopped before replacing the existing Host. That phase did not install,
replace, clear, instrument, force-stop, or uninstall those packages, and did not
install the Lua Provider or Host lifecycle harness.

The initial device-info/toast/storage native cases used test Host invokers. Their
receipts retain that scope; the real-Host claims come from the separate follow-up
below. The owner confirmed that the other projects had been testing concurrently,
that their testing was now complete, and that Host uninstall/reinstall was
authorized if needed.

The four packages installed by the initial phase were removed afterward. Each uninstall
returned `Success`; subsequent `pm path` and checks of their main/runtime/peer
process names were empty. The cleanup receipt is archived in
[`cleanup.json`](evidence/samsung-arm64-16k-20260910/cleanup.json). Other connected
devices were not used. No runtime implementation change was needed for the
31 covered cases.

## Real Host follow-up

### Installation and source boundary

The existing Host and Host-test APKs used a different certificate from the Lua
development APKs. Their installed bytes were pulled and verified before the
authorized uninstall. Same-debug-signer Host and Host-test copies were then
installed together with the official-package `providerDebug` APK and independent
Host lifecycle instrumentation. Every installed `base.apk` hash matched its
prepared artifact. The Host copies' ZIP entries outside `META-INF/` were
byte-identical to the original APK entries; the change was signing material.
The Host was a prebuilt local artifact, not a fresh Host-source rebuild.

| Role / versionCode | APK | Bytes | SHA-256 |
|---|---|---:|---|
| Host / 5279 | `autojs6-arm64-debug-signed.apk` | 48,105,776 | `e38cc4a51a538860f68a1541ad0c936a968e8d615760c682a74be427329c57c2` |
| Host test | `autojs6-test-debug-signed.apk` | 2,274,686 | `c331dd2c54ca2c3b7b81a32c325be6c4d5d734972598dc7d4d27f1bfb2ee62e0` |
| Lua Provider / 62 | `app-provider-arm64-v8a-debug.apk` | 1,874,178 | `60e59d9251dbb4f59b5e36f8969801a592eb763295e24ae6acf673ba7c167af8` |
| Independent Host harness / 62 | `host-lifecycle-test-debug.apk` | 1,008,487 | `85e78172f286a7a74fa155799a0e5764dc97856620a2fbd81eb1f505abf9cb4a` |

All four APKs passed signature verification with the local Android debug
certificate recorded above. The preflight, installation, and original APK
identities are archived under
[`host/receipt.json`](evidence/samsung-arm64-16k-20260910/host/receipt.json).

### Results

| Check | Result | Observed behavior |
|---|---|---|
| `LuaHostStorageInstrumentationTest` | 3/3, 0.884 seconds | Persistence across backend instances, principal isolation, quota rejection without overwriting committed data, concurrent writers without lost entries |
| Independent `LuaHostLifecycleInstrumentation`, `mode=smoke` | Pass, two executions | Exactly one official INFO/RUNTIME service, matching Host/Provider signatures, `return 7`, device SDK 36, real Host console marker |
| `LuaOfficialRuntimeSmokeTest` | 1/1, 10.335 seconds | Eight sequential Lua executions covering results, device info, console, Toast acknowledgement, same-file storage persistence/increment, different-file isolation, clear, and V1 module cache |
| Same capability test with notifications allowed | 1/1 repeated, 10.261 seconds | All eight executions passed again; the recorded screen visibly displayed the unique Lua Toast marker |

Every JUnit run returned `INSTRUMENTATION_CODE: -1`, the expected test count, and
success status `0` for every test, with no ignored tests. The independent smoke
returned `discovery=pass`, `result=pass`, `console=pass`, and `executions=2`:

```text
LUA_HOST_OFFICIAL_SMOKE_PASS runId=samsung-arm64-16k-host-20260910 hostPid=19402 hostUid=10341 hostVersionCode=5279 providerVersionCode=62 executions=2 discovery=pass result=pass console=pass
```

Raw command arguments and instrumentation outputs are retained in
[`storage.json`](evidence/samsung-arm64-16k-20260910/host/storage.json),
[`smoke.json`](evidence/samsung-arm64-16k-20260910/host/smoke.json),
[`capabilities.json`](evidence/samsung-arm64-16k-20260910/host/capabilities.json), and
[`capabilities-visible.json`](evidence/samsung-arm64-16k-20260910/host/capabilities-visible.json).

### Native page size in the actual Provider process

After real-Host execution, Provider runtime PID 19422 mapped the native payload
directly from its APK. The executable mapping began at APK offset `0x108000`,
matching the independently audited native ZIP entry. Its executable, read-only,
and writable mappings each reported `KernelPageSize: 16 kB` and
`MMUPageSize: 16 kB`. Provider `pageSizeCompat=0` was observed again.
The Host reported `pageSizeCompat=4`; this run makes no blanket alignment claim
for the Host's unrelated native libraries. The actual mappings and package
observations are in
[`device-runtime-observations.json`](evidence/samsung-arm64-16k-20260910/host/device-runtime-observations.json).

### Toast acknowledgement and visible delivery

The first capability run passed, but its recording showed no Toast. At the same
time, Android logged `Suppressing toast from package org.autojs.autojs6 by user request.`
and the Host's `POST_NOTIFICATION` app-op was `ignore`. This is the distinction
between accepting a Toast request and the platform actually presenting it.

For the temporary Host copy, `pm grant org.autojs.autojs6
android.permission.POST_NOTIFICATIONS` changed the observed app-op to `allow`.
The same capability test was repeated with its existing 8,000 ms Toast hold
argument. A frame extracted at 3.5 seconds from the device recording visibly
shows the AutoJs6 icon and this unique marker:

```text
lua-r5-toast-96de4ff7-c28f-4e4e-9608-d9d7a3e4c302
```

The unannotated frame is archived as
[`toast-visible.png`](evidence/samsung-arm64-16k-20260910/host/toast-visible.png).
The raw video SHA-256 is
`7c07d6a07995ed259a604f1554a87b654cb83cd58bf0fb5ee3edbc651c3f2088`;
the video remains a local build artifact. Platform suppression logs and the
permission transition are retained with the follow-up receipts. The temporary
device recordings were removed after pulling and verifying their hashes.

### Restoration and remaining scope

After testing, the Lua Provider and independent Host harness were uninstalled,
and their package/process absence was checked. The temporary Host and Host-test
copies were also uninstalled, then the original APKs were restored and their
installed hashes rechecked:

| Restored package | Original APK SHA-256 |
|---|---|
| `org.autojs.autojs6` | `ef5c38f62b8cb98f2e5ed81dadeb413ebf0ac373089ab70a6006199090f198d8` |
| `org.autojs.autojs6.test` | `71f96fade33b527aff0b0dfeed157c7395cb5b5a231b2e4d651d3bd9785d09c9` |

This restores the APK identities, not pre-uninstall application data; the Host
was reinstalled with fresh data under the owner's authorization. Other plugin
packages were retained. Exact cleanup and restoration commands are in
[`restore.json`](evidence/samsung-arm64-16k-20260910/host/restore.json).

This closes the previously deferred real-Host development smoke on the named
Samsung ARM64 16 KiB device. Plugin Center visual rendering, release-signed
versionCode 43 artifacts, upgrade/rollback matrices, and long-duration stability
were not part of this follow-up. No runtime source change was required.
