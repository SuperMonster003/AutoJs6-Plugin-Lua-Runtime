<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p>
    <img src="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="lua-runtime-ic-launcher" border="0" width="128" />
  </p>

  <h1>AutoJs6 Lua 运行时插件</h1>

  <p>在独立隔离进程中运行标准 PUC Lua 5.4.8 脚本</p>

  <p>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/releases"><img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?label=Release"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/issues"><img alt="GitHub closed issues" src="https://img.shields.io/github/issues/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=A24232&label=Issues"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE"><img alt="GitHub License" src="https://img.shields.io/github/license/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=534BAE&label=License"/></a>
  </p>
</div>

******

### 语言 (Languages)

******

当前 README.md 支持以下语言:

- 简体中文 [zh-Hans] # 当前
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ar.md)

******

### 简介

******

AutoJs6 本身运行 JavaScript. 本插件为它增加第二种脚本语言: 在 AutoJs6 编辑器中创建 `.lua` 文件并运行, 源码就会交给独立安装的 Provider, 在专用 `:lua_runtime` 进程中的 PUC Lua 5.4.8 上执行. 脚本可使用经过审查的 Lua 标准库子集和小型 `autojs` 桥, 但无法访问文件系统, 进程, 环境变量或动态原生模块.

```text
application ID: io.github.supermonster003.autojs6.plugin.lua.runtime
plugin / engine / variant: lua-runtime / lua / puc-lua54
discovery actions: org.autojs.plugin.INFO / org.autojs.plugin.lua.RUNTIME
runtime process: :lua_runtime
minimum host build: 5276
```

两个发现服务都受 `org.autojs.permission.PLUGIN` 签名权限保护. 宿主与插件必须使用同一证书; 能力默认关闭, 只有协议协商与验证通过后才会开放. 当前尚无公开 APK 下载, 请参阅构建与项目状态章节.

******

### 功能

******

- 从 AutoJs6 编辑器运行纯文本 Lua 5.4.8 (`.lua`) 脚本, 实时输出 console, 并在结束时返回一个标量结果.
- 开放 base, string, math, table, utf8 与 coroutine 的受控子集; 协程同样受 deadline, cancel 与内存账目约束.
- 提供 `autojs.console`, `autojs.now()`, 只读 `autojs.arguments`, `autojs.device.info()`, Host 持久化 `autojs.storage` 与端到端 `autojs.ui.toast()` 桥.
- 支持同目录模块快照: `job.lua` 可从 `job.modules/name.lua` 加载最多 64 KiB 的 UTF-8 文本模块.
- 每个运行时进程一次只执行一个脚本, 每次执行都有宿主给定的 deadline, 内存预算, 输出额度与 fail-stop watchdog.
- 源码在执行前按精确长度, SHA-256 和严格 UTF-8 复核; 预编译或二进制 Lua chunk 一律拒绝.
- 提供 arm64-v8a 与 x86_64 原生库并满足 16 KiB 对齐; README, CHANGELOG 与 Android 文本覆盖 10 种语言.

******

### 快速上手

******

**怎么装?** 当前没有公开 APK, 请先按下文自行构建. `providerDebug` 必须配合同一调试证书签名的 AutoJs6; release Provider 必须与 release 宿主同签名.

**怎么启用?** 无需开关. AutoJs6 会自动发现同签名 Provider; Lua 没有实验性 Boolean 属性.

**怎么跑?** 在 AutoJs6 编辑器中新建以 `.lua` 结尾的文件, 写入 Lua 源码并点击运行. console 会实时显示, 执行结束后宿主接收标量返回值.

**出错了看哪里?** 宿主 versionCode 低于 5276 时会以 `LUA_RUNTIME_UNAVAILABLE/HOST_VERSION_UNSUPPORTED` 拒绝派发. 超时为 `TIMEOUT`, 超出内存为 `MEMORY_LIMIT`, 调用未授权 Host 能力为 `HOST_CAPABILITY`; 这些都是确定性的安全终态.

******

### 使用示例

******

下面的文件型示例使用匹配 R5 Host 授予的持久化存储与 Toast 能力:

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

需要复用代码时, 可为入口 `job.lua` 创建同级目录 `job.modules/`, 将 `helper.lua` 放入其中, 再用 `require("helper")` 加载. V1 模块名只能是最长 64 字符的扁平 ASCII 标识符, 不接受路径, 点号, 二进制模块或 C 模块.

******

### 脚本 API

******

`require("autojs")` 返回桥接表. `autojs.console.log/info(text)` 写 stdout, `error/warn(text)` 写 stderr; 全局 `print(...)` 与 `warn(text)` 走同一受控双流. `autojs.now()` 返回 Unix epoch 毫秒. `autojs.arguments` 是本次执行参数的只读快照. `autojs.device.info()` 返回 brand, manufacturer, model, device, product 与 sdkInt. 对具有稳定文件身份的脚本, `autojs.storage.get/put/remove/clear` 提供 Host 持久化值: key 是 1 至 64 bytes ASCII 标识符, 规范值最多 252 KiB, 每个 principal 最多 256 keys / 2 MiB; 每次执行最多 64 次操作与 32 次 mutation. `autojs.ui.toast(text)` 最多计费 4 次, 接受 1 至 1024 bytes 严格 UTF-8, 只派发一次且不重试. Host 未授予任一协商能力时稳定得到 `HOST_CAPABILITY`, 没有本地回退或重试.

可用标准库为 base, string, math, table, utf8 和 coroutine. `io`, `os`, `debug`, `package`, `load`, `loadfile`, `dofile`, `string.dump`, `pcall`, `xpcall`, `getmetatable` 与 `setmetatable` 均有意关闭. 唯一源码加载器是文本模式 `luaL_loadbufferx(..., "t")`, 因而二进制 chunk 无法进入运行时. 详细边界见 [`docs/native-execution-core.md`](docs/native-execution-core.md) 与 [`docs/safe-standard-library-subset.md`](docs/safe-standard-library-subset.md).

******

### 运行限制与安全边界

******

- 每个运行时进程一次执行一个脚本, 最多保留两个已准备会话且没有队列; 额外请求立即失败.
- 宿主为每次运行设置端到端 deadline 与内存预算; 指令 hook 在紧循环和协程内同样执行 cancel 与 timeout.
- 输出按顺序, credit, 32 KiB chunk 与总量限制; 无限打印会确定性结束脚本, 不会淹没宿主.
- 清理超时会先 poison 再终止专用进程, 宿主随后绑定新进程; 每次 fail-stop 写入不含脚本内容的 `AutoJs6LuaWatchdog` 事件.
- native crash 或 watchdog 终止前只保存固定 20-byte 诊断记录: failure kind, phase 与 8-byte source-hash 前缀, 不保存脚本正文.

******

### 兼容性

******

支持 Android 24+ (minSdk 24, targetSdk 36), ABI 为 `arm64-v8a` 与 `x86_64`, 要求 AutoJs6 versionCode 5276 或更高且签名一致. x86_64 已归档 API 24, 31, 36 的安装, 真实宿主执行与卸载矩阵, 以及 API 37 端到端冒烟. arm64-v8a 会构建并通过制品门禁, 但未在物理设备上验证; 实际使用发现的问题按报告修复.

******

### 构建

******

需要 JDK 17+, Android SDK, NDK `28.2.13676358` 与 CMake `3.22.1`. 使用仓库内 Gradle Wrapper 构建普通开发 Provider:

```powershell
.\gradlew.bat :app:assembleProviderDebug
```

#### 构建变体

- `providerDebug`: 普通开发构建, 包含两个生产发现服务.
- `providerRelease`: 唯一 release 变体; 没有外部签名材料时只产生不可发布的 unsigned 制品.
- `nativeTestDebug` / `faultTestDebug`: 独立 application ID 的 instrumentation 变体, 合并 manifest 中物理移除生产发现服务.
- 只有 `faultTestDebug` 编译破坏性 fault harness; 旧 `-Pautojs.lua.*.enabled` Boolean 开关已经移除.

#### 签名 release 构建

使用仓库构建器, 将外部签名材料和同签名 AutoJs6 APK 的绝对路径传入:

```powershell
.\tools\build_runnable_provider.ps1 `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -HostApk '<absolute matching AutoJs6 APK path>'
```

脚本要求干净 revision 与 versionCode/提交数一致, 离线全量重建, 比较 Host/Provider 证书, 并执行严格制品门禁. 回执只证明签名打包, 不替代设备安装与运行验证.

******

### 项目状态

******

当前版本 `0.1.0-rc.2` 是本地验证的签名打包候选, 尚未公开发布. 当前候选属于 clean revision `a0ae189ac8cba042848412a671c91b0b8a7c44e1` (versionCode 43), universal APK SHA-256 为 `93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd`; 不可变回执中的 `deviceVerified=false/runtimeVerified=false` 保持不变. x86_64 split 随后通过 API 37 真实 Host 冒烟及 API 24, 31, 36 安装/执行/卸载矩阵. arm64 物理设备验证与七日 soak 已于 2026-08-26 按 owner 决定裁撤, 以后采用 fix-on-report. 详见 [`docs/release-candidate-rc2.md`](docs/release-candidate-rc2.md), [`docs/release-candidate-rc2-emulator-smoke.md`](docs/release-candidate-rc2-emulator-smoke.md), [`docs/release-candidate-rc2-api-matrix.md`](docs/release-candidate-rc2-api-matrix.md) 与 [`docs/public-release-policy.md`](docs/public-release-policy.md). R3 历史见 [ROADMAP.md](ROADMAP.md), R4 证据见 [ROADMAP-R4.md](ROADMAP-R4.md), 当前 R5 计划见 [ROADMAP-R5.md](ROADMAP-R5.md).

******

### 验证与发布工程

******

维护者门禁默认离线运行, 以减少开发网络中的 Cloudflare 502/524/529 噪声.

#### 多语言文档

`.readme/` 与 `.changelog/` 保存 10 种语言的 JSON 源和共享模板; `.python/generate_markdown.py` 生成 10 份 README, APK 内 10 语言 CHANGELOG, 并将 `zh-Hans` 写为根 `README.md`. Android UI 字符串由各 `values-*` 资源目录独立维护. 修改 JSON 后执行:

```powershell
python .\.python\generate_markdown.py
python .\.python\generate_markdown.py --check
```

#### 离线验证门禁

```powershell
.\tools\verify_local.ps1
```

该脚本串联 build-ready verifier, Python 敌意套件和 `:app:testProviderDebugUnitTest --offline`, 再按 `verification.properties` 中的单一测试计数核对 XML 报告. CI 使用相同的静态与 Gradle 边界.

#### 不可变输入

协议输入恰好是三个仓库内 SHA-256 锁定 AAR (`common-plugin-api`, `protocol-wire-api`, `lua-runtime-api`). 原生输入是固定 archive SHA-256 `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae` 的 PUC Lua 5.4.8; verifier 重新计算完整源码树指纹, CMake 只接纳审核过的源文件. 详见 [`protocol/README.md`](protocol/README.md) 与 [`vendor-lock.json`](app/src/main/cpp/vendor/vendor-lock.json).

#### Release 制品验证

原始 `providerRelease` 可以无签名构建, 但可发布候选必须从外部绝对路径读取签名材料并完成 fresh 离线任务:

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

随后以相同证书运行制品 verifier. 它核对 JVM 数量, signer, split/universal ABI, 16 KiB ZIP/ELF 对齐, non-debuggable 和 fault-harness 排除; 结果仍只属于打包证据.

#### 预发布 fault-harness 检查表

重建签名候选前, 用一次 clean canonical invocation 同时生成 fault 变体和可审计的 unsigned release 中间产物:

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

成功标记必须是 `RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS`; `faultTestDebug` 必须包含隔离服务与 fault JNI symbol, `providerRelease` 必须在 BuildConfig, manifest, class 与两种 ABI native 输出中排除它们. 设备侧另运行 `LuaRuntimeFaultRecoveryInstrumentationTest`, 制品回执不能代替这项证据. 失败或未执行时视为 `UNVERIFIED_FAULT_HARNESS`.

******

### 深入阅读

******

运行边界: [`docs/native-execution-core.md`](docs/native-execution-core.md), [`docs/coroutine-control-boundary.md`](docs/coroutine-control-boundary.md), [`docs/pcall-boundary-decision.md`](docs/pcall-boundary-decision.md), [`docs/storage-kv-v1.md`](docs/storage-kv-v1.md), [`docs/ui-toast-v1.md`](docs/ui-toast-v1.md). 前向设计: [`docs/module-snapshot-v2.md`](docs/module-snapshot-v2.md), [`docs/result-model-v2.md`](docs/result-model-v2.md), [`docs/execution-statistics-v1.md`](docs/execution-statistics-v1.md). 发布证据: [`docs/host-lifecycle-matrix.md`](docs/host-lifecycle-matrix.md), [`docs/release-upgrade-matrix.md`](docs/release-upgrade-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md).

******

### 变更记录

******

# v0.1.0-rc.2

###### 2026/08/27

* `提示` 当前仍是本地验证的签名打包候选, 尚未创建公开 tag 或 GitHub Release; 不可变候选回执继续保留 `deviceVerified=false/runtimeVerified=false`
* `提示` 按 owner 决定裁撤 arm64-v8a 物理设备冒烟和七日 production soak, 已完成的两日证据与冻结标准继续归档, 长期稳定性改为 fix-on-report
* `新增` 加入受控 coroutine, `autojs.now()`, `console.info/warn`, Provider 侧 `ui.toast.v1`, crash diagnostic 与 `AutoJs6LuaWatchdog` 事件
* `新增` 协同实现 `storage.kv.v1`: 按文件脚本隔离的 Host 持久化, 固定 get/put/remove/clear 形状, 有界规范值且无重试, 并完成 Host 交付的 `ui.toast.v1`
* `新增` 开放文本模块 snapshot, 只读执行参数与设备信息桥, 并保持 deadline, cancel, 内存和输出配额贯穿全部路径
* `新增` 提供 arm64-v8a 与 x86_64 原生库, 并归档 API 24, 31, 36, 37 的 x86_64 真实 Host 验证
* `修复` 极小 deadline 在 `start()` 到达前过期时稳定产生唯一 `TIMEOUT/QUEUE` 终态, 不再留下无终态会话
* `修复` 收紧 `math.randomseed` 和 Host capability 拒绝映射, 使未授权调用稳定结束为 `HOST_CAPABILITY`
* `优化` 用显式 `providerDebug/providerRelease/nativeTestDebug/faultTestDebug` 变体替换四个 Boolean 构建模式, 并物理隔离生产发现服务与破坏性 fault harness
* `优化` 迁移到兄弟插件统一的 `.python/generate_markdown.py` + `.readme/` + `.changelog/` 机制, 完整提供 10 语言文档并以 `zh-Hans` 作为根 README 默认语言
* `优化` 将 R5 从 `ROADMAP-R4.md` 独立为 `ROADMAP-R5.md`, 删除已经由完整十语言落地取代的繁体槽位待办
* `优化` 补齐 PFD 逻辑与 OS 级账目, 一键离线门禁, CI 重试/缓存, release 制品验证和 fault-harness 排除审计
* `依赖` 固定 PUC Lua 5.4.8, Android NDK 28.2.13676358 与 CMake 3.22.1

# v0.1.0-rc.1

###### 2026/08/13

* `提示` 首个 Provider-enabled 本地候选完成签名和设备验证, 但没有创建公开 tag 或 release
* `新增` 首次实现独立 `:lua_runtime` 进程, 文本 Lua 执行, console, 标量结果和 AutoJs6 Binder Provider 发现
* `修复` 以 fail-closed 协议, 源码摘要, UTF-8, deadline, 内存和输出验证拒绝畸形或超限请求
* `优化` 建立协议 AAR, Lua 源码, ABI, 16 KiB 对齐, 签名和回滚矩阵的可复核证据
* `依赖` 基于标准 PUC Lua 5.4.8 与冻结的 AutoJs6 Lua protocol 1.0

##### 更多版本记录

* [CHANGELOG.md](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/assets/doc/CHANGELOG-zh-Hans.md)

******

### 许可证

******

仓库自有代码采用 MIT License. 打包或暂存的第三方组件还包括采用 MIT 的 PUC Lua, 采用 Apache-2.0 的 Kotlin 与 JetBrains annotations, 按记录的 LLVM 条款静态链接的 Android NDK LLVM runtime 部分, 以及采用 MPL-2.0 的冻结 AutoJs6 协议 API. 详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), 完整许可证文本和精确协议对应源码.

******

### 多语言资源布局

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
app/src/main/res/values-*/strings.xml
```

`.python/generate_markdown.py` 从 JSON 源生成全部 10 种语言的 README 与 APK 内变更记录; 修改文档请编辑 JSON 源而非生成的 Markdown. Android UI 字符串由各资源目录管理.

******

### 链接

******

- AutoJs6 项目: https://github.com/SuperMonster003/AutoJs6
- Lua 项目: https://www.lua.org
- 第三方声明: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/THIRD_PARTY_NOTICES.md
- 项目许可证: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE
