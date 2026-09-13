<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p>
    <img src="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="lua-runtime-ic-launcher" border="0" width="128" />
  </p>

  <h1>AutoJs6 Lua Runtime Plugin</h1>

  <p>Runs standard PUC Lua 5.4.8 scripts in a dedicated isolated process</p>

  <p>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/releases"><img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?label=Release"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/issues"><img alt="GitHub closed issues" src="https://img.shields.io/github/issues/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=A24232&label=Issues"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE"><img alt="GitHub License" src="https://img.shields.io/github/license/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=534BAE&label=License"/></a>
  </p>
</div>

******

### Languages

******

The current README.md is available in the following languages:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-TW.md)
- English [en] # current
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ar.md)

******

### Introduction

******

AutoJs6 itself runs JavaScript. This plugin adds a second scripting language: create a `.lua` file in the AutoJs6 editor and run it, and the source is handed to an independently installed Provider that executes PUC Lua 5.4.8 inside its dedicated `:lua_runtime` process. Scripts receive a reviewed Lua standard-library subset and a small `autojs` bridge, while files, processes, environment variables and dynamic native modules stay out of reach.

```text
application ID: io.github.supermonster003.autojs6.plugin.lua.runtime
plugin / engine / variant: lua-runtime / lua / puc-lua54
discovery actions: org.autojs.plugin.INFO / org.autojs.plugin.lua.RUNTIME
runtime process: :lua_runtime
minimum host build: 5276
```

Both discovery services are protected by the `org.autojs.permission.PLUGIN` signature permission. The host and plugin must use the same certificate; capabilities stay off until protocol negotiation and validation admit them. There is no public APK download yet, so see Build and Project status below.

******

### Features

******

- Runs plain-text Lua 5.4.8 (`.lua`) scripts from the AutoJs6 editor, streams console output live, and returns one scalar result at completion.
- Provides controlled base, string, math, table, utf8 and coroutine libraries; coroutines inherit the same deadline, cancellation and memory accounting.
- Exposes `autojs.console`, `autojs.now()`, a read-only `autojs.arguments`, `autojs.device.info()`, Host-owned `autojs.storage` and end-to-end `autojs.ui.toast()` bridges.
- Supports adjacent module snapshots: `job.lua` can load UTF-8 text modules up to 64 KiB from `job.modules/name.lua`.
- Runs one execution per runtime process with a host-supplied deadline, memory budget, output credits and a fail-stop watchdog.
- Re-verifies exact length, SHA-256 and strict UTF-8 before execution; precompiled or binary Lua chunks are always rejected.
- Ships arm64-v8a and x86_64 native libraries with 16 KiB alignment, plus README, CHANGELOG and Android text in 10 languages.

******

### Quick start

******

**How do I install it?** There is no public APK yet, so build it as described below. `providerDebug` must pair with a debug-signed AutoJs6 using the same certificate; a release Provider must pair with the same-signed release host.

**How do I enable it?** There is no switch. AutoJs6 discovers a same-signature Provider automatically, and Lua has no experimental Boolean property.

**How do I run a script?** Create a file ending in `.lua` in the AutoJs6 editor, write Lua source, and tap run. The console streams live and the host receives the scalar return value at completion.

**Where do I look when it fails?** A host below versionCode 5276 refuses dispatch as `LUA_RUNTIME_UNAVAILABLE/HOST_VERSION_UNSUPPORTED`. A deadline ends as `TIMEOUT`, excess memory as `MEMORY_LIMIT`, and an ungranted Host bridge as `HOST_CAPABILITY`; these are deterministic safety terminals.

******

### Usage example

******

This file-backed example uses the R5 storage and Toast capabilities granted by a matching Host:

```lua
local autojs = require("autojs")

print("Hello from AutoJs6 Lua Runtime", _VERSION)
autojs.console.warn("stderr, ordered with console output")

local started = autojs.now()
local total = 0
for index = 1, 1000 do
    total = total + index
end
autojs.console.info(("sum=%d in %d ms"):format(total, autojs.now() - started))

local device = autojs.device.info()
autojs.console.log("running on: " .. device.manufacturer .. " " .. device.model)

local runCount = (autojs.storage.get("run_count") or 0) + 1
autojs.storage.put("run_count", runCount)
autojs.ui.toast(("Lua run #%d complete"):format(runCount))

return total
```

For reusable code, create `job.modules/` next to the entry script `job.lua`, place `helper.lua` inside it, and call `require("helper")`. V1 module names are flat ASCII identifiers of at most 64 characters; paths, dots, binary modules and C modules are rejected.

******

### Script API

******

`require("autojs")` returns the bridge table. `autojs.console.log/info(text)` writes stdout and `error/warn(text)` writes stderr; global `print(...)` and `warn(text)` use the same controlled stream pair. `autojs.now()` returns Unix epoch milliseconds. `autojs.arguments` is the read-only argument snapshot for this execution. `autojs.device.info()` returns brand, manufacturer, model, device, product and sdkInt. For a stable file-backed script, `autojs.storage.get/put/remove/clear` provides Host-owned persistent values: keys are 1 to 64-byte ASCII identifiers, canonical values are at most 252 KiB, and each principal is limited to 256 keys and 2 MiB; one execution may perform 64 operations and 32 mutations. `autojs.ui.toast(text)` charges at most 4 calls, accepts 1 to 1024 bytes of strict UTF-8, dispatches once and never retries. Host peers that do not grant either negotiated capability fail as `HOST_CAPABILITY`, with no local fallback or retry.

The available libraries are base, string, math, table, utf8 and coroutine. `io`, `os`, `debug`, `package`, `load`, `loadfile`, `dofile`, `string.dump`, `pcall`, `xpcall`, `getmetatable` and `setmetatable` are unavailable by design. The only source loader is text-mode `luaL_loadbufferx(..., "t")`, so binary chunks cannot enter the runtime. See [`docs/native-execution-core.md`](docs/native-execution-core.md) and [`docs/safe-standard-library-subset.md`](docs/safe-standard-library-subset.md) for the exact boundary.

******

### Limits and safety

******

- One script executes per runtime process, with at most two prepared sessions retained and no queue; additional requests fail fast.
- The host sets an end-to-end deadline and memory budget for every run; an instruction hook enforces cancellation and timeout inside tight loops and coroutines.
- Output is bounded by order, credit, 32 KiB chunks and a total ceiling; an infinite print loop terminates deterministically instead of flooding the host.
- Cleanup expiry poisons and terminates the dedicated process before the host binds a fresh one; each fail-stop emits a content-free `AutoJs6LuaWatchdog` event.
- Before a native crash or watchdog kill, only a fixed 20-byte diagnostic is stored: failure kind, phase and an 8-byte source-hash prefix, never script content.

******

### Compatibility

******

Android 24+ (minSdk 24, targetSdk 36), ABIs `arm64-v8a` and `x86_64`, and AutoJs6 versionCode 5276 or newer with the same certificate are required. The x86_64 install, real-Host execution and uninstall matrix is archived for API 24, 31 and 36, plus an API 37 end-to-end smoke. arm64-v8a is built and artifact-gated but has not run on a physical device; defects found in real use are fixed on report.

******

### Build

******

Use JDK 17+, the Android SDK, NDK `28.2.13676358` and CMake `3.22.1`. Build the ordinary development Provider with the repository Gradle Wrapper:

```powershell
.\gradlew.bat :app:assembleProviderDebug
```

#### Build variants

- `providerDebug`: ordinary development build containing both production discovery services.
- `providerRelease`: the only release variant; without external signing material it only produces an unpublished unsigned artifact.
- `nativeTestDebug` / `faultTestDebug`: instrumentation variants with independent application IDs and production discovery physically removed from the merged manifest.
- Only `faultTestDebug` compiles the destructive fault harness; the old `-Pautojs.lua.*.enabled` Boolean switches no longer exist.

#### Signed release build

Use the repository builder with absolute paths to external signing material and a matching same-signature AutoJs6 APK:

```powershell
.\tools\build_runnable_provider.ps1 `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -HostApk '<absolute matching AutoJs6 APK path>'
```

The script requires a clean revision whose commit count matches versionCode, performs a fresh offline rebuild, compares Host and Provider certificates, and runs the strict artifact gate. Its receipt proves signed packaging only and cannot replace device installation or runtime evidence.

******

### Project status

******

Current version `0.1.1-rc.2` is a locally validated signed-packaging candidate, not a public release. The current signed-packaging candidate belongs to clean plugin revision `a0ae189ac8cba042848412a671c91b0b8a7c44e1` (versionCode 43); its universal APK SHA-256 is `93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd`. device/runtime verification deliberately remains `false` in that immutable receipt. The x86_64 split later passed the API 37 real-Host smoke and install, real-Host execution, and Provider uninstall on API 24, 31, and 36. The arm64 physical-device half was descoped on 2026-08-26 together with the seven-day soak; stability now follows fix-on-report. See [`docs/release-candidate-rc2.md`](docs/release-candidate-rc2.md), [`docs/release-candidate-rc2-emulator-smoke.md`](docs/release-candidate-rc2-emulator-smoke.md), [`docs/release-candidate-rc2-api-matrix.md`](docs/release-candidate-rc2-api-matrix.md), and [`docs/public-release-policy.md`](docs/public-release-policy.md). R3 history is in [ROADMAP.md](ROADMAP.md), the R4 evidence ledger in [ROADMAP-R4.md](ROADMAP-R4.md), and the active R5 plan in [ROADMAP-R5.md](ROADMAP-R5.md).

******

### Verification and release engineering

******

Maintainer gates run offline by default to reduce Cloudflare 502/524/529 noise in the development network.

#### Localized documentation

`.readme/` and `.changelog/` hold the shared templates and JSON sources for 10 languages. `.python/generate_markdown.py` generates the 10 READMEs and the 10-language in-APK CHANGELOG set, and writes `zh-Hans` to the root `README.md`. Android UI strings are maintained independently in their `values-*` resource directories. After changing JSON, run:

```powershell
python .\.python\generate_markdown.py
python .\.python\generate_markdown.py --check
```

#### Offline verification gate

```powershell
.\tools\verify_local.ps1
```

This chains the build-ready verifier, the hostile Python suite and `:app:testProviderDebugUnitTest --offline`, then checks XML reports against the single test count in `verification.properties`. CI mirrors the same static and Gradle boundaries.

#### Immutable inputs

The protocol input is exactly three repository-local SHA-256-locked AARs (`common-plugin-api`, `protocol-wire-api`, `lua-runtime-api`). The native input is PUC Lua 5.4.8 with archive SHA-256 `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae`; the verifier recomputes the complete source-tree fingerprint and CMake admits reviewed sources only. See [`protocol/README.md`](protocol/README.md) and [`vendor-lock.json`](app/src/main/cpp/vendor/vendor-lock.json).

#### Release artifact validation

Raw `providerRelease` can build unsigned, but a publishable candidate must read signing material from external absolute paths and run a fresh offline task set:

```powershell
$releaseArgs = @(
    ':app:clean'
    ':app:testProviderDebugUnitTest'
    ':app:assembleProviderRelease'
    '-Pautojs.lua.release.signingPropertiesFile=<absolute sign.properties path>'
    '-Pautojs.lua.release.signingStoreFile=<absolute JKS path>'
    '--rerun-tasks'
    '--offline'
    '--no-daemon'
    '--console=plain'
)
.\gradlew.bat @releaseArgs
```

```powershell
.\tools\verify_release_candidate_artifacts.ps1 `
    -InvocationStartedAtUtc '<UTC start of that Gradle invocation>' `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -SdkRoot '<Android SDK root>'
```

Then run the artifact verifier with the same certificate. It checks the JVM count, signer, split/universal ABI identity, 16 KiB ZIP/ELF alignment, non-debuggability and fault-harness exclusion; the result remains packaging evidence only.

#### Pre-release fault-harness checklist

Before rebuilding a signed candidate, generate both the fault variant and auditable unsigned release intermediates in one clean canonical invocation:

```powershell
$faultStarted = [DateTimeOffset]::UtcNow.ToString(
    'yyyy-MM-ddTHH:mm:ss.ffffffZ'
)
$faultArgs = @(
    ':app:clean'
    ':app:assembleFaultTestDebug'
    ':app:compileProviderReleaseKotlin'
    ':app:processProviderReleaseMainManifest'
    ':app:externalNativeBuildProviderRelease'
    '--rerun-tasks'
    '--offline'
    '--no-daemon'
    '--console=plain'
)
.\gradlew.bat @faultArgs
.\tools\verify_fault_harness_artifacts.ps1 `
    -InvocationStartedAtUtc $faultStarted `
    -SdkRoot '<Android SDK root>'
```

The success marker must be `RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS`: `faultTestDebug` must contain the isolated services and fault JNI symbols, while `providerRelease` must exclude them from BuildConfig, manifest, classes and both ABI native outputs. Separately run `LuaRuntimeFaultRecoveryInstrumentationTest` on a disposable device; the artifact receipt cannot replace that evidence. A missing or failed run is `UNVERIFIED_FAULT_HARNESS`.

******

### Further reading

******

Runtime boundaries: [`docs/native-execution-core.md`](docs/native-execution-core.md), [`docs/coroutine-control-boundary.md`](docs/coroutine-control-boundary.md), [`docs/pcall-boundary-decision.md`](docs/pcall-boundary-decision.md), [`docs/storage-kv-v1.md`](docs/storage-kv-v1.md), [`docs/ui-toast-v1.md`](docs/ui-toast-v1.md). Forward designs: [`docs/module-snapshot-v2.md`](docs/module-snapshot-v2.md), [`docs/result-model-v2.md`](docs/result-model-v2.md), [`docs/execution-statistics-v1.md`](docs/execution-statistics-v1.md). Release evidence: [`docs/host-lifecycle-matrix.md`](docs/host-lifecycle-matrix.md), [`docs/release-upgrade-matrix.md`](docs/release-upgrade-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md).

******

### Release history

******

# v0.1.1

###### 2026/09/11

* `Improvement` Build verification of 16 KB page alignment for 64-bit native libraries, including manifest contract checks and JSON reports
* `Improvement` Add protected host activation, accurate installed-package metadata and signed release collection; standardize localized resources and read-only documentation checks

# v0.1.0-rc.2

###### 2026/08/27

* `Hint` This remains a locally validated signed-packaging candidate: no public tag or GitHub Release exists, and the immutable receipt still says `deviceVerified=false/runtimeVerified=false`
* `Hint` The arm64-v8a physical-device smoke and seven-day production soak were descoped by owner decision; the completed two-day evidence and frozen standard remain archived, while long-run stability follows fix-on-report
* `Feature` Added controlled coroutines, `autojs.now()`, `console.info/warn`, the Provider side of `ui.toast.v1`, crash diagnostics and `AutoJs6LuaWatchdog` events
* `Feature` Implemented negotiated `storage.kv.v1` with file-script-isolated Host persistence, fixed get/put/remove/clear shapes, bounded canonical values and no retry, and completed Host-delivered `ui.toast.v1`
* `Feature` Added text module snapshots, read-only execution arguments and the device-information bridge while carrying deadlines, cancellation, memory and output quotas across every path
* `Feature` Ships arm64-v8a and x86_64 native libraries and archives real-Host x86_64 evidence on API 24, 31, 36 and 37
* `Fix` A tiny deadline expiring before `start()` arrives now yields one deterministic `TIMEOUT/QUEUE` terminal instead of leaving a session without a terminal state
* `Fix` Tightened `math.randomseed` and Host-capability rejection mapping so ungranted calls terminate deterministically as `HOST_CAPABILITY`
* `Improvement` Replaced four Boolean build modes with explicit `providerDebug/providerRelease/nativeTestDebug/faultTestDebug` variants and physically isolated production discovery from the destructive fault harness
* `Improvement` Migrated to the sibling-plugin `.python/generate_markdown.py` + `.readme/` + `.changelog/` convention, with complete documentation in 10 languages and `zh-Hans` as the root README default
* `Improvement` Split R5 out of `ROADMAP-R4.md` into `ROADMAP-R5.md` and removed the obsolete Traditional-Chinese-slot task now superseded by the complete 10-language set
* `Improvement` Added exact logical and OS-level PFD accounting, a one-command offline gate, resilient CI, release artifact validation and fault-harness exclusion audits
* `Dependency` Pins PUC Lua 5.4.8, Android NDK 28.2.13676358 and CMake 3.22.1

# v0.1.0-rc.1

###### 2026/08/13

* `Hint` The first Provider-enabled local candidate was signed and device-tested, but no public tag or release was created
* `Feature` Introduced the independent `:lua_runtime` process, text Lua execution, console output, scalar results and AutoJs6 Binder Provider discovery
* `Fix` Rejected malformed or excessive requests through fail-closed protocol, source digest, UTF-8, deadline, memory and output validation
* `Improvement` Established reviewable evidence for protocol AARs, Lua source, ABIs, 16 KiB alignment, signing and rollback matrices
* `Dependency` Built on standard PUC Lua 5.4.8 and the frozen AutoJs6 Lua protocol 1.0

##### For more releases

* [CHANGELOG.md](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/assets/doc/CHANGELOG-en.md)

******

### License

******

Repository-owned code is distributed under the MIT License. Packaged or staged third-party components also include PUC Lua under MIT, Kotlin and JetBrains annotations under Apache-2.0, statically linked Android NDK LLVM runtime portions under their recorded LLVM terms, and the frozen AutoJs6 protocol APIs under MPL-2.0. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), the preserved license texts, and the exact protocol corresponding source.

******

### Localized resource layout

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
app/src/main/res/values-*/strings.xml
```

`.python/generate_markdown.py` generates the README and in-APK changelog for all 10 languages from JSON sources; edit the JSON sources rather than generated Markdown. Android UI strings are managed in their resource directories.

******

### Links

******

- AutoJs6 project: https://github.com/SuperMonster003/AutoJs6
- Lua project: https://www.lua.org
- Third-party notices: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/THIRD_PARTY_NOTICES.md
- Project license: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/main/docs/16kb.md)
