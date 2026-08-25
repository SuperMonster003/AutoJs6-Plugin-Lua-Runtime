# Production soak plan

Status: **STANDARD FROZEN — FIRST ROUND NOT COMPLETE**

This plan defines the R4-E production soak boundary for the exact signed rc.2
x86_64 candidate. It deliberately does not claim physical-device coverage and
does not replace the still-open arm64-v8a physical smoke item.

## Frozen pass standard

A passing round consists of **7 consecutive Asia/Shanghai calendar days** on
one continuously booted, dedicated API 36 x86_64 AVD. Each day runs **250**
official Host smoke iterations. Every iteration creates and destroys the real
AutoJs6 Lua engine and performs two Provider executions: `return 7`, followed by
`autojs.device.info()` plus a real Host console callback. This is 500 executions per day and 3,500 executions for a complete round.

The round passes only when all of the following remain true for all seven days:

- the AVD name is `DEX_R1_API36_X64`, `ro.kernel.qemu=1`, API is 36, and every
  device command names one explicit `emulator-*` serial;
- the AVD has an exclusive host-side deployment window: no IDE run,
  `connectedAndroidTest`, package installer, or other Gradle deployment may
  address it while a round is active;
- the emulator boot ID and the Provider `:lua_runtime` PID never change;
- the exact Host, Provider, lifecycle-test APK bytes, versions, and common
  signer remain fixed; no reinstall occurs after day 1;
- after a 10-iteration day-1 warmup, every day starts and ends with the exact
  same `/proc/<runtime-pid>/fd` count as the round baseline;
- all 250 instrumentation invocations emit `LUA_HOST_OFFICIAL_SMOKE_PASS` and
  `INSTRUMENTATION_CODE: -1`;
- logcat contains zero `event=lua_runtime_fail_stop`, zero lifecycle failure
  marker, and zero Host/Provider/lifecycle-related ANR or crash record.

An invocation before the next required date is rejected without advancing the
round. A missed date invalidates it. Any APK/install identity drift, AVD reboot,
runtime PID replacement, FD terminal growth, workload failure, fail-stop, ANR,
or relevant crash also invalidates the round. A new round ID and a fresh day 1
are then required; partial days never count.

A separate ADB server port is not treated as isolation. Local emulator
transports can still be visible to the default ADB server, so exclusivity must be
coordinated at the host. An unexpected package replacement is external
interference, but it still invalidates the round rather than being retried or
discounted.

## Exact round-1 artifacts

| Artifact | Version | SHA-256 |
| --- | ---: | --- |
| AutoJs6 x86_64 Host | 5276 | `813c6be9b051c2eada18b0bbe00acff4abb48facd8e1ddd9ff08d904861d367e` |
| Lua Provider x86_64 rc.2 | 43 | `c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12` |
| Host lifecycle test | recorded at round start | recorded at round start |

The Host source revision is
`b39872e2f1ccc940afcb74a6b95b5458e2fee594`; the Provider candidate source
revision is `a0ae189ac8cba042848412a671c91b0b8a7c44e1`. All APKs must have signer
SHA-256 `31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`.

## Executor and evidence

`tools/run_production_soak.ps1` is fail-closed and accepts only an online,
rooted Android emulator. Root is required solely to read the Provider process's
FD directory. The script never enumerates or selects another device, and it
must never be pointed at a physical serial.

Before round 1, run `-QualificationOnly`. Qualification installs the same exact
artifacts and runs 10 measured iterations, but writes to a separate ignored
directory, cannot create or advance production state, and refuses to run while
a production round is active. A passing qualification is tooling evidence only;
it is not a soak day.

Production evidence lives under ignored
`build/soak/r4e-rc2-x86_64-round-1/`. The atomic `state.json` binds the artifacts,
AVD boot ID, runtime PID, baseline FD count, dates, and daily receipts. Each day
also retains full instrumentation output and all-buffer logcat. Day 2 through
day 7 must invoke the same command on the next calendar date; the script verifies
the state before doing work and never reinstalls those days.

```powershell
.\tools\run_production_soak.ps1 `
    -Serial 'emulator-5564' `
    -HostApk 'D:\absolute\autojs6-v6.8.0-5276-x86_64.apk' `
    -ProviderApk 'D:\absolute\app-x86_64-release.apk' `
    -LifecycleTestApk 'D:\absolute\host-lifecycle-test-debug.apk' `
    -RoundId 'r4e-rc2-x86_64-round-1' `
    -SdkRoot 'E:\.android\sdk'
```

No production soak checkbox may be closed until `state.json` says `complete`,
contains exactly seven consecutive daily records, and the first complete result
has been transcribed into the repository evidence ledger. No day is complete
until its 250-iteration receipt has been written successfully.
