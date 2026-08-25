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

No pass is claimed in this section until a clean implementation revision has
been built and the complete matrix has emitted its aggregate pass record.
