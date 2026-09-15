<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p>
    <img src="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="lua-runtime-ic-launcher" border="0" width="128" />
  </p>

  <h1>AutoJs6 Lua 執行環境外掛</h1>

  <p>在獨立隔離處理程序中執行標準 PUC Lua 5.4.8 指令碼</p>

  <p>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/releases"><img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?label=Release"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/issues"><img alt="GitHub closed issues" src="https://img.shields.io/github/issues/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=A24232&label=Issues"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE"><img alt="GitHub License" src="https://img.shields.io/github/license/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=534BAE&label=License"/></a>
  </p>
</div>

******

### 語言 (Languages)

******

目前 README.md 支援以下語言:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-HK.md)
- 繁體中文 (台灣) [zh-Hant-TW] # 目前
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ar.md)

******

### 簡介

******

AutoJs6 本身執行 JavaScript. 本外掛加入第二種指令碼語言: 在 AutoJs6 編輯器建立 `.lua` 檔案並執行, 原始碼便會交給獨立安裝的 Provider, 在專用 `:lua_runtime` 處理程序中的 PUC Lua 5.4.8 上執行. 指令碼可使用經審查的 Lua 標準函式庫子集和小型 `autojs` 橋接, 但無法存取檔案系統, 處理程序, 環境變數或動態原生模組.

```text
application ID: io.github.supermonster003.autojs6.plugin.lua.runtime
plugin / engine / variant: lua-runtime / lua / puc-lua54
discovery actions: org.autojs.plugin.INFO / org.autojs.plugin.lua.RUNTIME
runtime process: :lua_runtime
minimum host build: 5276
```

兩個探索服務均受 `org.autojs.permission.PLUGIN` 簽章權限保護. Host 與外掛必須使用同一憑證; 功能預設關閉, 只有協定協商及驗證通過後才會開放. 目前尚無公開 APK 下載, 請參閱建置與專案狀態章節.

******

### 功能

******

- 從 AutoJs6 編輯器執行純文字 Lua 5.4.8 (`.lua`) 指令碼, 即時輸出 console, 並在完成時傳回一個純量結果.
- 開放 base, string, math, table, utf8 及 coroutine 的受控子集; coroutine 同樣受 deadline, cancel 和記憶體帳目限制.
- 提供 `autojs.console`, `autojs.now()`, 唯讀 `autojs.arguments`, `autojs.device.info()`, Host 持久化 `autojs.storage` 與端到端 `autojs.ui.toast()` 橋接.
- 支援相鄰模組快照: `job.lua` 可從 `job.modules/name.lua` 載入最多 64 KiB 的 UTF-8 文字模組.
- 每個執行環境處理程序一次只執行一個指令碼, 每次執行都有 Host 指定的 deadline, 記憶體預算, 輸出額度與 fail-stop watchdog.
- 執行前重新核對精確長度, SHA-256 與嚴格 UTF-8; 預先編譯或二進位 Lua chunk 一律拒絕.
- 提供 `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` 原生函式庫並符合 16 KiB 對齊; README, CHANGELOG 和 Android 文字涵蓋 10 種語言.

******

### 快速開始

******

**如何安裝?** 目前沒有公開 APK, 請依下文自行建置. `providerDebug` 必須配合同一偵錯憑證簽署的 AutoJs6; release Provider 必須與 release Host 同簽章.

**如何啟用?** 不需要開關. AutoJs6 會自動探索同簽章 Provider; Lua 沒有實驗性 Boolean 屬性.

**如何執行?** 在 AutoJs6 編輯器新增以 `.lua` 結尾的檔案, 寫入 Lua 原始碼並按執行. console 會即時顯示, 完成後 Host 取得純量傳回值.

**失敗時看哪裡?** Host versionCode 低於 5276 時會以 `LUA_RUNTIME_UNAVAILABLE/HOST_VERSION_UNSUPPORTED` 拒絕派發. 逾時為 `TIMEOUT`, 超出記憶體為 `MEMORY_LIMIT`, 呼叫未授權 Host 功能為 `HOST_CAPABILITY`; 這些都是確定性的安全終態.

******

### 使用範例

******

以下檔案型範例使用匹配 R5 Host 授予的持久化儲存與 Toast 功能:

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

需要重複使用程式碼時, 可在入口 `job.lua` 旁建立 `job.modules/`, 將 `helper.lua` 放入其中, 再呼叫 `require("helper")`. V1 模組名稱只接受最長 64 字元的扁平 ASCII 識別字, 不接受路徑, 點號, 二進位模組或 C 模組.

******

### 指令碼 API

******

`require("autojs")` 傳回橋接表. `autojs.console.log/info(text)` 寫入 stdout, `error/warn(text)` 寫入 stderr; 全域 `print(...)` 與 `warn(text)` 使用同一受控雙流. `autojs.now()` 傳回 Unix epoch 毫秒. `autojs.arguments` 是本次執行參數的唯讀快照. `autojs.device.info()` 傳回 brand, manufacturer, model, device, product 與 sdkInt. 對具穩定檔案身份的指令碼, `autojs.storage.get/put/remove/clear` 提供 Host 持久化值: key 為 1 至 64 bytes ASCII 識別碼, 規範值最多 252 KiB, 每個 principal 最多 256 keys / 2 MiB; 每次執行最多 64 次操作與 32 次 mutation. `autojs.ui.toast(text)` 最多計費 4 次, 接受 1 至 1024 bytes 嚴格 UTF-8, 只派發一次且不重試. Host 未授予任一協商功能時穩定得到 `HOST_CAPABILITY`, 沒有本機回退或重試.

可用函式庫為 base, string, math, table, utf8 和 coroutine. `io`, `os`, `debug`, `package`, `load`, `loadfile`, `dofile`, `string.dump`, `pcall`, `xpcall`, `getmetatable` 與 `setmetatable` 均刻意關閉. 唯一原始碼載入器是文字模式 `luaL_loadbufferx(..., "t")`, 因此二進位 chunk 無法進入執行環境. 詳細邊界見 [`docs/native-execution-core.md`](docs/native-execution-core.md) 與 [`docs/safe-standard-library-subset.md`](docs/safe-standard-library-subset.md).

******

### 限制與安全

******

- 每個執行環境處理程序一次執行一個指令碼, 最多保留兩個已準備工作階段且沒有佇列; 額外要求立即失敗.
- Host 為每次執行設定端對端 deadline 與記憶體預算; 指令 hook 在緊密迴圈與 coroutine 中同樣執行取消和逾時.
- 輸出受順序, credit, 32 KiB chunk 與總量限制; 無限列印會確定性終止, 不會淹沒 Host.
- 清理逾時會先 poison 再終止專用處理程序, Host 隨後繫結新處理程序; 每次 fail-stop 發出不含內容的 `AutoJs6LuaWatchdog` 事件.
- native crash 或 watchdog 終止前只儲存固定 20-byte 診斷: failure kind, phase 與 8-byte source-hash 前綴, 絕不儲存指令碼內容.

******

### 相容性

******

支援 Android 24+ (minSdk 24, targetSdk 37), ABI 為 `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, 並要求 AutoJs6 versionCode 5276 或以上及相同簽章. x86_64 已封存 API 24, 31, 36 的安裝, 真實 Host 執行與解除安裝矩陣, 以及 API 37 端對端冒煙. arm64-v8a 會建置並通過成品門禁, 但尚未在實體裝置驗證; 實際使用發現的缺陷按回報修正.

******

### 建置

******

需要 JDK 17+, Android SDK, NDK `28.2.13676358` 與 CMake `3.22.1`. 使用儲存庫內 Gradle Wrapper 建置一般開發 Provider:

```powershell
.\gradlew.bat :app:assembleProviderDebug
```

#### 建置變體

- `providerDebug`: 一般開發建置, 包含兩個正式探索服務.
- `providerRelease`: 唯一 release 變體; 沒有外部簽章材料時只產生不可發布的 unsigned 成品.
- `nativeTestDebug` / `faultTestDebug`: 使用獨立 application ID 的 instrumentation 變體, 在合併 manifest 中實體移除正式探索服務.
- 只有 `faultTestDebug` 編譯破壞性 fault harness; 舊 `-Pautojs.lua.*.enabled` Boolean 開關已移除.

#### 簽章 release 建置

使用儲存庫建置器, 傳入外部簽章材料與同簽章 AutoJs6 APK 的絕對路徑:

```powershell
.\tools\build_runnable_provider.ps1 `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -HostApk '<absolute matching AutoJs6 APK path>'
```

指令碼要求乾淨 revision 與 versionCode/提交數相符, 離線完整重建, 比較 Host/Provider 憑證並執行嚴格成品門禁. 回執只證明簽章封裝, 不可取代裝置安裝與執行證據.

******

### 專案狀態

******

目前版本 `0.1.2-rc.2` 是本機驗證的簽章封裝候選, 尚未公開發布. 候選 revision 為 `a0ae189ac8cba042848412a671c91b0b8a7c44e1`, universal APK SHA-256 為 `93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd`; 不可變回執仍保留 `deviceVerified=false/runtimeVerified=false`. x86_64 隨後通過 API 37 真實 Host 冒煙及 API 24, 31, 36 矩陣. arm64 實體裝置驗證與七日 soak 已於 2026-08-26 裁撤, 後續採用 fix-on-report. 詳見 [`docs/release-candidate-rc2.md`](docs/release-candidate-rc2.md), [`docs/release-candidate-rc2-emulator-smoke.md`](docs/release-candidate-rc2-emulator-smoke.md), [`docs/release-candidate-rc2-api-matrix.md`](docs/release-candidate-rc2-api-matrix.md) 及 [`docs/public-release-policy.md`](docs/public-release-policy.md). R3 見 [ROADMAP.md](ROADMAP.md), R4 見 [ROADMAP-R4.md](ROADMAP-R4.md), R5 見 [ROADMAP-R5.md](ROADMAP-R5.md).

******

### 驗證與發布工程

******

維護者門禁預設離線執行, 以減少開發網路的 Cloudflare 502/524/529 雜訊.

#### 多語言文件

`.readme/` 與 `.changelog/` 保存 10 種語言的 JSON 來源和共用範本; `.python/generate_markdown.py` 產生 10 份 README, APK 內 10 語言 CHANGELOG, 並把 `zh-Hans` 寫入根 `README.md`. Android UI 字串由各 `values-*` 目錄獨立維護. 修改 JSON 後執行:

```powershell
python .\.python\generate_markdown.py
python .\.python\generate_markdown.py --check
```

#### 離線驗證門禁

```powershell
.\tools\verify_local.ps1
```

此指令碼串接 build-ready verifier, Python 敵意套件與 `:app:testProviderDebugUnitTest --offline`, 再依 `verification.properties` 的單一計數核對 XML 報告. CI 使用相同邊界.

#### 不可變輸入

協定輸入恰好為三個儲存庫內 SHA-256 鎖定 AAR. 原生輸入是 archive SHA-256 `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae` 的 PUC Lua 5.4.8; verifier 重算完整來源樹指紋, CMake 只接納已審查來源. 詳見 [`protocol/README.md`](protocol/README.md) 與 [`vendor-lock.json`](app/src/main/cpp/vendor/vendor-lock.json).

#### Release 成品驗證

原始 `providerRelease` 可無簽章建置, 但可發布候選必須從外部絕對路徑讀取簽章材料並執行 fresh 離線任務:

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

再以相同憑證執行成品 verifier. 它核對 JVM 數量, signer, ABI, 16 KiB ZIP/ELF 對齊, non-debuggable 與 fault-harness 排除; 結果仍只屬封裝證據.

#### 預發布 fault-harness 檢查表

重建簽章候選前, 用一次 clean canonical invocation 同時產生 fault 變體及可稽核的 unsigned release 中間成品:

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

成功標記必須是 `RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS`; `faultTestDebug` 必須包含隔離服務及 fault JNI symbol, `providerRelease` 必須排除它們. 另在可拋棄裝置執行 `LuaRuntimeFaultRecoveryInstrumentationTest`; 成品回執不可取代此證據. 未執行即為 `UNVERIFIED_FAULT_HARNESS`.

******

### 延伸閱讀

******

執行邊界: [`docs/native-execution-core.md`](docs/native-execution-core.md), [`docs/coroutine-control-boundary.md`](docs/coroutine-control-boundary.md), [`docs/pcall-boundary-decision.md`](docs/pcall-boundary-decision.md), [`docs/storage-kv-v1.md`](docs/storage-kv-v1.md), [`docs/ui-toast-v1.md`](docs/ui-toast-v1.md). 前向設計: [`docs/module-snapshot-v2.md`](docs/module-snapshot-v2.md), [`docs/result-model-v2.md`](docs/result-model-v2.md), [`docs/execution-statistics-v1.md`](docs/execution-statistics-v1.md). 發布證據: [`docs/host-lifecycle-matrix.md`](docs/host-lifecycle-matrix.md), [`docs/release-upgrade-matrix.md`](docs/release-upgrade-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md).

******

### 變更記錄

******

# v0.1.2

###### 2026/09/15

* `改善` 將 compileSdk 與 targetSdk 提升到 37 (Android 17), 外掛程式行為不受新目標版本影響

# v0.1.1

###### 2026/09/11

* `改善` 建置階段校驗 64 位原生函式庫的 16 KB 頁面大小對齊, 檢查 manifest 契約並輸出 JSON 報告
* `改善` 補充受保護的主程式喚醒, 準確安裝套件資訊與簽署正式套件彙整, 規範多語言資源和唯讀文件檢查
* `改善` 擴充原生 ABI 封裝與外掛中繼資料至 arm64-v8a, armeabi-v7a, x86 和 x86_64, 同步通用 APK 與各 ABI 獨立 APK

# v0.1.0-rc.2

###### 2026/08/27

* `提示` 目前仍是本機驗證的簽章封裝候選, 尚未建立公開 tag 或 GitHub Release; 不可變回執繼續保留 `deviceVerified=false/runtimeVerified=false`
* `提示` 按 owner 決定裁撤 arm64-v8a 實體裝置冒煙和七日 production soak; 已完成的兩日證據與凍結標準繼續封存, 長期穩定性改為 fix-on-report
* `新增` 加入受控 coroutine, `autojs.now()`, `console.info/warn`, Provider 端 `ui.toast.v1`, crash diagnostic 與 `AutoJs6LuaWatchdog` 事件
* `新增` 協同實作 `storage.kv.v1`: 按檔案指令碼隔離的 Host 持久化, 固定 get/put/remove/clear 形狀, 有界規範值且不重試, 並完成 Host 交付的 `ui.toast.v1`
* `新增` 加入文字模組 snapshot, 唯讀執行參數與裝置資訊橋接, 並讓 deadline, cancel, 記憶體和輸出配額涵蓋所有路徑
* `新增` 提供 arm64-v8a 與 x86_64 原生函式庫, 並封存 API 24, 31, 36, 37 的 x86_64 真實 Host 證據
* `修正` 極小 deadline 在 `start()` 到達前逾時時會穩定產生唯一 `TIMEOUT/QUEUE` 終態, 不再留下無終態 session
* `修正` 收緊 `math.randomseed` 與 Host capability 拒絕映射, 讓未授權呼叫穩定結束為 `HOST_CAPABILITY`
* `改善` 以明確 `providerDebug/providerRelease/nativeTestDebug/faultTestDebug` 變體取代四個 Boolean 建置模式, 並實體隔離正式探索與破壞性 fault harness
* `改善` 遷移到兄弟外掛統一的 `.python/generate_markdown.py` + `.readme/` + `.changelog/` 約定, 完整提供 10 語言文件並以 `zh-Hans` 作為根 README 預設語言
* `改善` 將 R5 從 `ROADMAP-R4.md` 分拆至 `ROADMAP-R5.md`, 移除已由完整十語言成果取代的繁體中文槽位待辦
* `改善` 補齊 PFD 邏輯與 OS 級帳目, 一鍵離線門禁, 韌性 CI, release 成品驗證及 fault-harness 排除稽核
* `相依性` 固定 PUC Lua 5.4.8, Android NDK 28.2.13676358 與 CMake 3.22.1

##### 更多版本記錄

* [CHANGELOG.md](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/assets/doc/CHANGELOG-zh-Hant-TW.md)

******

### 授權條款

******

儲存庫自有程式碼採用 MIT License. 第三方元件還包括採用 MIT 的 PUC Lua, 採用 Apache-2.0 的 Kotlin 與 JetBrains annotations, 依記錄 LLVM 條款靜態連結的 Android NDK LLVM runtime 部分, 以及採用 MPL-2.0 的 AutoJs6 協定 API. 詳見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 及完整授權文字.

******

### 多語言資源配置

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
app/src/main/res/values-*/strings.xml
```

`.python/generate_markdown.py` 從 JSON 來源產生全部 10 種語言的 README 與 APK 內變更記錄; 修改文件請編輯 JSON 來源而非產生的 Markdown. Android UI 字串由各資源目錄管理.

******

### 連結

******

- AutoJs6 專案: https://github.com/SuperMonster003/AutoJs6
- Lua 專案: https://www.lua.org
- 第三方聲明: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/THIRD_PARTY_NOTICES.md
- 專案授權條款: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/main/docs/16kb.md)
