# Lua Provider 0.1.0-rc.2 x86_64 emulator smoke

Status: **X86_64 EMULATOR VERIFIED — ARM64 PHYSICAL DEVICE PENDING**

Evidence date: 2026-08-25 (Asia/Shanghai)

## Scope and result

This record is the x86_64 half of the R4-E installation and real-Host smoke
matrix. On the API 37 16 KiB `emulator-5554`, the exact signed rc.2 x86_64 APK
was installed, rediscovered through the Host-side Plugin Center INFO/RUNTIME
service contract, and exercised twice through AutoJs6's real
`LuaPluginScriptEngine`. Scalar result, `device.info`, and INFO-level console
feedback all passed.

The arm64-v8a physical-device half was not run. Physical devices were not
touched. Consequently, the combined R4-E checkbox remains open, and the
canonical universal-candidate receipt retains `deviceVerified=false` and
`runtimeVerified=false`.

## Exact artifacts and identities

| Role | Identity | Bytes | SHA-256 |
|---|---|---:|---|
| Provider under test | rc.2 x86_64, versionCode 43, build revision `a0ae189ac8cba042848412a671c91b0b8a7c44e1` | 709,975 | `c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12` |
| Host smoke instrumentation | versionCode 45, clean implementation revision `84dc0a24980deb43d4199bfecee3168e4401da78` | 975,243 | `705f1fb56127d807b8c7fcf759419de5f29592f4affd500444c730357f9ad762` |
| Installed AutoJs6 Host | 6.8.0, versionCode 5276 | device image | `fd9fc6ba3ccc34f977ae2925745e1f525d0522c2a300ce8f109426cd5e57c14f` |

The Provider and instrumentation APKs each had exactly one signer, certificate
SHA-256
`31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`.
The smoke also required Android `PackageManager.checkSignatures` to return
`SIGNATURE_MATCH` for the installed Host and Provider before Lua dispatch.

The installed Host digest is intentionally recorded as observed. It differs
from the Host comparison APK listed in the packaging receipt. That comparison
APK was explicitly limited to version/signer comparison and never carried a
clean-Host provenance claim. The installed Host still met the two dispatch
requirements enforced here: versionCode 5276 and the Provider's signer.

## Device boundary

| Property | Observed value |
|---|---|
| Explicit ADB serial | `emulator-5554` |
| Emulator guard | `ro.kernel.qemu=1` |
| Model | `sdk_gphone16k_x86_64` |
| Android | 17 / API 37 |
| Primary ABI | `x86_64` |
| ABI list | `x86_64,arm64-v8a` |
| Page size | 16,384 bytes |

Every mutating ADB command included `-s emulator-5554`. No unscoped install,
uninstall, test, or shell command was used.

## Installation and byte-for-byte check

Immediately before the final run, the local split was hashed and then installed
again, followed by the clean-revision smoke APK:

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-x86_64-release.apk
adb -s emulator-5554 install -r -t host-lifecycle-test/build/outputs/apk/debug/host-lifecycle-test-debug.apk
```

Both commands returned `Success`. Device-side `sha256sum` over each installed
`base.apk` reproduced the local hashes above exactly. Replacing the Provider
ended runtime PID 5238 as expected; the post-install smoke bound a new runtime
PID 6150, which remained alive after both executions.

## Real Host execution

The independently packaged, same-signer instrumentation has
`android:targetPackage="org.autojs.autojs6"`, so it ran inside the actual Host
process/UID. Before constructing the engine, it required exactly one enabled
and exported service for each of `org.autojs.plugin.INFO` and
`org.autojs.plugin.lua.RUNTIME`. Both had to belong to the rc.2 package, run in
`io.github.supermonster003.autojs6.plugin.lua.runtime:lua_runtime`, require the
frozen plugin permission, and match the Host signer.

It then loaded the Host's own `LuaPluginScriptEngine` and `LuaFileSource` and
performed two sequential executions:

1. `return 7` returned the protocol `LuaValue.Int64Value(7)`.
2. A script loaded `autojs`, validated the fixed shape returned by
   `autojs.device.info()`, emitted a unique marker with
   `autojs.console.log(...)`, and returned SDK level 37. The engine result was
   `LuaValue.Int64Value(37)`, and the Host `ConsoleImpl` received the marker at
   Android `Log.INFO` level.

`engine.destroy()` was mandatory; any cleanup exception would have failed the
instrumentation rather than being suppressed.

## Final receipt

Run ID: `rc2emu-install-20260825051216`

```text
INSTALL_PROVIDER Success
INSTALL_TEST Success
INSTRUMENTATION_RESULT: console=pass
INSTRUMENTATION_RESULT: discovery=pass
INSTRUMENTATION_RESULT: executions=2
INSTRUMENTATION_RESULT: hostVersionCode=5276
INSTRUMENTATION_RESULT: providerVersionCode=43
INSTRUMENTATION_RESULT: result=pass
INSTRUMENTATION_RESULT: runId=rc2emu-install-20260825051216
INSTRUMENTATION_RESULT: stream=LUA_HOST_OFFICIAL_SMOKE_PASS runId=rc2emu-install-20260825051216
INSTRUMENTATION_CODE: -1
```

The matching Android log receipt was:

```text
LUA_HOST_OFFICIAL_SMOKE_PASS runId=rc2emu-install-20260825051216 hostPid=6122 hostUid=10298 hostVersionCode=5276 providerVersionCode=43 executions=2 discovery=pass result=pass console=pass
```

This evidence proves only the exact rc.2 x86_64 split on the named emulator. By
itself it does not prove arm64-v8a installation, visual Plugin Center UI
rendering, API 24/31/36 compatibility, upgrade/rollback combinations,
publication, or production soak. The API 24/31/36 matrix was subsequently
proved and archived separately in
[`release-candidate-rc2-api-matrix.md`](release-candidate-rc2-api-matrix.md).
