# Production soak round 2

Status: **ROUND 2 IN PROGRESS — DAY 1/7 PASSED**

Round 1 was invalidated by an external Host package replacement. Round 2 uses
the same frozen rc.2 Host and Provider artifacts, a new lifecycle-test identity,
one new AVD boot ID, and an explicitly reserved host-side deployment window.
Qualification is not a production day. The R4-E checkbox remains open until all
seven consecutive days pass.

| Day | Asia/Shanghai date | Iterations | Executions | Runtime PID | FD baseline → final | Result |
| ---: | --- | ---: | ---: | ---: | --- | --- |
| 1 | 2026-08-25 | 250 | 500 | 8080 | 83 → 83 | **PASS** |
| 2 | pending (due 2026-08-26) | 250 | 500 | pending | pending | pending |
| 3 | pending | 250 | 500 | pending | pending | pending |
| 4 | pending | 250 | 500 | pending | pending | pending |
| 5 | pending | 250 | 500 | pending | pending | pending |
| 6 | pending | 250 | 500 | pending | pending | pending |
| 7 | pending | 250 | 500 | pending | pending | pending |

## Qualification and artifact binding

Round 2 uses lifecycle-test versionCode 57, 977,999 bytes, SHA-256
`7f5bc019367e46aa4533bb6ae71ec7f339cea20d1efb2a988f7f2060d3e07604`.
Its signer is the frozen common signer
`31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`.
The preceding qualification completed 10 warmup plus 10 measured iterations,
20 executions, on PID 4345 with FD 83 → 83. Its receipt SHA-256 is
`9d40666b8f6fedcb2bd5a77dc647fe6d63d4cdb098f24ffdfeccd970fa2246f3`;
qualification did not create or advance production state.

The production state binds the exact x86_64 Host and Provider SHA-256 values
`813c6be9b051c2eada18b0bbe00acff4abb48facd8e1ddd9ff08d904861d367e`
and `c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12`.
It also binds API 36 AVD `DEX_R1_API36_X64`, boot ID
`47e07ea5-0187-4553-871a-6c713deab71c`, and explicit serial
`emulator-5600`.

## Day 1 evidence — 2026-08-25

Day 1 ran from `2026-08-25T07:55:46.1075655Z` through
`2026-08-25T08:20:29.4192999Z`. Ten warmup iterations established Provider PID
8080 and FD baseline 83. All 250 measured iterations then passed, producing 500
verified Lua executions. The instrumentation transcript contains exactly 260
`LUA_HOST_OFFICIAL_SMOKE_PASS` markers (warmup plus measured) and no lifecycle
failure marker.

Every periodic FD sample at iterations 0, 25, …, 250 was 83. Both the initial
and final stable observations were `83,83,83`. Runtime PID remained 8080. Host,
Provider, and lifecycle `lastUpdateTime` values remained respectively 15:55:50,
15:55:45, and 15:55:50. The receipt reports zero fail-stop, zero related ANR,
zero related crash, and zero instrumentation failure.

| Evidence | Bytes | SHA-256 |
| --- | ---: | --- |
| Day-1 receipt | 4,377 | `6b7ce09b221c3165428adff234cede20b7bb61b5aad215a3e491c180ebe3d4d8` |
| Day-1 instrumentation transcript | 134,820 | `f4fe337b65942d98749451f4e30f39fc2cb89995f0b23bdde690dc35db11c4fa` |
| Day-1 all-buffer logcat | 12,129,089 | `dcc98a84f346ade21dbf8a6c7c0afd01814c15bf1ea55c4fa50cf1c2e1c518b9` |
| State immediately after Day 1 | 3,675 | `f76d02dad57ce8516910b7908c0fcade041b94599bc2ebee2e7e7180f45fd736` |

The atomic state is `in_progress`, contains exactly one completed daily record,
and schedules Day 2 for 2026-08-26 Asia/Shanghai. The AVD must remain booted and
the versionCode 57 lifecycle APK must not be rebuilt or replaced. This is a
valid first day, not a complete production soak; no complete-round pass is
claimed and the R4-E checkbox remains open.
