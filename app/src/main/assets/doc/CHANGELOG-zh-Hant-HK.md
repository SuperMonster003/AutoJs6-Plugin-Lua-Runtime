******

### 變更記錄

******

# v0.1.2

###### 2026/09/19

* `修正` AGP 9.1 構建時的 SDK XML v4 解析警告及 JVM 單元測試組裝任務誤觸發 APK 原生程式庫對齊檢查的問題 (共用構建外掛 1.8.3)
* `改進` 將 compileSdk 與 targetSdk 提升到 37 (Android 17), 插件行為不受新目標版本影響

# v0.1.1

###### 2026/09/11

* `改進` 建置階段校驗 64 位原生程式庫的 16 KB 頁面大小對齊, 檢查 manifest 契約並輸出 JSON 報告
* `改進` 補充受保護的宿主喚醒, 準確安裝包資訊與簽名正式包歸集, 規範多語言資源和唯讀文件檢查
* `改進` 擴展原生 ABI 打包與插件中繼資料至 arm64-v8a, armeabi-v7a, x86 和 x86_64, 同步通用 APK 與各 ABI 獨立 APK

# v0.1.0-rc.2

###### 2026/08/27

* `提示` 目前仍是本機驗證的簽章封裝候選, 尚未建立公開 tag 或 GitHub Release; 不可變回執繼續保留 `deviceVerified=false/runtimeVerified=false`
* `提示` 按 owner 決定裁撤 arm64-v8a 實體裝置冒煙和七日 production soak; 已完成的兩日證據與凍結標準繼續封存, 長期穩定性改為 fix-on-report
* `新增` 加入受控 coroutine, `autojs.now()`, `console.info/warn`, Provider 端 `ui.toast.v1`, crash diagnostic 與 `AutoJs6LuaWatchdog` 事件
* `新增` 協同實作 `storage.kv.v1`: 按檔案指令碼隔離的 Host 持久化, 固定 get/put/remove/clear 形狀, 有界規範值且不重試, 並完成 Host 交付的 `ui.toast.v1`
* `新增` 加入文字模組 snapshot, 唯讀執行參數與裝置資訊橋接, 並讓 deadline, cancel, 記憶體和輸出配額涵蓋所有路徑
* `新增` 提供 arm64-v8a 與 x86_64 原生函式庫, 並封存 API 24, 31, 36, 37 的 x86_64 真實 Host 證據
* `修正` 極小 deadline 在 `start()` 到達前逾時時會穩定產生唯一 `TIMEOUT/QUEUE` 終態, 不再留下無終態 session
* `修正` 收緊 `math.randomseed` 與 Host capability 拒絕映射, 令未授權呼叫穩定結束為 `HOST_CAPABILITY`
* `改進` 以明確 `providerDebug/providerRelease/nativeTestDebug/faultTestDebug` 變體取代四個 Boolean 建置模式, 並實體隔離生產探索與破壞性 fault harness
* `改進` 遷移至兄弟外掛程式統一的 `.python/generate_markdown.py` + `.readme/` + `.changelog/` 約定, 完整提供 10 語言文件並以 `zh-Hans` 作根 README 預設語言
* `改進` 將 R5 從 `ROADMAP-R4.md` 分拆至 `ROADMAP-R5.md`, 移除已由完整十語言成果取代的繁體中文槽位待辦
* `改進` 補齊 PFD 邏輯與 OS 級帳目, 一鍵離線門禁, 韌性 CI, release 制品驗證及 fault-harness 排除審計
* `依賴` 固定 PUC Lua 5.4.8, Android NDK 28.2.13676358 與 CMake 3.22.1

# v0.1.0-rc.1

###### 2026/08/13

* `提示` 首個 Provider-enabled 本機候選已簽章並完成裝置測試, 但沒有建立公開 tag 或 release
* `新增` 首次提供獨立 `:lua_runtime` 程序, 文字 Lua 執行, console, 純量結果與 AutoJs6 Binder Provider 探索
* `修正` 以 fail-closed 協定, 來源摘要, UTF-8, deadline, 記憶體及輸出驗證拒絕畸形或超限要求
* `改進` 建立 protocol AAR, Lua 來源, ABI, 16 KiB 對齊, 簽章和回復矩陣的可覆核證據
* `依賴` 建基於標準 PUC Lua 5.4.8 與凍結的 AutoJs6 Lua protocol 1.0
