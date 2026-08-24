# Host lifecycle matrix

This matrix closes the R4-A Host update and Host uninstall/reinstall peer-death
gap. It is destructive by definition, so the repository-owned orchestrator
accepts only an online `emulator-*` serial whose `ro.kernel.qemu` property is
`1`. It must never be pointed at a physical device.

## Boundary

`host-lifecycle-test` is a standalone test APK, not an Android test source set
of the Lua runtime application. Its manifest declares exactly one
instrumentation whose target package is the real `org.autojs.autojs6` package;
it declares no activity, service, receiver, or provider. Android will inject it
into the Host process only when the test APK and Host have the same signing
identity. The test module consumes the three frozen protocol AARs directly and
does not depend on, package, or modify the runtime application module.

External Host-matching signing material is opt-in through both of these Gradle
properties:

- `autojs.lua.hostLifecycle.signingPropertiesFile`
- `autojs.lua.hostLifecycle.signingStoreFile`

Both paths must be absolute regular files. Omitting both retains ordinary
debug signing for compilation-only checks; providing only one fails the build.
Neither signing material nor its passwords belong in the repository.

## Cases and assertions

The `arm` instrumentation mode binds the production
`LuaRuntimeService` explicitly, calls `getRuntimeInfo()`, creates a real native
`while true do end` execution, and waits for `onStarted`. Both the callback and
Host capability broker Binder objects live in the actual AutoJs6 process and
UID. After logging the run-specific armed marker, the instrumentation blocks
forever. It contains no process-kill primitive; only package replacement or
package uninstall is allowed to terminate that Host process.

`tools/verify_host_lifecycle_matrix.ps1` then runs two independent cases:

1. install a baseline Host and replace it with a strictly higher-version Host
   while the execution is active;
2. start another active execution, uninstall the Host package, prove the Lua
   provider package remains installed, and reinstall the updated Host.

For each case, the script records the live `:lua_runtime` PID before Host death.
After the Host is available again, `verify` binds that same provider process
and immediately executes `return 7`. This proves the dead Host's retained and
active session leases were released instead of returning `BUSY`. It then keeps
the same provider Binder alive for seven seconds, longer than the armed
four-second deadline plus the runtime's two-second cleanup grace, and executes
`return 7` again. Binder death, PID replacement, a stale watchdog kill, a
non-scalar result, or any failed/cancelled terminal causes the case to fail.

The matrix additionally rejects any of the following before device mutation:

- a non-emulator serial, offline target, API below 24, or ABI without x86_64;
- Host APKs which do not form a strictly increasing version pair;
- a package-name mismatch or more than one current signer;
- any signer mismatch among both Host APKs, the provider, and the test APK;
- a provider APK whose packaged discovery resource is not enabled.

## Reproduction shape

Build a native/provider-enabled signed candidate and the lifecycle test APK
with external signing paths, and prepare two same-source, same-signer Host APKs
whose only intended identity difference is an increasing version code. Then
run:

```powershell
.\tools\verify_host_lifecycle_matrix.ps1 `
    -Serial 'emulator-5554' `
    -BaselineHostApk 'D:\absolute\host-5276-x86_64.apk' `
    -UpdatedHostApk 'D:\absolute\host-5277-x86_64.apk' `
    -ProviderApk 'D:\absolute\app-universal-release.apk' `
    -LifecycleTestApk 'D:\absolute\host-lifecycle-test-debug.apk' `
    -HostRevision '0000000000000000000000000000000000000000' `
    -SdkRoot 'E:\.android\sdk'
```

The zero revision above is a shape placeholder and must be replaced by the
exact 40-character Host source revision used for both APKs. Success emits one
`HOST_LIFECYCLE_MATRIX_PASS` record containing all APK SHA-256 values, signer,
versions, device identity, stable runtime PIDs, recovery count, and watchdog
proof duration. The updated Host, signed provider, and test APK remain installed
on that disposable emulator so the recorded terminal state can be inspected.

This matrix is lifecycle evidence for the exact APK identities in its pass
record. It does not publish the provider, change default-off discovery, authorize
physical-device uninstall, or substitute for the separate release upgrade and
rollback matrix in R4-E.
