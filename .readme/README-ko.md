<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p>
    <img src="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="lua-runtime-ic-launcher" border="0" width="128" />
  </p>

  <h1>AutoJs6 Lua 런타임 플러그인</h1>

  <p>전용 격리 프로세스에서 표준 PUC Lua 5.4.8 스크립트를 실행합니다</p>

  <p>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/releases"><img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?label=Release"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/issues"><img alt="GitHub closed issues" src="https://img.shields.io/github/issues/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=A24232&label=Issues"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE"><img alt="GitHub License" src="https://img.shields.io/github/license/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=534BAE&label=License"/></a>
  </p>
</div>

******

### 언어 (Languages)

******

현재 README.md는 다음 언어를 지원합니다:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ja.md)
- 한국어 [ko] # 현재
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ar.md)

******

### 소개

******

AutoJs6 자체는 JavaScript를 실행합니다. 이 플러그인은 두 번째 스크립트 언어를 추가합니다. AutoJs6 편집기에서 `.lua` 파일을 만들고 실행하면 소스가 별도로 설치된 Provider에 전달되고, 전용 `:lua_runtime` 프로세스의 PUC Lua 5.4.8에서 실행됩니다. 스크립트는 검토된 Lua 표준 라이브러리 일부와 작은 `autojs` 브리지를 사용할 수 있지만 파일, 프로세스, 환경 변수, 동적 네이티브 모듈에는 접근할 수 없습니다.

```text
application ID: io.github.supermonster003.autojs6.plugin.lua.runtime
plugin / engine / variant: lua-runtime / lua / puc-lua54
discovery actions: org.autojs.plugin.INFO / org.autojs.plugin.lua.RUNTIME
runtime process: :lua_runtime
minimum host build: 5276
```

두 검색 서비스는 `org.autojs.permission.PLUGIN` 서명 권한으로 보호됩니다. Host와 플러그인은 같은 인증서를 사용해야 하며, 각 capability는 프로토콜 협상과 검증이 승인할 때까지 꺼져 있습니다. 공개 APK는 아직 없으므로 빌드와 프로젝트 상태를 참고하세요.

******

### 기능

******

- AutoJs6 편집기에서 일반 텍스트 Lua 5.4.8 (`.lua`) 스크립트를 실행하고 console을 실시간 전송하며 완료 시 하나의 스칼라 결과를 반환합니다.
- 제어된 base, string, math, table, utf8, coroutine 라이브러리를 제공합니다. coroutine도 deadline, 취소, 메모리 계산을 그대로 적용받습니다.
- `autojs.console`, `autojs.now()`, 읽기 전용 `autojs.arguments`, `autojs.device.info()`, Provider 측 `autojs.ui.toast()`를 제공합니다.
- 인접 모듈 snapshot을 지원합니다. `job.lua`는 `job.modules/name.lua`에서 최대 64 KiB UTF-8 텍스트 모듈을 불러올 수 있습니다.
- 런타임 프로세스마다 한 번에 하나만 실행하며 Host가 지정한 deadline, 메모리 예산, 출력 credit, fail-stop watchdog를 적용합니다.
- 실행 전에 정확한 길이, SHA-256, 엄격한 UTF-8을 다시 검증하며 사전 컴파일 또는 바이너리 Lua chunk는 항상 거부합니다.
- 16 KiB alignment를 갖춘 arm64-v8a와 x86_64 네이티브 라이브러리, 그리고 10개 언어 README, CHANGELOG, Android 텍스트를 제공합니다.

******

### 빠른 시작

******

**어떻게 설치하나요?** 공개 APK는 아직 없으므로 아래 설명대로 빌드하세요. `providerDebug`는 같은 디버그 인증서의 AutoJs6와 함께 사용하고, release Provider는 같은 서명의 release Host와 함께 사용해야 합니다.

**어떻게 활성화하나요?** 스위치는 없습니다. AutoJs6가 같은 서명의 Provider를 자동으로 찾으며 Lua에는 실험적 Boolean 속성이 없습니다.

**어떻게 실행하나요?** AutoJs6 편집기에서 이름이 `.lua`로 끝나는 파일을 만들고 Lua 소스를 작성한 뒤 실행하세요. console이 실시간 표시되고 완료 시 Host가 스칼라 반환값을 받습니다.

**실패하면 무엇을 확인하나요?** versionCode 5276 미만 Host는 `LUA_RUNTIME_UNAVAILABLE/HOST_VERSION_UNSUPPORTED`로 dispatch를 거부합니다. deadline은 `TIMEOUT`, 메모리 초과는 `MEMORY_LIMIT`, 승인되지 않은 Host 브리지는 `HOST_CAPABILITY`로 끝납니다. 모두 결정적인 안전 종료입니다.

******

### 사용 예

******

이 스크립트는 현재 Host가 항상 승인하는 capability만 사용합니다:

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

return total
```

코드를 재사용하려면 엔트리 `job.lua` 옆에 `job.modules/`를 만들고 `helper.lua`를 넣은 뒤 `require("helper")`를 호출하세요. V1 모듈 이름은 최대 64자의 평면 ASCII 식별자이며 경로, 점, 바이너리 모듈, C 모듈은 거부됩니다.

******

### 스크립트 API

******

`require("autojs")`는 브리지 테이블을 반환합니다. `autojs.console.log/info(text)`는 stdout, `error/warn(text)`는 stderr에 기록하며 전역 `print(...)`와 `warn(text)`도 같은 제어 스트림을 사용합니다. `autojs.now()`는 Unix epoch 밀리초를 반환합니다. `autojs.arguments`는 실행 인자의 읽기 전용 snapshot입니다. `autojs.device.info()`는 brand, manufacturer, model, device, product, sdkInt를 반환합니다. `autojs.ui.toast(text)`는 실행당 최대 4회, 엄격한 UTF-8 1에서 1024 bytes, 한 번만 dispatch하고 재시도하지 않습니다. 현재 Host는 아직 승인하지 않으므로 `HOST_CAPABILITY`로 끝납니다.

사용 가능한 라이브러리는 base, string, math, table, utf8, coroutine입니다. `io`, `os`, `debug`, `package`, `load`, `loadfile`, `dofile`, `string.dump`, `pcall`, `xpcall`, `getmetatable`, `setmetatable`은 의도적으로 비활성화했습니다. 유일한 소스 로더는 텍스트 모드 `luaL_loadbufferx(..., "t")`이므로 바이너리 chunk는 들어올 수 없습니다. 자세한 경계는 [`docs/native-execution-core.md`](docs/native-execution-core.md)와 [`docs/safe-standard-library-subset.md`](docs/safe-standard-library-subset.md)를 참고하세요.

******

### 제한과 안전

******

- 프로세스마다 스크립트 하나를 실행하고 준비된 session은 최대 2개이며 queue는 없습니다. 추가 request는 즉시 실패합니다.
- Host가 실행마다 end-to-end deadline과 메모리 예산을 설정하며 instruction hook이 tight loop와 coroutine 안에서도 취소와 timeout을 적용합니다.
- 출력은 순서, credit, 32 KiB chunk, 총량으로 제한됩니다. 무한 print loop는 Host를 범람시키지 않고 결정적으로 종료됩니다.
- cleanup 만료 시 전용 프로세스를 poison한 뒤 종료하고 Host가 새 프로세스에 다시 연결합니다. 각 fail-stop은 내용 없는 `AutoJs6LuaWatchdog` event를 남깁니다.
- native crash 또는 watchdog 종료 전에는 고정 20-byte 진단만 저장합니다. failure kind, phase, 8-byte source-hash prefix만 포함하고 스크립트 내용은 저장하지 않습니다.

******

### 호환성

******

Android 24+ (minSdk 24, targetSdk 36), ABI `arm64-v8a` 또는 `x86_64`, 그리고 같은 인증서의 AutoJs6 versionCode 5276 이상이 필요합니다. x86_64 설치, 실제 Host 실행, 제거 matrix는 API 24, 31, 36에서 보관되었고 API 37 end-to-end smoke도 있습니다. arm64-v8a는 빌드와 artifact gate를 통과했지만 실제 기기에서는 검증하지 않았습니다. 실제 사용에서 보고된 결함은 수정합니다.

******

### 빌드

******

JDK 17+, Android SDK, NDK `28.2.13676358`, CMake `3.22.1`를 사용합니다. 저장소 Gradle Wrapper로 일반 개발 Provider를 빌드하세요:

```powershell
.\gradlew.bat :app:assembleProviderDebug
```

#### 빌드 variant

- `providerDebug`: 두 프로덕션 검색 서비스를 포함한 일반 개발 빌드입니다.
- `providerRelease`: 유일한 release variant입니다. 외부 서명 자료가 없으면 게시할 수 없는 unsigned artifact만 만듭니다.
- `nativeTestDebug` / `faultTestDebug`: 독립 application ID를 사용하고 merged manifest에서 프로덕션 검색을 물리적으로 제거한 instrumentation variant입니다.
- 파괴적 fault harness는 `faultTestDebug`만 컴파일합니다. 이전 `-Pautojs.lua.*.enabled` Boolean switch는 제거되었습니다.

#### 서명된 release 빌드

외부 서명 자료와 같은 서명의 AutoJs6 APK 절대 경로를 저장소 builder에 전달하세요:

```powershell
.\tools\build_runnable_provider.ps1 `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -HostApk '<absolute matching AutoJs6 APK path>'
```

스크립트는 commit count와 versionCode가 같은 clean revision을 요구하고, offline 전체 재빌드, Host/Provider 인증서 비교, 엄격한 artifact gate를 수행합니다. receipt는 서명 package만 증명하며 기기 설치나 runtime 증거를 대신하지 않습니다.

******

### 프로젝트 상태

******

현재 `0.1.0-rc.2`는 로컬에서 검증된 서명 package 후보이며 공개 release가 아닙니다. 후보 revision은 `a0ae189ac8cba042848412a671c91b0b8a7c44e1`, universal APK SHA-256은 `93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd`이고 immutable receipt는 `deviceVerified=false/runtimeVerified=false`를 유지합니다. x86_64 split은 이후 API 37 실제 Host smoke와 API 24, 31, 36 matrix를 통과했습니다. arm64 실제 기기 검증과 7일 soak는 2026-08-26 제외되었고 이후에는 fix-on-report를 따릅니다. [`docs/release-candidate-rc2.md`](docs/release-candidate-rc2.md), [`docs/release-candidate-rc2-emulator-smoke.md`](docs/release-candidate-rc2-emulator-smoke.md), [`docs/release-candidate-rc2-api-matrix.md`](docs/release-candidate-rc2-api-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md)를 참고하세요. R3은 [ROADMAP.md](ROADMAP.md), R4는 [ROADMAP-R4.md](ROADMAP-R4.md), R5는 [ROADMAP-R5.md](ROADMAP-R5.md)에 있습니다.

******

### 검증과 release engineering

******

개발 네트워크의 Cloudflare 502/524/529 잡음을 줄이기 위해 maintainer gate는 기본적으로 offline 실행됩니다.

#### 다국어 문서

`.readme/`와 `.changelog/`에 10개 언어 JSON 소스와 공용 template가 있습니다. `.python/generate_markdown.py`가 10개 README와 APK 내부 CHANGELOG를 생성하고 `zh-Hans`를 루트 `README.md`에 씁니다. Android UI 문자열은 각 `values-*` 디렉터리에서 관리합니다. JSON 변경 후 실행하세요:

```powershell
python .\.python\generate_markdown.py
python .\.python\generate_markdown.py --check
```

#### 오프라인 검증 gate

```powershell
.\tools\verify_local.ps1
```

build-ready verifier, hostile Python suite, `:app:testProviderDebugUnitTest --offline`를 연결하고 `verification.properties`의 단일 test count와 XML report를 비교합니다. CI도 같은 경계를 사용합니다.

#### 불변 입력

프로토콜 입력은 SHA-256으로 잠근 저장소 내부 AAR 3개뿐입니다. 네이티브 입력은 archive SHA-256 `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae`인 PUC Lua 5.4.8입니다. verifier는 전체 source tree 지문을 다시 계산하고 CMake는 검토된 source만 허용합니다. [`protocol/README.md`](protocol/README.md)와 [`vendor-lock.json`](app/src/main/cpp/vendor/vendor-lock.json)을 참고하세요.

#### Release artifact 검증

`providerRelease`는 unsigned로 빌드할 수 있지만 게시 후보는 외부 절대 경로에서 서명 자료를 읽고 fresh offline task를 실행해야 합니다:

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

그 다음 같은 인증서로 artifact verifier를 실행합니다. JVM count, signer, ABI, 16 KiB ZIP/ELF alignment, non-debuggable, fault-harness 제외를 확인하지만 package 증거일 뿐입니다.

#### 출시 전 fault-harness checklist

서명 후보를 다시 빌드하기 전에 한 번의 clean canonical invocation으로 fault variant와 감사 가능한 unsigned release intermediate를 생성하세요:

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

성공 marker는 `RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS`여야 합니다. `faultTestDebug`는 격리 서비스와 fault JNI symbol을 포함하고 `providerRelease`는 이를 제외해야 합니다. 폐기 가능한 기기에서 `LuaRuntimeFaultRecoveryInstrumentationTest`도 실행하세요. receipt는 이를 대신하지 못하며 미실행은 `UNVERIFIED_FAULT_HARNESS`입니다.

******

### 추가 자료

******

실행 경계: [`docs/native-execution-core.md`](docs/native-execution-core.md), [`docs/coroutine-control-boundary.md`](docs/coroutine-control-boundary.md), [`docs/pcall-boundary-decision.md`](docs/pcall-boundary-decision.md), [`docs/ui-toast-v1.md`](docs/ui-toast-v1.md). 향후 설계: [`docs/module-snapshot-v2.md`](docs/module-snapshot-v2.md), [`docs/storage-kv-v1.md`](docs/storage-kv-v1.md), [`docs/result-model-v2.md`](docs/result-model-v2.md), [`docs/execution-statistics-v1.md`](docs/execution-statistics-v1.md). release 증거: [`docs/host-lifecycle-matrix.md`](docs/host-lifecycle-matrix.md), [`docs/release-upgrade-matrix.md`](docs/release-upgrade-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md).

******

### 릴리스 기록

******

# v0.1.0-rc.2

###### 2026/08/27

* `안내` 현재도 로컬 검증된 서명 package 후보입니다. 공개 tag나 GitHub Release는 없고 immutable receipt는 `deviceVerified=false/runtimeVerified=false`를 유지합니다
* `안내` owner 결정으로 arm64-v8a 실제 기기 smoke와 7일 production soak를 제외했습니다. 완료된 2일 증거와 고정 standard는 보관하며 장기 안정성은 fix-on-report를 따릅니다
* `추가` 제어 coroutine, `autojs.now()`, `console.info/warn`, Provider 측 `ui.toast.v1`, crash diagnostic, `AutoJs6LuaWatchdog` event를 추가했습니다
* `추가` 텍스트 module snapshot, 읽기 전용 실행 인자, device 정보 브리지를 추가하고 deadline, 취소, memory, output quota를 모든 경로에서 유지합니다
* `추가` arm64-v8a와 x86_64 native library를 제공하고 API 24, 31, 36, 37의 x86_64 실제 Host 증거를 보관했습니다
* `수정` `start()` 도착 전에 작은 deadline이 만료되면 종료 없는 session 대신 하나의 결정적 `TIMEOUT/QUEUE`를 생성합니다
* `수정` `math.randomseed`와 Host capability 거부 mapping을 강화하여 승인되지 않은 call이 `HOST_CAPABILITY`로 종료됩니다
* `개선` 4개 Boolean build mode를 `providerDebug/providerRelease/nativeTestDebug/faultTestDebug` variant로 교체하고 프로덕션 discovery와 파괴적 fault harness를 물리적으로 격리했습니다
* `개선` 형제 plugin의 `.python/generate_markdown.py` + `.readme/` + `.changelog/` 규약으로 이전하여 10개 언어 문서와 `zh-Hans` 기본 root README를 제공합니다
* `개선` R5를 `ROADMAP-R4.md`에서 `ROADMAP-R5.md`로 분리하고 완전한 10개 언어 세트로 불필요해진 번체 slot 작업을 삭제했습니다
* `개선` PFD 논리/OS 계산, 한 명령 offline gate, resilient CI, release artifact 검증, fault-harness 제외 감사를 추가했습니다
* `의존성` PUC Lua 5.4.8, Android NDK 28.2.13676358, CMake 3.22.1을 고정합니다

# v0.1.0-rc.1

###### 2026/08/13

* `안내` 첫 Provider-enabled 로컬 후보를 서명하고 device test했지만 공개 tag나 release는 만들지 않았습니다
* `추가` 독립 `:lua_runtime` process, 텍스트 Lua 실행, console, scalar result, AutoJs6 Binder Provider discovery를 도입했습니다
* `수정` protocol, digest, UTF-8, deadline, memory, output의 fail-closed 검증으로 잘못되거나 과도한 request를 거부했습니다
* `개선` AAR, Lua source, ABI, 16 KiB alignment, 서명, rollback matrix의 검토 가능한 증거를 확립했습니다
* `의존성` 표준 PUC Lua 5.4.8과 고정된 AutoJs6 Lua protocol 1.0을 기반으로 합니다

##### 더 많은 릴리스

* [CHANGELOG.md](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/assets/doc/CHANGELOG-ko.md)

******

### 라이선스

******

저장소 자체 코드는 MIT License입니다. 타사 구성 요소에는 MIT의 PUC Lua, Apache-2.0의 Kotlin과 JetBrains annotations, 기록된 LLVM 조건의 Android NDK LLVM runtime 정적 링크 부분, MPL-2.0의 AutoJs6 프로토콜 API가 포함됩니다. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)와 전체 라이선스를 참고하세요.

******

### 다국어 resource 구성

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
app/src/main/res/values-*/strings.xml
```

`.python/generate_markdown.py`는 JSON에서 전체 10개 언어 README와 APK 내부 CHANGELOG를 생성합니다. 생성된 Markdown 대신 JSON source를 편집하세요. Android UI 문자열은 각 resource directory에서 관리합니다.

******

### 링크

******

- AutoJs6 프로젝트: https://github.com/SuperMonster003/AutoJs6
- Lua 프로젝트: https://www.lua.org
- 타사 고지: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/THIRD_PARTY_NOTICES.md
- 프로젝트 라이선스: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE
