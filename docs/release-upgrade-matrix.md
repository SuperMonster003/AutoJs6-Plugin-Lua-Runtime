# Release upgrade and rollback matrix

This matrix is the local, emulator-only acceptance boundary for the R4-E
Provider upgrade and rollback-path item. It uses the real AutoJs6 package and
the standalone same-signer instrumentation from `host-lifecycle-test`; it does
not substitute a synthetic Host or call the Provider's execution Binder
directly for the compatibility decision.

## Safety and artifact boundary

`tools/verify_release_upgrade_matrix.ps1` accepts only an online
`emulator-*` serial whose `ro.kernel.qemu` property is `1`, API is at least 24,
and ABI inventory contains `x86_64`. It rejects all inputs before device
mutation unless:

- the two Host APKs own `org.autojs.autojs6`, the current Host is exactly 5276,
  and the other Host has a lower versionCode;
- the two Provider APKs own the official package, are exactly
  `0.1.0-rc.1` and `0.1.0-rc.2`, and form a strictly increasing versionCode
  pair;
- both Provider APKs package production discovery as enabled;
- the standalone test APK targets the expected lifecycle package; and
- both Hosts, both Providers, and the test APK have exactly one common current
  signer.

Every installed Host or Provider identity is checked against the input APK's
versionCode and SHA-256. Signing material remains external to this repository;
the matrix consumes already signed APKs and never reads a keystore or password.
The script is destructive only to packages on the explicitly selected
emulator. On failure after mutation starts, it makes a best-effort restoration
of Host 5276, rc.2, and the same-signer test APK.

## Required cases

The matrix emits one `RELEASE_UPGRADE_CASE_PASS` record per required case and a
single aggregate `RELEASE_UPGRADE_MATRIX_PASS` record only after all cases and
the final restoration pass.

1. **rc.1 to rc.2 package replacement.** Install rc.1 from an absent Provider
   state, run two scripts through the real Host engine, then use `adb install
   -r` for rc.2 without an intervening uninstall. The installed package UID and
   `firstInstallTime` must remain unchanged, the device-side APK must equal the
   rc.2 digest, and the real Host smoke must pass again.
2. **rc.2 uninstall and clean reinstall.** Uninstall rc.2, require both package
   path and `:lua_runtime` PID to be absent, install the exact rc.2 APK without
   `-r`, verify its device-side digest, and repeat the real Host smoke.
3. **older-Host rejection.** Downgrade the real Host to the exact lower-version
   APK while rc.2 remains installed. The `incompatible` instrumentation mode
   calls only `LuaPluginScriptEngine.init()`. It requires the outer Host code
   `LUA_RUNTIME_UNAVAILABLE`, the sole provider evaluation rejection
   `HOST_VERSION_UNSUPPORTED`, and records `dispatch=not-entered`; no script
   source or engine `execute()` call exists in that mode. A live Provider
   runtime PID is additionally required, proving that the decision followed a
   real runtime-info probe rather than a missing-package shortcut.
4. **5276 restoration.** Replace the older Host with the exact 5276 APK and run
   the rc.2 real-Host smoke once more. This proves that the rejection was tied
   to Host compatibility rather than residual Provider failure.

The smoke mode verifies unique enabled INFO/RUNTIME services, shared Host and
Provider signing identity, two real `LuaPluginScriptEngine` executions
(`return 7` and `device.info`), and INFO-level console delivery.

## Reproduction shape

Build rc.1 from its frozen clean revision, rc.2 from its candidate revision,
both Host APKs from isolated clean AutoJs6 revisions, and the lifecycle APK
from the clean implementation revision being tested. Then run against a
dedicated disposable x86_64 AVD:

```powershell
.\tools\verify_release_upgrade_matrix.ps1 `
    -Serial 'emulator-5564' `
    -OlderHostApk 'D:\absolute\autojs6-5275.apk' `
    -CurrentHostApk 'D:\absolute\autojs6-5276.apk' `
    -Rc1ProviderApk 'D:\absolute\lua-runtime-rc1-x86_64.apk' `
    -Rc2ProviderApk 'D:\absolute\lua-runtime-rc2-x86_64.apk' `
    -LifecycleTestApk 'D:\absolute\host-lifecycle-test.apk' `
    -OlderHostRevision '0000000000000000000000000000000000000000' `
    -CurrentHostRevision '0000000000000000000000000000000000000000' `
    -Rc1Revision '0497d5061171421e655e0256ef5a1fe1566c5a23' `
    -Rc2Revision '0000000000000000000000000000000000000000' `
    -SdkRoot 'E:\.android\sdk'
```

The zero revisions are shape placeholders and must be replaced by the exact
40-character source revisions. A pass record applies only to the exact APK
digests, revisions, serial, API, ABI inventory, and signer printed by that run.
It is not physical-device or public-release evidence.

## Recorded run

The canonical run completed on 2026-08-25. The compatibility instrumentation
and matrix hardening were frozen at clean revision
`b3cae39f63561cea81392051a7e8b4361cac9129`, whose commit count and lifecycle
APK versionCode were both 50. Before that revision, an audit-only first attempt
stopped before any smoke because API 36 reports `appId=` rather than `userId=`
in `dumpsys package`. Revision `b3cae39f...` added both-name parsing and made
failure recovery unconditionally replace any installed Provider with rc.2.
The corrected 44/44 Python verifier suite and lifecycle lint/assemble gate then
passed before the canonical run.

### Exact artifacts

rc.1 was rebuilt from its frozen clean revision with its own historical gate:
43 JVM tests and all 87 fresh Gradle tasks passed, followed by
`SIGNED_RELEASE_CANDIDATE_ARTIFACT_GATE_PASS`. Both Host APKs were built in an
isolated clone of AutoJs6 with `assembleAppRelease`, then externally 16 KiB
ZIP-aligned and signed. The 5275 build started from the exact older revision;
its normal post-assemble hook changed only the checkout's `BUILD_TIME` after
packaging. That file was restored to its exact committed blob before the clone
was switched. The 5276 build explicitly disabled build-number and build-time
writeback and ended with a clean source checkout.

| Artifact | Source revision / versionCode | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| AutoJs6 5275 x86_64 | `de07b385393b7c9c3155aa84e446234a09282170` / 5275 | 33,305,177 | `9a738fa91973643b7c3b0a2ecd30612237decacb65fd5b15e337c14e45580e48` |
| AutoJs6 5276 x86_64 | `b39872e2f1ccc940afcb74a6b95b5458e2fee594` / 5276 | 33,309,273 | `813c6be9b051c2eada18b0bbe00acff4abb48facd8e1ddd9ff08d904861d367e` |
| Provider 0.1.0-rc.1 x86_64 | `0497d5061171421e655e0256ef5a1fe1566c5a23` / 20 | 666,735 | `2bdd0f37bdc9a8ed365f55def12e500c37423364cd5c16916d8bb9a325e9ce28` |
| Provider 0.1.0-rc.2 x86_64 | `a0ae189ac8cba042848412a671c91b0b8a7c44e1` / 43 | 709,975 | `c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12` |
| Host lifecycle instrumentation | `b3cae39f63561cea81392051a7e8b4361cac9129` / 50 | 977,999 | `0fc069ccfa42a0d18ec57fee535515abf6b8f1c1409847371c56652543bb9c75` |

All five APKs had the single current signer SHA-256
`31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`.

### Emulator cases

Only `emulator-5564` received ADB commands. It was the dedicated
`DEX_R1_API36_X64` AVD, with API 36, qemu property `1`, and ABI inventory
`x86_64,arm64-v8a`.

- `rc1-before-upgrade-96f36c265431492eb9bf5e60ebaf658b` ran the full real-Host
  smoke on versionCode 20. `adb install -r` then installed rc.2 without an
  uninstall; package UID 10229 and `firstInstallTime` were preserved.
  `rc2-after-upgrade-0510cafc4a8b42cf973cff696fa0bd0d` repeated the full smoke
  on versionCode 43. The case emitted `case=rc1-to-rc2`,
  `installReplace=true`, and `smokeBefore=pass smokeAfter=pass`.
- rc.2 uninstall made both package path and `:lua_runtime` PID absent. A clean
  install restored the exact rc.2 device-side digest, and
  `rc2-after-reinstall-0dc0ba45fd48412595bc45327b809bbe` passed the full smoke.
  The case emitted `case=rc2-uninstall-reinstall packageAbsent=true
  runtimeAbsent=true`.
- With rc.2 still installed, Host 5275 run
  `rc2-older-host-ccec32723f8b40d1ad4acc5c9490eb97` reached the real Provider
  runtime-info endpoint at PID 5499, then failed closed during engine
  initialization with outer code `LUA_RUNTIME_UNAVAILABLE`, sole rejection
  `HOST_VERSION_UNSUPPORTED`, and `dispatch=not-entered`. No script execution
  was attempted. The case emitted `case=older-host-rejection`.
- Host 5276 was restored from the exact APK, and
  `rc2-current-host-restored-86de49df38434011bd6c732b52bd40f1` passed two real
  engine executions with `discovery=pass result=pass console=pass`. The final
  marker recorded Host PID 5990, UID 10228, Host 5276, and Provider 43; the
  runtime-info/Provider process remained PID 5499.

The aggregate terminal record was:

```text
RELEASE_UPGRADE_MATRIX_PASS serial=emulator-5564 api=36 abis=x86_64,arm64-v8a olderHostRevision=de07b385393b7c9c3155aa84e446234a09282170 olderHostVersionCode=5275 olderHostSha256=9a738fa91973643b7c3b0a2ecd30612237decacb65fd5b15e337c14e45580e48 currentHostRevision=b39872e2f1ccc940afcb74a6b95b5458e2fee594 currentHostVersionCode=5276 currentHostSha256=813c6be9b051c2eada18b0bbe00acff4abb48facd8e1ddd9ff08d904861d367e rc1Revision=0497d5061171421e655e0256ef5a1fe1566c5a23 rc1VersionCode=20 rc1Sha256=2bdd0f37bdc9a8ed365f55def12e500c37423364cd5c16916d8bb9a325e9ce28 rc2Revision=a0ae189ac8cba042848412a671c91b0b8a7c44e1 rc2VersionCode=43 rc2Sha256=c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12 lifecycleVersionCode=50 lifecycleSha256=0fc069ccfa42a0d18ec57fee535515abf6b8f1c1409847371c56652543bb9c75 signerSha256=31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213 rc1RunId=rc1-before-upgrade-96f36c265431492eb9bf5e60ebaf658b upgradeRunId=rc2-after-upgrade-0510cafc4a8b42cf973cff696fa0bd0d reinstallRunId=rc2-after-reinstall-0dc0ba45fd48412595bc45327b809bbe incompatibleRunId=rc2-older-host-ccec32723f8b40d1ad4acc5c9490eb97 restoreRunId=rc2-current-host-restored-86de49df38434011bd6c732b52bd40f1 outerCode=LUA_RUNTIME_UNAVAILABLE rejection=HOST_VERSION_UNSUPPORTED dispatch=not-entered upgrade=pass uninstallReinstall=pass olderHostRejection=pass currentHostRestore=pass
```

After collecting the restored terminal state, the dedicated AVD was shut down
successfully. No physical Android device was addressed or mutated. This closes
the R4-E upgrade/rollback-path item for the exact artifacts above; it does not
close the separate arm64-v8a physical-device smoke requirement or alter the
canonical rc.2 universal receipt flags.
