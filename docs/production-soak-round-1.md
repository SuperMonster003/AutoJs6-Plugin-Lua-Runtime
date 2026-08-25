# Production soak round 1

Status: **ROUND 1 INVALID — EXTERNAL HOST PACKAGE REPLACEMENT**

The frozen standard is documented in
[`production-soak-plan.md`](production-soak-plan.md). Qualification is explicitly
not a production day. Round 1 started on 2026-08-25, but an unrelated deployment
replaced the Host during measured iteration 116. The round is invalid and the
R4-E checkbox remains open.

| Day | Asia/Shanghai date | Iterations | Executions | Runtime PID | FD baseline → final | Result |
| ---: | --- | ---: | ---: | ---: | --- | --- |
| 1 | 2026-08-25 | 115 passed; attempt 116 interrupted | 230 verified (+20 qualification executions) | 12691 | 83 → 83 at last checkpoint and post-failure probe | **INVALID** |
| 2 | not run | — | — | — | — | not run |
| 3 | not run | — | — | — | — | not run |
| 4 | not run | — | — | — | — | not run |
| 5 | not run | — | — | — | — | not run |
| 6 | not run | — | — | — | — | not run |
| 7 | not run | — | — | — | — | not run |

## Qualification evidence

The pre-round qualification used lifecycle-test versionCode 56, SHA-256
`a8eaab0ed4e82e3802ee6088a76a79eef1dc36aca9f769aed5c7055ce3afda4b`.
Ten warmup iterations and ten measured iterations completed; all 20 smoke
markers passed, the Provider PID was 8843, and FD returned from 83 to 83. The
qualification receipt SHA-256 is
`1c4d0e341e7489640d885a1afd9b7e8789f6badb624611199c341a7f2971d570`.
It did not create production state.

## Invalidation evidence

Round state bound Host versionCode 5276 with `lastUpdateTime=2026-08-25
15:23:21`, Provider versionCode 43 with PID 12691, and FD baseline 83. The first
115 measured iterations passed. Attempt 116 returned `shortMsg=Process crashed`
and `INSTRUMENTATION_CODE: 0`; its failure receipt SHA-256 is
`397f85aef1030233c3382314cb76674914141d6ead044d8aefd23397c5d49d6a`, and
the complete instrumentation transcript SHA-256 is
`b6176e0fcb2c8f125e3eb55c66cc284e08426ddef37392fa4a297b31481a0272`.

The events buffer records `am_kill` for the active Host with reason `stop
org.autojs.autojs6 due to installPackageLI` at 15:35:52. Device package state
then showed Host versionCode 5278 with `lastUpdateTime=2026-08-25 15:35:52` and
an unrelated `org.autojs.autojs6.test` update at 15:35:53. The Provider package
remained versionCode 43 with its original 15:23:15 update time; its PID remained
12691 and a post-failure FD probe remained 83. The captured buffers contained
zero `event=lua_runtime_fail_stop`, zero lifecycle failure marker, zero related
ANR, and zero `am_crash` record. This is a package-replacement interruption, not
a Provider watchdog fail-stop, but the frozen rules still invalidate the round.

The first writer revision also failed to persist `status=invalid` because its
PowerShell `File.Replace` backup argument was not portable. The retained failure
receipt preserved the primary error. The executor now uses same-directory
`File.Move(..., overwrite=true)`, captures all-buffer logcat on failure, and
repairs a retained failed day-1 state before allowing any new round. No pass
result is claimed for round 1.
