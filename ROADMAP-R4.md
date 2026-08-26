# Lua 运行时插件 Roadmap — R4 阶段 (能力精进与发布收敛)

> 本文件承接 `ROADMAP.md` 中已完成的 R3-A/B/C/D 阶段。勾选规则与 R3 一致:
> 勾选仅表示对应的源码、测试或静态证据已存在并可复核, 不代表设备、签名或
> 发布层面的验收。每一项均给出"完成判据", 以便逐项 Check。
> R5 已独立迁移到 [`ROADMAP-R5.md`](ROADMAP-R5.md); 本文件只保留 R4 的历史
> 计划与证据台账。
>
> 验证约定: 受网络环境限制 (Cloudflare 502/524/529, 尤以 524 超时为甚),
> 所有 Gradle 验证一律附加 `--offline`; 纯静态验证优先使用
> `python tools/verify_repository.py --require-build-ready` 与
> `python -m unittest discover -s tools/tests`。任何需要外网的步骤单独标注
> 并允许跳过重试。

## R4-0 — 基线修复 (阻塞项, 先于一切新工作)

当前 HEAD (`9c0375f`) 与工作区存在以下真实漂移, 必须先归零:

- [x] **修复 JVM 测试计数漂移 (43 → 44)**。提交 `9c0375f` 新增了
  `LuaExecutionSessionControllerTest` 的诊断透传用例, 实际套件为 44 个测试,
  但 `.github/workflows/ci.yml`、`tools/verify_debug_artifacts.ps1`、
  `tools/verify_release_candidate_artifacts.ps1`、`tools/verify_repository.py`
  (含 `tools/tests` 固定文本) 、`README.md` 与 `ROADMAP.md` 仍写 43。
  完成判据: 上述六处全部一致为 44; `python -m unittest discover -s tools/tests`
  23/23 通过; `verify_repository.py --require-build-ready` 输出
  `STATIC_SCAFFOLD_OK`。
- [x] **提交 `.gitignore` 顺序修复**。`.bak/` 曾被追加到不可变输入例外
  (`!protocol/*.aar`、`!gradle/wrapper/gradle-wrapper.jar`) 之后, 触发静态门禁
  fail-closed (已在工作区修正为例外规则保持最后两行)。
  完成判据: 修复随下一次提交入库, 静态门禁在干净检出上通过。
- [x] **同步文档中 print/warn 的描述**。`9c0375f` 已把 `print`/`warn` 恢复为
  受控 console 桥 (等价 `console.log`/`console.error`, 同样受序列/额度/分块/
  总量限制), 但 `README.md` 第 130-131 行与 `docs/native-execution-core.md`
  第 46 行仍声称二者"不可用"。
  完成判据: 两份文档与 JNI 实际行为 (`install_autojs_module` 中的
  `lua_setglobal(state, "print"/"warn")`) 一致, 并说明其输出边界。
- [x] **提交工作区在途变更并恢复 VERSION_BUILD 不变量**。当前未提交变更包含
  宿主版本对齐 5276、mipmap 图标迁移、`requiresHostVersion` 资源化与 JNI
  尾逗号修复; 当前 HEAD 的 commit count 已为 23，因此下一次单提交收敛时
  `version.properties` 必须写 `VERSION_BUILD=24` 才能满足
  `VERSION_BUILD == rev-list --count HEAD` 的 artifact 门禁；若拆分为多个
  提交则需再次对齐。
  完成判据: 工作区干净; `git rev-list --count HEAD` 与 `VERSION_BUILD` 相等。
- [x] **(联网, 可延后) 在一次网络良好的窗口跑一遍完整 CI 等价验证**:
  `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
  -Pautojs.lua.native.enabled=true -Pautojs.lua.provider.enabled=false --offline`
  失败再去掉 `--offline` 重试一次。
  完成判据: 44/44 通过且三个 debug APK 产出;
  `tools/verify_debug_artifacts.ps1 -ExpectedTests 44` 通过。

R4-0 本地证据 (2026-08-24): Python 敌意/静态套件 23/23 通过，
`verify_repository.py --require-build-ready` 输出 `STATIC_SCAFFOLD_OK`；完整
Gradle 命令在 `--offline` 下完成 44/44 JVM 测试、两种 ABI 的 JNI 编译及三个
debug APK。提交后的干净 HEAD 另以 `verify_debug_artifacts.ps1` 复核版本计数、
APK/ABI、16 KiB ZIP/ELF 对齐、签名与 packaged gates。

## R4-A — R3 遗留缺口收敛 (ROADMAP.md 已明示未勾选/未测项)

- [x] **PFD 全生命周期账目**。补齐 R3-C 唯一未勾选项: 为每个传入、复制、
  返回、遗弃的 ParcelFileDescriptor 建立逻辑账目, 覆盖 create 异常路径、
  BUSY 拒绝路径、broker 回调 payload 关闭路径。
  完成判据: 新增 JVM 账目测试 (计数进/出严格配平); 现有
  `LuaRuntimeExecutionManager` 的 `finally { source?.close() }` 与
  `LuaParcelFileExecutionSource` 双重关闭路径均有断言。
- [x] **OS 级 FD 泄漏证据**。在 androidTest 中于批量执行前后对比
  `/proc/self/fd` 计数 (含故意失败、取消、回调死亡各路径)。
  完成判据: 仪器测试断言 FD 计数回落到基线; 用例并入故障恢复矩阵。
- [x] **极小 deadline 与 oneway start 竞争**。R3 记录: 极小请求超时可能在
  oneway `start()` 尚未到达时就过期。为该窗口定义确定性行为 (统一走
  `expireIfNotStarted` 的 TIMEOUT 终态, 不得出现无终态会话)。
  完成判据: JVM 测试固定 1ms deadline + 延迟 start, 断言唯一终态与
  watchdog 租约释放。
- [x] **阻塞型非常规源 FD 的敌意遏制**。宿主传入 pipe/socket 等阻塞 FD 时,
  读取阶段必须受 deadline/watchdog 约束而非无限阻塞。
  完成判据: androidTest 用 pipe 写端悬挂构造阻塞读, 断言在 deadline +
  宽限期内进程被 fail-stop 或会话进入 TIMEOUT 终态。
- [x] **更广的对端死亡矩阵**。补齐 R3 明示未测的 update/uninstall/对端死亡
  case: 宿主更新、宿主卸载重装、broker 与 callback 分别单独死亡。
  完成判据: 仪器矩阵各 case 至少一条用例, 断言会话清理与 watchdog 不误杀
  后续执行。
- [x] **十语言 README/changelog/资源工作流**。R3-A 唯一未勾选项。先落地
  zh-CN/en 双语生成脚手架 (脚本生成、单一 source of truth), 其余八语言
  仅在有真实翻译输入时扩展, 不引入机器占位文本。
  完成判据: 生成脚本 + 双语 README 入库; verifier 增加生成物一致性检查。
  此处记录的是 R4 当时的关闭方式; 当前实现已在 R5-A 迁移为兄弟插件统一的
  十语言 `.python/.readme/.changelog` 机制。

R4-A 本地账目/竞争证据 (2026-08-24): JVM 套件新增四条 PFD 逻辑账目用例，
分别覆盖 create 异常、BUSY 不复制、source read/controller finish 双重关闭幂等、
Host callback payload 与 V1 零返回 payload，所有分类进/出严格配平；Android
测试另直接断言非法 Host payload 的真实 PFD 已关闭。1ms deadline + 延迟
`start()` 固定产生唯一 `TIMEOUT/QUEUE` 终态，并释放未派发的 watchdog 与
start lease。更广对端死亡仍由下方未勾选项承接。

R4-A 设备 FD/阻塞源证据 (2026-08-25): debug-only fault control Binder 在真实
`:lua_runtime` 进程读取 `/proc/self/fd`；热身后固定基线，随后 4 轮成功执行与
digest 失败、主动取消、callback 独立进程死亡、broker 独立进程死亡均清理到
完全相同的 OS FD 数量，且两类 peer 死亡不会替换或误杀健康 runtime。另以
`ParcelFileDescriptor.createPipe()` 保持写端悬挂，证明 source validation 阶段
阻塞读会被已派发 watchdog 在 deadline + 2 秒 cleanup grace 后 fail-stop；重绑
获得新 PID/nonce，并再次成功执行 `return 7`。完整 fault instrumentation 4/4
已在 API 37 x86_64 `emulator-5554` 通过。更广矩阵中的 callback/broker 独立死亡
已有证据。

R4-A 真实宿主生命周期证据 (2026-08-25): 独立测试 APK 的 instrumentation
`targetPackage` 固定为真实 `org.autojs.autojs6`，且与宿主/Provider 使用同一签名；
因此 `while true do end` 活跃会话的 callback 与 broker Binder 均实际归属宿主
进程/UID，而测试代码没有自行杀进程的入口。以宿主源码 revision
`afca7b14c4ba3971b60a9ce3587e2f10bfd0ab1e` 构造同源同签名的 5276 → 5277
x86_64 APK 对，先在 `onStarted` 后执行真实 package replace，再独立执行宿主卸载、
确认 Provider 仍安装、重装 5277；两次 Host 死亡前后的 `:lua_runtime` PID 均稳定为
14807。每个 case 在恢复后立即成功执行一次 `return 7`，再持有同一 Provider
Binder 跨过 7 秒 (大于旧会话 4 秒 deadline + 2 秒 cleanup grace) 后再次成功，
共 4 次恢复执行，证明会话租约已清理且旧 watchdog 未误杀后续执行。API 37
`emulator-5554` 最终输出 `HOST_LIFECYCLE_MATRIX_PASS`；完整边界与复现方法见
[`docs/host-lifecycle-matrix.md`](docs/host-lifecycle-matrix.md)。精确制品为 Host 5276
`c3d87a02713ac3b5b9674bbbd34ba890226e1a8dd4840dfcb3f9cd4b74bf572c`、Host 5277
`8561864bbad8d739e875d998484e5d2e393cce38c406e0ff58d6bec0eb215f1c`、revision
`6b6019243c6cc9a66d54f559450776cf7829a059` 的 Provider versionCode 33
`6ef9cb7858f5ba886767d18f31e6de1255a8bfa975670511887004a0f20c8166` 与 lifecycle
test `5de00fd680e40370ed3c37305f94d760ffc8b592af2e9567d299e97c6f757f81`；共同 signer
SHA-256 为 `31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`。

R4-A 本地化证据 (2026-08-24): `localization/locales.json` 声明恰好十个语言
槽位，当前仅 `en`/`zh-CN` 为 `humanReviewed` active source；其余八个 planned
槽位没有 source 目录或占位译文。`tools/generate_localized_content.py` 从单一
source tree 生成双语 README、双语 changelog 与两套 Android strings，
`--check`、仓库 verifier 及 Python 敌意用例会拒绝生成物漂移、重复 JSON key、
planned source 与翻译占位标记。

R4-A 本地化历史说明 (2026-08-27): 上述 R4 管线与路径作为历史证据保留在本段,
但不再描述当前工作区. 当前多语言输入、生成物和完成判据统一见
[`ROADMAP-R5.md`](ROADMAP-R5.md) 的 R5-A; 旧 `localization/` 与
`tools/generate_localized_content.py` 已移除。

## R4-B — 语言能力精进 (受控扩展, 每项默认关闭直至证据齐备)

- [x] **协程库受控引入**。R3 因 hook 继承性未验证而排除 `lcorolib.c`。
  先写 native 层证据: 子协程继承 count hook 与 deadline 检查、协程内
  cancel 生效、跨 resume/yield 的 allocator 记账不破。
  完成判据: CMake 源清单加入 `lcorolib.c` (仅此一文件);
  androidTest 覆盖协程内超时/取消/OOM 三路径; `linit.c` 仍排除。
- [x] **受控 `pcall`/`xpcall` 评估**。当前移除二者是为防脚本吞掉 hook 取消
  错误。评估替代方案: 提供包装版 `pcall`, 对 watchdog/cancel 类错误
  (以私有 sentinel 标识) 强制重抛, 普通业务错误可捕获。
  完成判据: 决策记录写入 `docs/` (采纳或明确拒绝均可勾选);
  若采纳, 附带"取消错误不可吞"的 native 测试。
- [x] **多返回值/表结果的 V2 结果模型草案**。当前结果仅限单标量。
  在协议 wire API 允许的范围内起草 V2: 表→受限 LuaValue 树 (复用参数侧
  已有的 depth/nodes/bytes 配额), 多返回值→数组。仅出设计文档与
  兼容性分析, 不动 frozen 协议。
  完成判据: `docs/result-model-v2.md` 完成, 含与宿主协议版本协商方案;
  明确标注需要宿主侧配合的部分。
- [x] **`string.format`/`os.time` 类安全子集调研**。梳理脚本实际高频诉求
  (时间戳、随机数), 评估以 `autojs` 模块受控 API 形式提供 (如
  `autojs.now()`), 而非开放 `os` 库。
  完成判据: 调研记录 + 决定清单入库; 采纳项各配 JNI 边界测试。
- [x] **模块快照能力增强**。当前 `module.snapshot.v1` 仅支持平面 ASCII 名。
  评估 v2: 点分层级名 (`a.b.c`)、每执行模块总量上限、快照缓存命中指标。
  完成判据: 能力协商设计文档; 若实现, 名称校验正则与循环加载 fail-closed
  测试同步扩展。

R4-B 决策/设计证据 (2026-08-24): `docs/pcall-boundary-decision.md` 明确拒绝在
R4 开放 Lua 层 `pcall`/`xpcall`，保留 control-plane interruption 不可捕获的
性质，并列出未来重新评估必须覆盖的 nested catch、OOM、xpcall handler 与
coroutine 矩阵。`docs/result-model-v2.md` 仅起草 protocol 1.1 +
`result.model.v2` 双重协商、独立 `SCHEMA_RESULT_V2`、有界 LuaValue tree/
ordered returns、V1 downgrade 与宿主/Provider 分工；冻结的 1.0 AAR、当前
metadata、JNI 标量边界及零结果 PFD 均未改变。Python 敌意/静态套件会拒绝关键
决策或兼容性证据被移除。

R4-B 工具/模块证据 (2026-08-24):
`docs/safe-standard-library-subset.md` 保持 `loslib.c`/完整 `os` 库关闭，采纳
现有 `string.format`、伪随机 `math.random` 与零参数 `autojs.now()`；同时将
`math.randomseed` 收紧为必须显式传入一到两个整数，避免把上游无参数分支中含
`lua_State` 地址成分的 seed 返回脚本。原生 instrumentation 源码覆盖格式化、
随机数、时钟值与两条错误参数路径；完整 11 项 native instrumentation 已在
16 KiB x86_64 `emulator-5554` 以 `native=true/provider=false` 实跑通过。
`docs/module-snapshot-v2.md` 则冻结点分 ASCII 正则、16 段/255 字节名称上限、
64 个不同模块/512 KiB 聚合源码配额、精确缓存指标与禁止 V2→V1 错误回退；
`module.snapshot.v2` 仍未进入 Kotlin/JNI 或 Provider metadata。

R4-B 协程证据 (2026-08-25): CMake 库清单仅新增 PUC Lua 5.4.8
`lcorolib.c`，`linit.c` 继续排除；JNI 显式打开 `luaopen_coroutine`。静态门禁绑定
上游 `lua_newthread` 对父线程 hook/mask/count 的复制及从主线程复制
`LUA_EXTRASPACE` 的顺序，并绑定 sticky allocator-limit、完整关闭后
`used == 0` 与 `MEMORY_LIMIT` 分类。干净实现 revision
`fc964d448c85f950c27667e7891fbb7d337fe73e` 的 x86_64 debug APK versionCode 35
`244345a1eb0512ca8cb018012614713d43eef3aab500d0086a4d1978199ccaac` 与 test APK
`81dfc66b720b05fb8ded3cfbe34ef43c0a5b1a44172531c3f821ca00f8e12cdb`
使用共同 signer
`2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8`；完整
15 项 `NativeLuaRuntimeInstrumentationTest` 在 API 37、x86_64、16 KiB
`emulator-5554` 以 `provider=false/faultHarness=false` 通过。子协程 deadline、取消、
六轮 yield/resume allocator 记账、被 `resume` 捕获后仍粘性失败的 OOM 及同进程
立即复用均通过；完整回执与边界见
[`docs/coroutine-control-boundary.md`](docs/coroutine-control-boundary.md)。未操作物理设备。

## R4-C — 宿主能力面扩展 (capability 逐个白名单化)

- [x] **`console` 分级增强**。评估 `console.warn`/`console.info` 独立
  wire 流或以现有双流 (stdout/stderr) 映射; 保持序列/额度/分块限制不变。
  完成判据: 决策记录; 若实现, `emit_autojs_console` 常量与协议枚举对齐,
  JVM 测试覆盖新流。
- [x] **`toast` 能力 (`ui.toast.v1`)**。最小 UI 反馈能力: 单 string 参数,
  长度上限, 频率限制 (每执行 N 次), Binder 单次派发不重试。
  完成判据: 与 `device.info` 相同规格的 fixed-shape 桥 + 校验器 +
  仪器冒烟; capability 出现在 `LuaProviderMetadata.capabilities`。
- [x] **`storage` 键值能力草案 (`storage.kv.v1`)**。执行间持久化的
  受控 KV: 键 ASCII 白名单、值走既有 LuaValue 配额、宿主侧落盘。
  完成判据: 能力设计文档 (含配额与清除策略); 实现另立勾选项。
- [x] **能力协商回归矩阵**。宿主未授予某 capability 时, 脚本调用对应 API
  必须得到确定性 DENIED 错误而非挂起或崩溃。
  完成判据: 对每个已注册 capability 各一条 JVM 测试 (授予/未授予两态)。

R4-C console 证据 (2026-08-24): `docs/console-levels-decision.md` 选择不扩展
冻结协议枚举；`console.info` 精确映射 `STDOUT/1`，`console.warn` 精确映射
`STDERR/2`。JNI 使用命名 wire 常量，JVM 用例固定协议仍恰好只有两个 stream，
原生 instrumentation 在同一 16 KiB x86_64 模拟器覆盖四个 `autojs.console`
名称的有序双流输出；现有序列、credit、分块与总量门禁不变。

R4-C capability/storage 证据 (2026-08-25): 当前 Provider metadata 中已注册的
`device.info`、`module.snapshot.v1` 与 `ui.toast.v1` 各由 1 秒超时 JVM 用例同时
覆盖授予和拒绝；默认拒绝器稳定产生 `DENIED`，JNI bridge 记录固定 rejected wire
值 `3`，不挂起也不降级。原生 instrumentation 覆盖 `device.info` 与 `ui.toast.v1`
未授予时映射为 `HOST_CAPABILITY`；模块未授予路径沿用相同终态映射。
`docs/storage-kv-v1.md`
冻结 Host 侧持久化与稳定脚本 principal 隔离、64-byte ASCII key、每 principal
256 keys/2 MiB、每执行 64 次操作/32 次 mutation、清除语义，以及 mutation 禁止
Provider 重试；该能力仍是 design-only，未进入 Kotlin/JNI 或 Provider metadata。

R4-C toast 证据 (2026-08-25): implementation revision
`e26fbc1356dc9e98a0fdf11e4ab732f06079eac4` 固定
`autojs.ui.toast(text)` 为 `{text=string}` / `{accepted=true}`，严格 UTF-8
1–1024 bytes，每执行共享 4 次额度，派发前扣取且 Binder/JNI 均不重试。仓库门禁
42/42 Python、50/50 JVM 通过；API 37、x86_64、16 KiB `emulator-5554` 上完整
`NativeLuaRuntimeInstrumentationTest` 17/17 通过，versionCode 37，
`provider=false/faultHarness=false`。x86_64 APK SHA-256 为
`ab62c4bb40f77259f7d5f9eaad5ca213a72186ef8dea0897bd491ca4774aab5a`，测试 APK
SHA-256 为 `74b1be6024b78bf366d93d19b062841b1064a75c78f67ec490b8df2db5d5763e`，
两者 signer 为
`2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8`。相邻脏 Host
revision `4a9718d63923834c9a99fd70e0cd58c898e138f6` 尚未启用该 capability，因此本项
关闭的是 Provider fixed-shape/校验/配额/拒绝/冒烟判据，不声称可见 Toast 已完成
Host 端到端交付。完整回执见 [`docs/ui-toast-v1.md`](docs/ui-toast-v1.md)；未操作
物理设备。

## R4-D — 可观测性与诊断

- [x] **结构化执行统计**。在终态回调中附带 (或经 `getRuntimeInfo` 暴露)
  每次执行的峰值内存、指令 hook 触发数、输出字节数、耗时分解
  (load/execute/teardown)。仅统计, 不含脚本内容。
  完成判据: 协议允许范围内的字段设计 + JVM 断言; 越界则记录为
  "需宿主协议演进"并给出字段清单。
- [x] **崩溃诊断落盘**。native crash / watchdog fail-stop 前, 将最小诊断
  (failure kind、阶段、脚本 hash 前 8 字节) 写入进程私有目录, 下次
  `getRuntimeInfo` 可上报"上次异常终止"标志。
  完成判据: 仪器测试注入 fault harness 崩溃, 重启后读到诊断标志。
- [x] **watchdog 事件可追溯**。为 `DEADLINE_CLEANUP_EXPIRED`/
  `STOP_CLEANUP_EXPIRED`/`WATCHDOG_CONTROL_FAILURE` 三类 fail-stop 附带
  logcat 结构化标签, 便于宿主侧聚合。
  完成判据: 标签常量 + 单测断言日志路径被调用 (可注入 logger)。

R4-D 结构化统计设计证据 (2026-08-25): 冻结 protocol 1.0 的成功终态仅有
`requestId/value/elapsedMillis`，取消终态仅有
`requestId/reason/elapsedMillis`，失败终态没有 elapsed/statistics 字段；
`getRuntimeInfo()` 也没有 execution identity，不能安全充当“上一次执行”旁路。
[`docs/execution-statistics-v1.md`](docs/execution-statistics-v1.md) 因此按判据标记
“需宿主协议演进”，冻结 `SCHEMA_EXECUTION_STATISTICS` 的七字段清单、六位
validity mask、三个终态父 tag、protocol 1.1 + `execution.stats.v1` 双重协商、
V1 byte-for-byte 回退及 Host/Provider 分工。JVM 边界用例反射并编码断言冻结 AAR
仍无合法统计承载位；静态敌意门禁绑定设计与测试。当前 metadata/JNI/终态编码均未
广告、采集或伪装统计，不声称运行时实现或 Host 端到端交付。

R4-D 崩溃诊断证据 (2026-08-25): implementation revision
`d882dea986dd95f1f0e6aeb484618ca99f703dfb` 在 native runner 前同步原子写入固定
20 字节 provisional 记录，managed return 清除；watchdog 则先 poison token、再以
精确 reason/phase 覆写并提交，最后 fail-stop。记录仅含闭合 failure kind、阶段、
SHA-256 前 8 字节与 CRC32；下次 `getRuntimeInfo()` 只动态加入不含内容的
`diagnostic.last-abnormal-termination.v1` 标志，三项可调用 Host capability 未改变。
仓库门禁 44/44 Python、58/58 JVM 通过。由该提交 clean 构建的 versionCode 40
x86_64 APK `1552e47be94542b0fab5fa28624691f2c7c3ee3e493c14ece9dc9816c5d75e8f`
与 test APK `a105d92e2fe476d246e0b57d1109bb5a22a1e7d82f65a6e96f92c9901035167f`
使用共同 signer
`2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8`；API 37、
x86_64、16 KiB `emulator-5554` 上完整 fault instrumentation 4/4 (7.984 秒)
通过。真实 JNI crash、native wedge、悬挂 pipe 分别读到
`NATIVE_CRASH/NATIVE_EXECUTION`、`DEADLINE_CLEANUP_EXPIRED/NATIVE_EXECUTION`、
`DEADLINE_CLEANUP_EXPIRED/SOURCE_VALIDATION` 及匹配的 8-byte hash；每次恢复后的
`return 7` 均清除文件与标志，最终私有目录为空。完整契约与回执见
[`docs/crash-diagnostic-v1.md`](docs/crash-diagnostic-v1.md)；所有 ADB 命令显式指定
`emulator-5554`，未运行 `connectedAndroidTest`，未操作物理设备。

R4-D watchdog 日志证据 (2026-08-25): 固定 Android logcat tag
`AutoJs6LuaWatchdog`，消息恰好为
`event=lua_runtime_fail_stop reason=<closed_reason_tag>`；三类 reason 分别闭合映射为
`deadline_cleanup_expired`、`stop_cleanup_expired`、`watchdog_control_failure`，不含
token、hash、脚本内容或任意动态字段。所有 fail-stop 经统一终止汇合点执行“诊断
observer → logger → terminator”；observer/logger 异常均被独立遏制，不能阻断 kill。
注入式 JVM recorder 精确覆盖三类路径，并固定异常用例顺序为
`diagnostic, log, terminate`；静态敌意门禁会拒绝 logger 调用、reason 常量、Android
`Log.e` 适配器或生产注入被移除。仓库离线门禁 44/44 Python、59/59 JVM 通过；本项
完成判据不要求新增设备声明。完整契约见
[`docs/watchdog-event-logging.md`](docs/watchdog-event-logging.md)。

## R4-E — 发布工程收敛 (从 rc 走向可公开发布)

- [x] **rc.2 候选重建**。基于 R4-0 修复后的干净 HEAD 重建签名候选:
  `tools/build_runnable_provider.ps1` 全程通过, 产出新的 receipt。
  完成判据: receipt 中 revision 为新 HEAD; `deviceVerified`/
  `runtimeVerified` 按流程翻转为 true 的设备证据单独归档。
- **设备安装 + 宿主端到端冒烟归档 — 已裁撤 (2026-08-26)**。x86_64 模拟器
  半程已完成并归档 (安装 → 真实宿主发现/执行 → 结果/console 全部通过);
  arm64-v8a 真机既未授权也不可用, 按 owner 决定退出发布门禁, 改为发布后
  按报告修复。历史证据段落按原文保留, 本项不勾选 [x]。
- [x] **API 兼容矩阵扩展**。在 API 24 (minSdk)、31、36 (targetSdk) 三档
  完成安装/执行/卸载回归 (R3 曾做 24/31/37 样本, 需对齐当前 targetSdk=36)。
  完成判据: 三档证据归档; 发现的兼容问题各开独立勾选项。
- [x] **API 24 冒烟入口反射兼容修正**。矩阵预检发现独立 Host smoke APK 使用
  API 26 才加入的 `Method.getParameterCount()`；改为 minSdk-safe 的
  `method.parameterTypes.size`，避免把测试入口 `NoSuchMethodError` 误判为 Provider
  回归。完成判据: 敌意静态测试锁定禁用调用；测试模块 lint/assemble 与 API 24
  真机前置模拟器冒烟通过。
- [x] **升级/回滚路径证据**。rc.1 → rc.2 覆盖安装升级、rc.2 卸载重装、
  以及宿主 5276 与更早版本 (应拒绝 dispatch) 的组合行为。
  完成判据: 每个组合一条归档记录; 早宿主拒绝路径给出确定性错误码。
- [x] **公开发布物料**。LICENSE/THIRD_PARTY_NOTICES 复核、GitHub Release
  草稿 (含 SHA-256 与最低宿主版本声明)、公开 tag 命名方案
  (`v0.1.0` 系列) 。(联网项, 放在网络良好窗口执行)
  完成判据: 物料在仓库内成稿; 实际发布动作单独人工确认。
- **生产 soak 计划 — 已裁撤 (2026-08-26)**。冻结标准与 Round 1/2 记录
  按原文保留; Round 2 在 Day 2/7 通过后按 owner 决定关闭, 不再作为发布
  门禁。长时稳定性改为日常使用中按报告即时修复; soak 执行器与标准保留,
  供未来自愿重启。本项不勾选 [x]。

R4-E 生产 soak 标准冻结 (2026-08-25): `N=7` 个 Asia/Shanghai 连续自然日，
每日 250 次真实 Host smoke、每次 2 次 Lua 执行，即每日 500 次、整轮 3,500 次；
同一 API 36 x86_64 AVD boot ID、同一 `:lua_runtime` PID、同一组逐字节 APK，且
日初/日末 `/proc/<pid>/fd` 必须精确等于首日 warmup 后基线。任一漏日、制品/安装
漂移、AVD/进程重启、instrumentation 失败、`event=lua_runtime_fail_stop`、相关
ANR/crash 或 FD 终值增长都会使整轮失效。执行器仅接受显式 `emulator-*` 且二次
校验 `ro.kernel.qemu=1`，资格运行不能创建或推进生产 state；首轮尚未完成，本项
保持未勾选。标准、失效规则与证据格式见
[`docs/production-soak-plan.md`](docs/production-soak-plan.md)，进行中记录见
[`docs/production-soak-round-1.md`](docs/production-soak-round-1.md)。

R4-E 生产 soak Round 1 失效记录 (2026-08-25): 资格运行 20 次真实执行通过，
PID/FD 为 `8843 / 83→83`。正式 Day 1 前 115 次 measured smoke 通过；第 116 次
被计划外 Host 包替换中断。events 精确记录 `installPackageLI` 杀死 Host，安装态随即
从冻结 versionCode 5276 / update 15:23:21 漂移到 versionCode 5278 / update
15:35:52，且出现无关 `org.autojs.autojs6.test`；Provider 仍为 versionCode 43、
PID 12691、FD 83，watchdog fail-stop、相关 ANR/`am_crash` 均为 0。按冻结规则整轮
作废而非续跑；同时修复了失败 state 原子覆盖并新增失败 logcat 留存。Round 2 使用
新 ID 与独占部署窗口重新从 Day 1 开始，本项继续保持未勾选。详见
[`docs/production-soak-round-1.md`](docs/production-soak-round-1.md) 与
[`docs/production-soak-round-2.md`](docs/production-soak-round-2.md)。

R4-E 生产 soak Round 2 Day 1 证据 (2026-08-25): 新 boot ID
`47e07ea5-0187-4553-871a-6c713deab71c`，冻结 lifecycle-test versionCode 57 / SHA-256
`7f5bc019367e46aa4533bb6ae71ec7f339cea20d1efb2a988f7f2060d3e07604`；资格
20 次执行先以 PID 4345、FD `83→83` 通过。正式 Day 1 在 PID 8080 完成 250 次
measured smoke / 500 次 Lua 执行，连同 10 次 warmup 共 260 个确定性 PASS marker；
0/25/.../250 的全部 FD 样本及首尾三连稳定采样均为 83，fail-stop、相关 ANR/crash、
instrumentation failure 均为 0。Day-1 receipt SHA-256 为
`6b7ce09b221c3165428adff234cede20b7bb61b5aad215a3e491c180ebe3d4d8`，state 诚实保持
`in_progress` / `completedDays=1`，Day 2 只能在 2026-08-26 Asia/Shanghai 运行；本项
仍保持未勾选。完整回执摘要见
[`docs/production-soak-round-2.md`](docs/production-soak-round-2.md)。

R4-E 生产 soak Round 2 Day 2 证据 (2026-08-26): 同一 boot ID 与 PID 8080 下完成
250 次 measured smoke / 500 次 Lua 执行，无 warmup；250 个 run header、PASS marker、
`result=pass` 与 `INSTRUMENTATION_CODE: -1` 一一对应。0/25/.../250 的全部 FD 样本及
首尾三连稳定采样仍均为 83，冻结安装态未改变，fail-stop、相关 ANR/crash、
instrumentation failure 均为 0。Day-2 receipt SHA-256 为
`9b89cb129ddb2e619b98954042e8fb18fc942867563cc25278c7819ad1ccbc61`。同日稍早一次启动
在只读拉取已安装 Host、首个 instrumentation 之前中止；临时拉取文件的 SHA-256 仍为
冻结 Host digest，且未安装、未清 logcat、未运行 smoke、未推进 state。完整重新校验后
才执行上述正式 Day 2，因此该计量前中止不计为 partial day，也未触发冻结的失效条件。
state 诚实保持 `in_progress` / `completedDays=2`，Day 3 只能在 2026-08-27
Asia/Shanghai 运行；本项仍保持未勾选。完整证据及临时中止审计见
[`docs/production-soak-round-2.md`](docs/production-soak-round-2.md)。

R4-E 长时测试裁撤记录 (2026-08-26): 应 owner 明确决定，“设备安装 + 宿主端到端
冒烟归档”与“生产 soak 计划”两项自本日起退出发布门禁，项目转为功能优先、缺陷
按报告即时修复 (fix-on-report)。裁撤不是完成：两项均不勾选 [x]；已有的 x86_64
模拟器冒烟、API 24/31/36 矩阵、升级/回滚矩阵与 soak Round 2 两个有效日
(0 fail-stop、FD 83→83) 证据按原文保留；arm64-v8a 真机冒烟与 7 日完整 soak
未执行，也不再声称。`docs/production-soak-plan.md` 标注 RETIRED，
`docs/production-soak-round-2.md` 在 Day 2/7 之后关闭，`state.json` 诚实保持
`in_progress` / `completedDays=2`，不被伪造为 complete。soak 执行器
`tools/run_production_soak.ps1` 与冻结标准原样保留，供未来自愿重启；重启需按原
规则使用新 RoundId 从 Day 1 开始。

R4-E rc.2 签名打包证据 (2026-08-25): 干净 implementation revision
`a0ae189ac8cba042848412a671c91b0b8a7c44e1` 的提交数/versionCode 同为 43；强化后的
`build_runnable_provider.ps1` 强制 `:app:clean`、`--rerun-tasks`、`--offline`，并
自动调用严格 release artifact gate。59/59 JVM、R8、lintVital 与 87 个 fresh Gradle
任务通过；Provider=true、faultHarness=false、单签名、split/universal ABI 一致及
ZIP/ELF 16 KiB 对齐均通过。universal APK 为 1,299,883 bytes、SHA-256
`93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd`，arm64 APK 为
`257f4c4a9dceed4fc58e089651370abaaa1384cf09c5f69e6fb21f244d409210`，x86_64 APK 为
`c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12`，共同 signer
为 `31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`。回执精确绑定
revision 与 universal digest，且诚实保持 `artifactGateVerified=true`、
`deviceVerified=false`、`runtimeVerified=false`；未安装 APK、未发出 ADB 命令、未
触碰物理设备。完整制品表、native 摘要、Host comparator 边界与回执见
[`docs/release-candidate-rc2.md`](docs/release-candidate-rc2.md)。

R4-E rc.2 x86_64 模拟器半程证据 (2026-08-25): 仅对 API 37、16 KiB
`emulator-5554` 发出显式定向 ADB 命令；重新安装 versionCode 43 的 x86_64 split 后，
设备内 `base.apk` SHA-256 与候选精确一致，均为
`c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12`。干净 revision
`84dc0a24980deb43d4199bfecee3168e4401da78` 构造的同签名 Host instrumentation
versionCode 45 以真实 `org.autojs.autojs6` 为 target，测试 APK SHA-256 为
`705f1fb56127d807b8c7fcf759419de5f29592f4affd500444c730357f9ad762`。它先锁定 Host
5276/Provider 43、同签名、唯一 INFO/RUNTIME service 及 `:lua_runtime` 进程，再由真实
Host `LuaPluginScriptEngine` 连续执行 `return 7` 与 `device.info + console.log`；结果、
fixed-shape device、INFO console 均通过，最终输出
`LUA_HOST_OFFICIAL_SMOKE_PASS runId=rc2emu-install-20260825051216`，Provider 在安装后
以新 PID 6150 完成并保持存活。完整记录见
[`docs/release-candidate-rc2-emulator-smoke.md`](docs/release-candidate-rc2-emulator-smoke.md)。
arm64-v8a 真机未获授权且未触碰，因此“设备安装 + 宿主端到端冒烟归档”总项继续保持
未勾选，canonical universal receipt 也不改写。

R4-E rc.2 API 兼容矩阵证据 (2026-08-25): clean revision
`52e41233705d20275945b88589b501af4e4a36c1` 先移除 API 26-only
`Method.getParameterCount()`，以 `method.parameterTypes.size` 保持 API 24 可调用，并
通过 44/44 Python、59/59 JVM、host-lifecycle lint/assemble。随后同一个
`emulator-5564` 端口按顺序、非并发地启动 `DEX_R1_API24_X64`、`AVD_API_31_Play`、
`DEX_R1_API36_X64` 三个 x86_64 AVD；每档均安装 Host 5276、精确 rc.2 Provider 43
`c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12` 与同签名 smoke
APK 47 `19a4a0f0ffae07b08682f87a50e8c6625bfeeab737b51419ecf23f084dceb59f`。三档真实
Host 结果分别为 `rc2api24-20260825052417`、`rc2api31-20260825052654`、
`rc2api36-20260825052833`，均 `executions=2 discovery=pass result=pass console=pass`；
随后 Provider 卸载均确认 `packageAbsent=true runtimeAbsent=true`，专用 AVD 均已关闭，
物理设备未触碰。完整指纹、PID、制品与清理记录见
[`docs/release-candidate-rc2-api-matrix.md`](docs/release-candidate-rc2-api-matrix.md)。

R4-E 升级/回滚路径证据 (2026-08-25): clean implementation revision
`b3cae39f63561cea81392051a7e8b4361cac9129` 的提交数/lifecycle APK versionCode 同为
50，44/44 Python 与 lifecycle lint/assemble 通过。仅在 API 36 x86_64 专用
`emulator-5564` 上，以同一 signer 的精确 Host 5275/5276、Provider rc.1 versionCode
20、rc.2 versionCode 43 和真实 Host instrumentation 执行矩阵。rc.1 冒烟后通过
`adb install -r` 升级 rc.2，UID 10229 与 `firstInstallTime` 保持不变且升级后冒烟通过；
rc.2 卸载确认 `packageAbsent=true runtimeAbsent=true`，精确重装 digest
`c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12` 后再次通过。
Host 5275 对 rc.2 的真实 runtime-info probe 在 PID 5499 完成，随后于 engine init
确定性给出外层 `LUA_RUNTIME_UNAVAILABLE`、唯一 rejection
`HOST_VERSION_UNSUPPORTED` 与 `dispatch=not-entered`；恢复精确 Host 5276 后两次真实
engine 执行及 discovery/result/console 全部通过。终端输出
`RELEASE_UPGRADE_MATRIX_PASS`，随后专用 AVD 已关闭且物理设备未触碰。完整构建边界、
五项制品指纹、四个 case/run ID 与聚合回执见
[`docs/release-upgrade-matrix.md`](docs/release-upgrade-matrix.md)。本项不替代尚未授权的
arm64-v8a 物理设备冒烟，也不改写 canonical rc.2 universal receipt。

R4-E 公开发布物料证据 (2026-08-25): clean material revision
`40a727f346376ae5827e63a2915d21cdd0ac8d62` 的提交数/versionCode 同为 52。许可证
反向审计覆盖仓库 MIT、PUC Lua 5.4.8 MIT、Kotlin stdlib 2.3.20 与 JetBrains
annotations 13.0 Apache-2.0、AutoJs6 三份协议 AAR 的 MPL-2.0，以及由 Android NDK
r28c `-static-libstdc++` 嵌入的 LLVM runtime 条款；精确 NDK toolchain NOTICE 以
130,424 bytes、SHA-256
`f96f763beb66a7ba7a667647fc64c0226ace875e590c831fdd9579ec1c1d91e1` 入库。由于协议
锁 revision `3b7378758c5a4f68e8680a78cf2c541c23628489` 的公共 commit URL 审计时返回
HTTP 404，仓库保存三个模块共 35 个逐 Git blob 一致的对应源码文件，确定性树指纹为
`0f845025cc46041a138de869fefcbcdbcc742e7e0d2f2c08eeffe1f375b97a69`，并与三份 AAR
digest、MPL 全文共同 fail-closed 绑定。GitHub Release 草稿明确列出三份 rc.2 验证
基线 SHA-256、最低 Host versionCode 5276、ABI、signer 与未完成门禁，并以
`DRAFT — DO NOT PUBLISH` 阻止误发；tag 规则固定候选 `v0.1.0-rc.N`、稳定版
`v0.1.0`、annotated/immutable 且禁止移动或复用。46/46 Python 敌意测试、59/59 JVM
离线门禁、native 双 ABI assemble/lint 通过；新 gate 同时拒绝额外 native 库并将 ELF
依赖闭合为 `libc/libdl/liblog/libm`。完整清单、人工确认边界及草稿分别见
[`docs/public-release-policy.md`](docs/public-release-policy.md) 与
[`docs/release-v0.1.0-rc.2-draft.md`](docs/release-v0.1.0-rc.2-draft.md)。本项仅表示
仓库物料成稿：当前 checkout 未配置 Git remote，未创建/推送 tag，未创建 GitHub
Release 或上传 APK；arm64-v8a 真机与首轮 soak 仍未完成，物理设备未触碰。

## R4-F — 门禁与 CI 强化 (全程可离线)

- [x] **verifier 覆盖新边界**。`verify_repository.py` 增加: print/warn
  受控桥的存在性检查 (防止未来误开无限制 print)、R4 新 capability 的
  fixed-shape 桥 token 检查、测试计数单一 source of truth (从一处常量
  派生, 消灭六处硬编码)。
  完成判据: `tools/tests` 相应新增敌意用例; 套件保持全绿。
- [x] **本地一键离线门禁脚本**。新增 `tools/verify_local.ps1`: 串联
  python verifier → unittest → `gradlew --offline testDebugUnitTest`,
  任何一步失败即停; 明确不触网。
  完成判据: 脚本入库并在 README 记为标准本地门禁; 断网环境实测通过。
- [x] **CI 网络韧性**。GitHub Actions 中为 Gradle/SDK 下载步骤增加重试
  (最多 2 次) 与依赖缓存 (`gradle/actions/setup-gradle` 缓存已内建,
  补 sdkmanager 缓存), 降低远端 5xx 导致的红灯噪声。
  完成判据: workflow 更新入库; verifier 的 CI 文本检查同步放行。
- [x] **fault harness 门禁并入常规矩阵**。`verify_fault_harness_artifacts.ps1`
  目前独立存在; 将其运行前提与产出写入 README 并纳入发布前 checklist。
  完成判据: README/发布 checklist 引用该脚本; 参数与当前 harness 实现一致。

R4-F 本地证据 (2026-08-24): `verification.properties` 成为 JVM 测试计数唯一
source of truth；CI、debug/release artifact gate 与本地门禁均从中读取。
Python 静态/敌意套件扩展为 38 项，覆盖受控 console/工具 API、R4 决策记录、
capability 注册表、PFD/deadline、CI cache/retry、fault checklist、真实宿主
lifecycle target/模拟器/PID/watchdog 边界与 SSOT 篡改；
`tools/verify_local.ps1` 实测输出 `LOCAL_OFFLINE_GATE_PASS tests=49 ...
network=disabled`。

R4-F fault audit 修正 (2026-08-25): release-candidate Gradle guard 从笼统匹配
所有 `package*Release*` 任务收窄到实际 APK/AAB 产物任务；因此无签名的
`compileReleaseKotlin`、`processReleaseMainManifest`、`externalNativeBuildRelease`
与其 `packageReleaseResources` 中间依赖可以为物理排除审计生成证据，而
`assembleRelease`、`bundleRelease`、release APK/split package 仍强制经过
`requireReleaseCandidate`。规范 fault invocation 先执行 `:app:clean`，再以
`--rerun-tasks` 生成最小受审集合，使所有中间产物都晚于 invocation timestamp；
敌意用例会拒绝重新阻塞 audit intermediate、跳过清理或复用过期输出。

## 执行顺序建议

1. **R4-0 全部完成后再动其他区块** (它们是当前门禁红/绿的直接决定项)。
2. R4-A 与 R4-F 可并行推进 (均为本地/离线工作)。
3. R4-B/R4-C 每项先出决策记录再写代码, 保持"能力默认关闭 + fail-closed"
   的既有纪律。
4. R4-E 依赖 R4-0 与 R4-A 的大部分项, 且含联网/真机步骤, 统一安排在
   网络与设备条件良好的窗口。
