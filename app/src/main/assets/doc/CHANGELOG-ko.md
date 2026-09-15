******

### 릴리스 기록

******

# v0.1.1

###### 2026/09/11

* `개선` 64비트 네이티브 라이브러리의 16 KB 페이지 정렬을 빌드 시 검증, manifest 계약 검사 및 JSON 보고서 지원
* `개선` 보호된 호스트 활성화, 정확한 설치 패키지 정보, 서명된 릴리스 수집을 추가하고 다국어 리소스와 읽기 전용 문서 검사를 통일
* `개선` 네이티브 ABI 패키징과 플러그인 메타데이터를 arm64-v8a, armeabi-v7a, x86, x86_64로 확장하고 범용 APK와 ABI별 APK를 일치시킴

# v0.1.0-rc.2

###### 2026/08/27

* `안내` 현재도 로컬 검증된 서명 package 후보입니다. 공개 tag나 GitHub Release는 없고 immutable receipt는 `deviceVerified=false/runtimeVerified=false`를 유지합니다
* `안내` owner 결정으로 arm64-v8a 실제 기기 smoke와 7일 production soak를 제외했습니다. 완료된 2일 증거와 고정 standard는 보관하며 장기 안정성은 fix-on-report를 따릅니다
* `추가` 제어 coroutine, `autojs.now()`, `console.info/warn`, Provider 측 `ui.toast.v1`, crash diagnostic, `AutoJs6LuaWatchdog` event를 추가했습니다
* `추가` file script별 Host 영속화, 고정 get/put/remove/clear shape, bounded canonical value, no retry를 적용한 negotiated `storage.kv.v1`과 Host 전달 `ui.toast.v1`을 구현했습니다
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
