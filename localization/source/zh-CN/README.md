# Lua 运行时

这是冻结 Lua Binder 协议的独立 Android 运行时 Provider。插件面向 `lua54`
运行时槽位，使用 PUC Lua 5.4.8，并在专用的 `:lua_runtime` 进程中执行脚本。

## 当前检查点

本仓库包含一个能够工作的独立版本化运行时 Provider，但生产发现仍默认关闭：

- 应用 ID：`io.github.supermonster003.autojs6.plugin.lua.runtime`
- 插件 / 引擎 / 变体：`lua-runtime` / `lua` / `puc-lua54`
- INFO action：`org.autojs.plugin.INFO`
- RUNTIME action：`org.autojs.plugin.lua.RUNTIME`
- 支持的原生目标：`arm64-v8a` 与 `x86_64`
- 固定原生工具链：NDK `28.2.13676358`、CMake `3.22.1`
- 两个导出服务都要求 `org.autojs.permission.PLUGIN` 权限
- 两个服务都接受 action 为 null 的显式 component-only 绑定
- 两个服务都由生成的 Boolean 资源保持默认禁用

协议 AAR 与 PUC Lua 源码都是仓库内不可变输入，Gradle wrapper 已固定，并已有
定向 JVM、原生设备、进程恢复及显式生产 Provider 试运行证据。普通构建仍会关闭
原生执行和 Provider 发现；当前检查点不声明已经公开发布，也不声明 Provider 已
默认启用。

## 本地化仓库内容

英文与简体中文 README、变更记录和 Android 字符串均由
`localization/source` 下经过审阅的源文件生成。十个语言槽位统一声明在
`localization/locales.json`；其余八个槽位在获得真实翻译前没有源目录，也不会
放入占位译文。

修改任一活动语言后，使用以下命令重新生成并检查输出：

```powershell
python tools/generate_localized_content.py
python tools/generate_localized_content.py --check
```

不要直接编辑根目录生成文档或 `values*/strings.xml`；仓库验证器会执行同一项
漂移检查。

## Fail-closed 构建开关

`autojs.lua.native.enabled` 和 `autojs.lua.provider.enabled` 默认均为
`false`。只有原生开关已经启用时，Provider 开关才允许启用。

原生开关会激活 CMake；任何固定 Lua 源文件缺失都会导致构建失败。Provider
开关同时控制两个 manifest 服务，并且必须在原生执行、生命周期、描述符和
Android 一致性门禁通过前保持关闭。因此，安装普通脚手架构建不会影响宿主的
运行时选择。

### 构建可安装的 Provider

测试 Plugin Center 发现时不要安装普通 debug APK：其中既没有启用 Provider，
也不包含原生 Lua 库。应显式构建可运行候选版本，并证明它与匹配的 AutoJs6 APK
使用同一证书：

```powershell
.\tools\build_runnable_provider.ps1 `
    -SigningPropertiesFile '<签名 properties 的绝对路径>' `
    -SigningStoreFile '<JKS 的绝对路径>' `
    -HostApk '<匹配 AutoJs6 APK 的绝对路径>'
```

该命令会运行 JVM 套件，为 release 构建启用原生执行和两个发现服务，检查通用
APK 的 action、原生 ABI、版本与签名者，最后输出准确的制品路径。最终回执仍会
保持 `deviceVerified=false` 和 `runtimeVerified=false`；安装以及一次真实的
宿主到 Provider Lua 执行属于独立设备证据。仓库默认值仍不可发现，同签名 Binder
边界也不会被削弱。

当前可运行候选版本要求 AutoJs6 version code 不低于 5276。该宿主版本能让
dispatch 前的运行时信息超时继续落在原绑定预算内；更早的 Lua-capable 宿主可能
发现 Provider，但无法可靠派发脚本。

## 协议输入

应用只消费三个位于仓库内且由 SHA-256 锁定的 AAR：

- `protocol/common-plugin-api.aar`
- `protocol/protocol-wire-api.aar`
- `protocol/lua-runtime-api.aar`

详见 [protocol/README.md](protocol/README.md)。可变的相邻路径和未版本化缓存制品
都不是 release 输入。

`tools/verify_repository.py` 会解析协议/vendor 锁、两个默认关闭的 Gradle
属性，以及 CI 中对 Provider 开关的每个字面引用。CI 可以用 `native=true`
编译原生脚手架，但 Provider 的唯一取值必须保持 `false`。精确的 Git-ignore
例外只为 `protocol/*.aar` 与 `gradle/wrapper/gradle-wrapper.jar` 保留不可变
输入入口，不会宽泛放行缓存 AAR/JAR。重复 JSON 成员以及能够覆盖这些路径的嵌套
Git-ignore 文件都会 fail closed。

## 原生源码摄取

冻结的上游输入为：

- PUC Lua 5.4.8
- `https://www.lua.org/ftp/lua-5.4.8.tar.gz`
- SHA-256：
  `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae`

经过验证的归档已进入不可变 vendored 源码树。参见
[vendor-lock.json](app/src/main/cpp/vendor/vendor-lock.json) 和
[vendor 摄取说明](app/src/main/cpp/vendor/lua-5.4.8/README.md)。摄取后，仓库
验证器会针对完整 `src` 树重新计算规范路径/内容摘要与文件数量；单独放置一个
哨兵文件不能把锁状态改成 `vendored`。

CMake 源清单刻意排除了 `linit.c`、`lcorolib.c`、`ldblib.c`、
`liolib.c`、`loslib.c` 和 `loadlib.c`。JNI 探针使用有界
`lua_newstate` allocator，只开放 base、math、string、table 与 UTF-8 库；随后
移除受保护调用、基础 loader、metatable 访问以及 `string.dump`。在 hook 继承性
测试完成前，协程支持继续排除。

移除这些全局只是纵深防御，而不是二进制 chunk 门禁。执行 loader 调用
`luaL_loadbufferx(..., "t")`。由于 Lua core 仍包含 `lundump.c`，不得引入任何
没有显式 text-only 模式的 loader。

## 执行边界

`LuaRuntimeService` 在默认关闭的 Provider 门禁之后持有仅接受源码的
Binder/session 骨架。它会复制并关闭源描述符，在进程级零队列串行 worker 上验证
精确长度、SHA-256、EOF 与严格 UTF-8；同时只允许一个活动执行，限制保留但尚未
启动的会话，并收敛 callback、终态与 close 竞态。原生启用构建选择
`NativeLuaExecutionRunner`，原生关闭构建保留 `DisabledLuaExecutionRunner`；
Provider 发现仍是独立的默认关闭门禁。

受控的 `require("autojs")` 暴露 `console.log(string)`、
`console.info(string)`、`console.error(string)`、`console.warn(string)`、
`ui.toast(string)`、零参数 `now()` 和执行本地的 `arguments` 快照。该快照把完整
有界 V1 值模型映射为 Lua 标量、从 1 开始的稠密数组和字符串键表；字符串与字节使用 binary-safe Lua
string。`now()` 无需 Host 调用，返回有符号 64 位 Unix epoch 毫秒。现有的
`string.format` 与伪随机 `math.random` 继续开放，而 `math.randomseed` 现在要求
显式传入一到两个整数 seed；完整的 `os` 库仍不可用。

全局 `print(...)` 与 `warn(string)` 是受控的 stdout/stderr 桥：`print` 将参数
字符串化、用制表符连接并追加换行，`warn` 则复用 `console.error` 的 stderr
路径。`console.info` 映射 stdout，`console.warn` 映射 stderr，因此六个输出入口
都保留已有的序列、credit、分块与总输出上限，不增加 wire stream。平面 ASCII
模块名还可通过固定的 `module.snapshot.v1` capability 解析为执行本地 UTF-8 文本
快照。路径/package 搜索、二进制模块和动态 C 模块仍不可用。两个已注册 Host
数据 capability 与新增 `ui.toast.v1` 都有 Android-free 的授予/拒绝回归覆盖；
缺少授权时会稳定终止为
`DENIED`/`HOST_CAPABILITY`。
Toast 只接受一个 1–1024 字节的非空严格 UTF-8 字符串，请求/回执固定为
`{text=string}` / `{accepted=true}`；每次执行最多扣取四次额度，Provider 绝不重试。
相邻 Host 当前尚未启用该 capability，因此现有 Provider instrumentation 只证明
派发、校验、配额与拒绝行为，不代表已经完成可见 Toast 的端到端交付。

进程级 watchdog 绑定到每个获准执行的 token。它只在 worker 即将派发前启动，
在 cancel、close 或 callback 死亡时缩短宽限期，并在正常结束时清除 token。如果
worker 超过端到端 deadline，或超过停止请求加清理宽限期，专用运行时进程会被
标记为 poisoned 并终止。定向 Android 证据覆盖 kill、rebind 与新 PID 恢复，
但 Provider 发现仍默认关闭。

宿主侧编辑器与持久入口预览、签名且启用 Provider 的 `0.1.0-rc.1` 候选版本，
以及 API 24/31/37 的定向兼容和回滚样本，均已在各自历史提交中完成。这些结果只
对对应制品成立：它们不会发布 Provider、提升普通构建默认值，也不能证明生产
soak 已完成。

Android 原生 instrumentation 可以在 `native=true/provider=false` 下直接调用
`NativeLuaRuntime.execute()`，验证标量结果、受控 console 输出、失败、hook 与
allocator 恢复，而不会发现任一生产服务。另有显式 opt-in smoke 会经过正式
INFO/RUNTIME Binder 路径，同时保持仓库默认值关闭。

经过审阅的 PUC Lua 5.4.8 coroutine 库现已作为唯一新增的原生库源码纳入。子协程
及嵌套协程继承 count hook 与私有执行控制，在 yield/resume 之间共享所属 allocator，
并且不能把已经捕获的 deadline、取消或 allocator 配额失败转换为成功结果。精确边界
与 Android 矩阵记录在
[`docs/coroutine-control-boundary.md`](docs/coroutine-control-boundary.md)。

R4 继续保持 Lua 层 `pcall` 和 `xpcall` 不可用；审阅后的理由与重新评估条件记录
在 [`docs/pcall-boundary-decision.md`](docs/pcall-boundary-decision.md)。需要宿主
协同的结构化与多返回值提案记录在
[`docs/result-model-v2.md`](docs/result-model-v2.md)；它目前只是设计，不会修改
冻结的 V1 协议制品或当前标量结果边界。审阅后的工具子集与 console 映射分别记录
在 [`docs/safe-standard-library-subset.md`](docs/safe-standard-library-subset.md)
和 [`docs/console-levels-decision.md`](docs/console-levels-decision.md)。
已实现的 Provider 侧 Toast 契约及需要协同的 Host 后续记录在
[`docs/ui-toast-v1.md`](docs/ui-toast-v1.md)。点分模块名、
聚合源码配额与缓存指标仅作为不广告能力的 V2 提案记录在
[`docs/module-snapshot-v2.md`](docs/module-snapshot-v2.md)。持久化、按 principal
隔离的 KV 提案仅作为设计记录在
[`docs/storage-kv-v1.md`](docs/storage-kv-v1.md)；目前不广告 storage capability
或 Lua API。冻结协议没有能够一致承载每执行 Lua 峰值内存、hook 触发数、已接受
输出字节及 load/execute/teardown 耗时的字段。精确终态字段提案、兼容矩阵与当前
JVM 边界证据记录在
[`docs/execution-statistics-v1.md`](docs/execution-statistics-v1.md)，并明确标记
为需要 Host 协议协同演进；当前尚未广告统计 capability，也未采集运行时统计。
另有一项已实现的崩溃诊断：在 native 或 watchdog 导致进程死亡前，以原子方式写入
一条固定 20 字节记录，其中仅含 failure kind、阶段和 SHA-256 前 8 字节；新 runtime
只经 `getRuntimeInfo()` 上报不含内容的
`diagnostic.last-abnormal-termination.v1` 标志，健康的已验证 native 返回会将其清除。
隐私、竞态与恢复契约固定在
[`docs/crash-diagnostic-v1.md`](docs/crash-diagnostic-v1.md)，其中归档了 API 37、
16 KiB `emulator-5554` 上 4/4 通过的 crash/watchdog 恢复矩阵；未操作物理设备。
watchdog 还会为每次拥有 token 的 deadline、stop 或 control-failure fail-stop 发送
一条不含内容且形状固定的 logcat 事件；精确 tag/reason 映射、隐私边界及“诊断—
日志—终止”顺序记录在
[`docs/watchdog-event-logging.md`](docs/watchdog-event-logging.md)。

仓库 JVM 套件的准确数量只在 `verification.properties` 中声明一次，本地、CI、
debug 制品和 release 制品门禁都从这里读取。原生、Binder/PFD、进程恢复与
Provider 路径的 Android 证据保持独立。

## 本地离线门禁

使用仓库自带的标准本地门禁，不刷新依赖，也不回退到网络：

```powershell
.\tools\verify_local.ps1
```

它会运行 build-ready 仓库验证器、Python 敌意边界套件，并在原生启用、Provider
发现关闭和 Gradle `--offline` 条件下执行 `:app:testDebugUnitTest`。随后解析生成
的 XML 报告，并与 `verification.properties` 中的 `JVM_TEST_COUNT` 对照；因此，
即使其余测试全部通过，删除一个 JVM 测试也会让门禁失败。

## CI

静态 job 要求不可变协议和 Lua 输入，验证固定的仓库 wrapper，并在 build
readiness 回退时 fail closed。Gradle/native job 使用该 wrapper，以原生启用、
Provider 关闭的方式构建。CI 接线本身不是 Binder/PFD、安装、release 签名、API
矩阵或设备恢复证据。

输入门禁 Python 套件覆盖锁 schema 与重复键、revision 语法、制品清单/摘要、
源码树锁一致性、默认关闭解析、CI Provider 收敛、生成内容漂移、Git-ignore
优先级、摄取脚本漂移和 watchdog token/fail-stop 接线等正常与敌意案例。它只是
静态门禁；当前输出为
`STATIC_SCAFFOLD_OK protocol=ready lua=ready build_ready=true`。构建和 release
自动化必须继续调用
`python tools/verify_repository.py --require-build-ready`。

静态锁一致性不能证明 Git revision 确实存在、AAR 确实由该 revision 生成，或
重新锁定的 Lua 树确实来自固定归档。这些来源声明仍由 clean-checkout 摄取工作流
及后续 Gradle/build 证据负责。Provider 状态同样必须在允许 Gradle 工作后通过
merged manifest 或 APK 检查；源码和 CI 文本检查不是运行时证据。

## Release 验证

已完成的本地候选版本应遵循宿主仓库的 Lua roadmap 与定期证据。冻结的签名制品
仅属于插件 revision `0497d5061171421e655e0256ef5a1fe1566c5a23`；其后的源码
或协议输入提交必须重新构建，不能继承该 revision 的设备或签名证据。公开 tag、
远程发布上传和生产 soak 仍不在当前范围内。

签名 release assembly 必须显式 opt-in。外部签名 properties 与 keystore 可以
直接使用，无需复制到仓库：

```powershell
$releaseArgs = @(
    ':app:testDebugUnitTest'
    ':app:assembleRelease'
    '-Pautojs.lua.native.enabled=true'
    '-Pautojs.lua.provider.enabled=true'
    '-Pautojs.lua.faultHarness.enabled=false'
    '-Pautojs.lua.releaseCandidate.enabled=true'
    '-Pautojs.lua.release.signingPropertiesFile=<签名 properties 的绝对路径>'
    '-Pautojs.lua.release.signingStoreFile=<JKS 的绝对路径>'
    '--no-daemon'
    '--console=plain'
)
.\gradlew.bat @releaseArgs
```

命令行只接收签名路径；alias 和密码由 Gradle 进程内部读取。两个路径都必须是绝对
路径且指向普通文件。旧的四变量 `AUTOJS_LUA_RELEASE_*` 契约仍受支持，但不能与
外部文件形式混用。仓库默认值、开发 CI 和普通 debug 构建都会保持原生执行、
Provider 发现及候选模式关闭。

在一次干净的规范调用中同时执行 `:app:testDebugUnitTest` 与
`:app:assembleRelease` 后，使用同一外部签名证书验证新制品，且不把密码放在
命令行中：

```powershell
.\tools\verify_release_candidate_artifacts.ps1 `
    -InvocationStartedAtUtc '<该 Gradle 调用开始时的 UTC 时间>' `
    -SigningPropertiesFile '<签名 properties 的绝对路径>' `
    -SigningStoreFile '<JKS 的绝对路径>' `
    -SdkRoot '<Android SDK 根目录>'
```

验证器会通过 `keytool` 和进程范围的密码环境变量推导期望证书摘要，之后清除该
变量。结果只证明签名与打包；它会有意报告设备和运行时验证为 false。

### 预发布 fault-harness 检查表

重建签名候选版本前，应通过一次干净的规范调用生成默认关闭 fault harness 的两侧
排除证据。debug 变体显式启用破坏性 harness；未签名 release 中间产物则必须在
物理上排除它：

```powershell
$faultStarted = [DateTimeOffset]::UtcNow.ToString(
    'yyyy-MM-ddTHH:mm:ss.ffffffZ'
)
$faultArgs = @(
    ':app:clean'
    ':app:assembleDebug'
    ':app:compileReleaseKotlin'
    ':app:processReleaseMainManifest'
    ':app:externalNativeBuildRelease'
    '-Pautojs.lua.native.enabled=true'
    '-Pautojs.lua.provider.enabled=false'
    '-Pautojs.lua.faultHarness.enabled=true'
    '--rerun-tasks'
    '--offline'
    '--no-daemon'
    '--console=plain'
)
.\gradlew.bat @faultArgs
.\tools\verify_fault_harness_artifacts.ps1 `
    -InvocationStartedAtUtc $faultStarted `
    -SdkRoot '<Android SDK 根目录>'
```

release-candidate 门禁仍绑定实际 APK/AAB 制品任务。这里只允许生成无签名的编译、
manifest、资源与原生中间产物，以便排除审计读取它们，但不会创建 release package。

所需成功回执为 `RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS`：debug 必须包含
两个显式隔离服务和两个 fault JNI symbol，而 release BuildConfig、merged
manifest、编译类以及两个 ABI 的原生输出必须将它们排除。在一次性设备上，还需
单独以 `native=true`、`provider=false`、`faultHarness=true` 运行
`LuaRuntimeFaultRecoveryInstrumentationTest`；其中的 crash/wedge 与阻塞 pipe
fail-stop、远端 `/proc/self/fd` 精确基线、callback/broker 独立死亡、新 PID 与
恢复后执行断言才是设备证据，不能由制品回执替代。

## 许可证

仓库代码采用 MIT License。PUC Lua 同样使用 MIT 许可证；参见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和保留的 Lua 许可证文本。
