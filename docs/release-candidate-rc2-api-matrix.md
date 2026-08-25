# Lua Provider 0.1.0-rc.2 API 24/31/36 compatibility matrix

Status: **COMPLETE — API 24 / 31 / 36**

Evidence date: 2026-08-25 (Asia/Shanghai)

## Scope

This record proves the current target-aligned R4-E compatibility matrix for the
exact rc.2 x86_64 Provider split. API 24 (minSdk), API 31, and API 36
(targetSdk) each completed the same sequence on a dedicated x86_64 emulator:

1. install AutoJs6 5276, the exact Provider, and the same-signer Host smoke APK;
2. discover exactly one valid INFO service and one valid RUNTIME service;
3. execute two scripts through the real Host `LuaPluginScriptEngine` and verify
   scalar result, `device.info`, and INFO-level console feedback; and
4. uninstall the Provider and prove both its package and `:lua_runtime` process
   are absent.

Physical devices were not touched. This matrix does not close the separate
arm64-v8a physical-device smoke requirement.

## Compatibility-harness correction

The first preflight audit found that the new smoke harness used
`Method.getParameterCount()`, an API 26 reflection method. Calling it on API 24
would have produced a harness `NoSuchMethodError` unrelated to the Provider.
Clean revision `52e41233705d20275945b88589b501af4e4a36c1` replaced it with the
minSdk-safe `method.parameterTypes.size`, added a hostile static regression,
and passed 44 Python tests, 59 JVM tests, `:host-lifecycle-test:lintDebug`, and
`:host-lifecycle-test:assembleDebug` before any matrix run.

## Exact artifacts

| Role | Version / revision | Bytes | SHA-256 |
|---|---|---:|---|
| AutoJs6 Host universal APK | 6.8.0 / versionCode 5276 | 34,583,727 | `0303d66688beff27ed512b952b084f409b349abb908b049d64157ec07c6ceeb4` |
| Provider x86_64 APK | 0.1.0-rc.2 / versionCode 43 / `a0ae189ac8cba042848412a671c91b0b8a7c44e1` | 709,975 | `c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12` |
| Host smoke APK | 0.1.0-rc.2 / versionCode 47 / `52e41233705d20275945b88589b501af4e4a36c1` | 975,147 | `19a4a0f0ffae07b08682f87a50e8c6625bfeeab737b51419ecf23f084dceb59f` |

All three local APKs used the same certificate SHA-256
`31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`.
Every smoke run also required installed Host/Provider signature equality through
Android `PackageManager` before dispatch.

## Device matrix

The three AVDs were started one at a time in hidden mode on the explicitly
allocated serial `emulator-5564`. Each reported `ro.kernel.qemu=1`; each was
shut down and confirmed absent before the next AVD reused that serial.

| API role | AVD | Android / ABI | Page size | Build fingerprint |
|---|---|---|---:|---|
| API 24 / minSdk | `DEX_R1_API24_X64` | 7.0 / `x86_64,x86` | unavailable from this image | `Android/sdk_phone_x86_64/generic_x86_64:7.0/NYC/4174735:userdebug/test-keys` |
| API 31 | `AVD_API_31_Play` | 12 / `x86_64,arm64-v8a` | 4,096 | `google/sdk_gphone64_x86_64/emulator64_x86_64_arm64:12/SE1A.211212.001.B1/8023802:user/release-keys` |
| API 36 / targetSdk | `DEX_R1_API36_X64` | 16 / `x86_64,arm64-v8a` | 4,096 | `google/sdk_gphone64_x86_64/emu64xa:16/BE4B.251210.005/14574095:userdebug/dev-keys` |

The API 24 and API 31 AVDs contained none of the three target packages before
installation. The API 36 AVD already contained AutoJs6, but neither Provider
nor smoke APK; AutoJs6 was replaced in place by the exact Host artifact above.

## Common execution assertions

Every run pinned `expectedHostVersionCode=5276` and
`expectedProviderVersionCode=43`. The smoke then required:

- one enabled/exported `org.autojs.plugin.INFO` service and one enabled/exported
  `org.autojs.plugin.lua.RUNTIME` service in the official package and isolated
  `:lua_runtime` process, protected by the frozen plugin permission;
- same-signature Host/Provider packages;
- `return 7` mapping to `LuaValue.Int64Value(7)`;
- fixed-shape `autojs.device.info()` with `sdkInt` exactly equal to the device
  API level; and
- a unique `autojs.console.log` marker appearing in the real Host
  `ConsoleImpl` at `Log.INFO`.

`engine.destroy()` was mandatory. After the pass receipt, Provider uninstall
had to return `Success`; `pm path` and `pidof ...:lua_runtime` then both had to
be empty.

## Results and receipts

| API | Run ID | Host PID / UID | Runtime PID after smoke | Install | Discovery/result/console | Uninstall |
|---:|---|---|---:|---|---|---|
| 24 | `rc2api24-20260825052417` | 3045 / 10062 | 3066 | pass | pass / pass / pass | package absent, runtime absent |
| 31 | `rc2api31-20260825052654` | 6412 / 10147 | 6438 | pass | pass / pass / pass | package absent, runtime absent |
| 36 | `rc2api36-20260825052833` | 9844 / 10228 | 9860 | pass | pass / pass / pass | package absent, runtime absent |

The three Android log receipts were:

```text
LUA_HOST_OFFICIAL_SMOKE_PASS runId=rc2api24-20260825052417 hostPid=3045 hostUid=10062 hostVersionCode=5276 providerVersionCode=43 executions=2 discovery=pass result=pass console=pass
LUA_HOST_OFFICIAL_SMOKE_PASS runId=rc2api31-20260825052654 hostPid=6412 hostUid=10147 hostVersionCode=5276 providerVersionCode=43 executions=2 discovery=pass result=pass console=pass
LUA_HOST_OFFICIAL_SMOKE_PASS runId=rc2api36-20260825052833 hostPid=9844 hostUid=10228 hostVersionCode=5276 providerVersionCode=43 executions=2 discovery=pass result=pass console=pass
```

Each instrumentation command returned `INSTRUMENTATION_CODE: -1` with
`executions=2`, `discovery=pass`, `result=pass`, and `console=pass`. Each
uninstall ended with the independently checked receipt:

```text
UNINSTALL_PROVIDER Success packageAbsent=true runtimeAbsent=true
```

The API 24 and API 31 Host/test packages installed from an initially absent
state were removed afterward. On API 36, the newly installed smoke APK was
removed while the pre-existing Host package was retained. All three dedicated
AVD processes were then shut down. No API-specific Provider compatibility defect
was observed.

This record does not prove the arm64-v8a physical-device smoke, rc.1-to-rc.2
upgrade/rollback combinations, behavior with pre-5276 Hosts, visual Plugin
Center UI rendering, publication, or production soak.
