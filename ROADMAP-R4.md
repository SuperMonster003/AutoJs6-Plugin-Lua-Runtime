# Lua 运行时插件 Roadmap — R4 阶段 (能力精进与发布收敛)

> 本文件承接 `ROADMAP.md` 中已完成的 R3-A/B/C/D 阶段。勾选规则与 R3 一致:
> 勾选仅表示对应的源码、测试或静态证据已存在并可复核, 不代表设备、签名或
> 发布层面的验收。每一项均给出"完成判据", 以便逐项 Check。
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
- [ ] **OS 级 FD 泄漏证据**。在 androidTest 中于批量执行前后对比
  `/proc/self/fd` 计数 (含故意失败、取消、回调死亡各路径)。
  完成判据: 仪器测试断言 FD 计数回落到基线; 用例并入故障恢复矩阵。
- [x] **极小 deadline 与 oneway start 竞争**。R3 记录: 极小请求超时可能在
  oneway `start()` 尚未到达时就过期。为该窗口定义确定性行为 (统一走
  `expireIfNotStarted` 的 TIMEOUT 终态, 不得出现无终态会话)。
  完成判据: JVM 测试固定 1ms deadline + 延迟 start, 断言唯一终态与
  watchdog 租约释放。
- [ ] **阻塞型非常规源 FD 的敌意遏制**。宿主传入 pipe/socket 等阻塞 FD 时,
  读取阶段必须受 deadline/watchdog 约束而非无限阻塞。
  完成判据: androidTest 用 pipe 写端悬挂构造阻塞读, 断言在 deadline +
  宽限期内进程被 fail-stop 或会话进入 TIMEOUT 终态。
- [ ] **更广的对端死亡矩阵**。补齐 R3 明示未测的 update/uninstall/对端死亡
  case: 宿主更新、宿主卸载重装、broker 与 callback 分别单独死亡。
  完成判据: 仪器矩阵各 case 至少一条用例, 断言会话清理与 watchdog 不误杀
  后续执行。
- [ ] **十语言 README/changelog/资源工作流**。R3-A 唯一未勾选项。先落地
  zh-CN/en 双语生成脚手架 (脚本生成、单一 source of truth), 其余八语言
  仅在有真实翻译输入时扩展, 不引入机器占位文本。
  完成判据: 生成脚本 + 双语 README 入库; verifier 增加生成物一致性检查。

R4-A 本地账目/竞争证据 (2026-08-24): JVM 套件新增四条 PFD 逻辑账目用例，
分别覆盖 create 异常、BUSY 不复制、source read/controller finish 双重关闭幂等、
Host callback payload 与 V1 零返回 payload，所有分类进/出严格配平；Android
测试另直接断言非法 Host payload 的真实 PFD 已关闭。1ms deadline + 延迟
`start()` 固定产生唯一 `TIMEOUT/QUEUE` 终态，并释放未派发的 watchdog 与
start lease。OS 级 FD、阻塞 pipe 与更广对端死亡仍由下方未勾选项承接。

## R4-B — 语言能力精进 (受控扩展, 每项默认关闭直至证据齐备)

- [ ] **协程库受控引入**。R3 因 hook 继承性未验证而排除 `lcorolib.c`。
  先写 native 层证据: 子协程继承 count hook 与 deadline 检查、协程内
  cancel 生效、跨 resume/yield 的 allocator 记账不破。
  完成判据: CMake 源清单加入 `lcorolib.c` (仅此一文件);
  androidTest 覆盖协程内超时/取消/OOM 三路径; `linit.c` 仍排除。
- [ ] **受控 `pcall`/`xpcall` 评估**。当前移除二者是为防脚本吞掉 hook 取消
  错误。评估替代方案: 提供包装版 `pcall`, 对 watchdog/cancel 类错误
  (以私有 sentinel 标识) 强制重抛, 普通业务错误可捕获。
  完成判据: 决策记录写入 `docs/` (采纳或明确拒绝均可勾选);
  若采纳, 附带"取消错误不可吞"的 native 测试。
- [ ] **多返回值/表结果的 V2 结果模型草案**。当前结果仅限单标量。
  在协议 wire API 允许的范围内起草 V2: 表→受限 LuaValue 树 (复用参数侧
  已有的 depth/nodes/bytes 配额), 多返回值→数组。仅出设计文档与
  兼容性分析, 不动 frozen 协议。
  完成判据: `docs/result-model-v2.md` 完成, 含与宿主协议版本协商方案;
  明确标注需要宿主侧配合的部分。
- [ ] **`string.format`/`os.time` 类安全子集调研**。梳理脚本实际高频诉求
  (时间戳、随机数), 评估以 `autojs` 模块受控 API 形式提供 (如
  `autojs.now()`), 而非开放 `os` 库。
  完成判据: 调研记录 + 决定清单入库; 采纳项各配 JNI 边界测试。
- [ ] **模块快照能力增强**。当前 `module.snapshot.v1` 仅支持平面 ASCII 名。
  评估 v2: 点分层级名 (`a.b.c`)、每执行模块总量上限、快照缓存命中指标。
  完成判据: 能力协商设计文档; 若实现, 名称校验正则与循环加载 fail-closed
  测试同步扩展。

## R4-C — 宿主能力面扩展 (capability 逐个白名单化)

- [ ] **`console` 分级增强**。评估 `console.warn`/`console.info` 独立
  wire 流或以现有双流 (stdout/stderr) 映射; 保持序列/额度/分块限制不变。
  完成判据: 决策记录; 若实现, `emit_autojs_console` 常量与协议枚举对齐,
  JVM 测试覆盖新流。
- [ ] **`toast` 能力 (`ui.toast.v1`)**。最小 UI 反馈能力: 单 string 参数,
  长度上限, 频率限制 (每执行 N 次), Binder 单次派发不重试。
  完成判据: 与 `device.info` 相同规格的 fixed-shape 桥 + 校验器 +
  仪器冒烟; capability 出现在 `LuaProviderMetadata.capabilities`。
- [ ] **`storage` 键值能力草案 (`storage.kv.v1`)**。执行间持久化的
  受控 KV: 键 ASCII 白名单、值走既有 LuaValue 配额、宿主侧落盘。
  完成判据: 能力设计文档 (含配额与清除策略); 实现另立勾选项。
- [ ] **能力协商回归矩阵**。宿主未授予某 capability 时, 脚本调用对应 API
  必须得到确定性 DENIED 错误而非挂起或崩溃。
  完成判据: 对每个已注册 capability 各一条 JVM 测试 (授予/未授予两态)。

## R4-D — 可观测性与诊断

- [ ] **结构化执行统计**。在终态回调中附带 (或经 `getRuntimeInfo` 暴露)
  每次执行的峰值内存、指令 hook 触发数、输出字节数、耗时分解
  (load/execute/teardown)。仅统计, 不含脚本内容。
  完成判据: 协议允许范围内的字段设计 + JVM 断言; 越界则记录为
  "需宿主协议演进"并给出字段清单。
- [ ] **崩溃诊断落盘**。native crash / watchdog fail-stop 前, 将最小诊断
  (failure kind、阶段、脚本 hash 前 8 字节) 写入进程私有目录, 下次
  `getRuntimeInfo` 可上报"上次异常终止"标志。
  完成判据: 仪器测试注入 fault harness 崩溃, 重启后读到诊断标志。
- [ ] **watchdog 事件可追溯**。为 `DEADLINE_CLEANUP_EXPIRED`/
  `STOP_CLEANUP_EXPIRED`/`WATCHDOG_CONTROL_FAILURE` 三类 fail-stop 附带
  logcat 结构化标签, 便于宿主侧聚合。
  完成判据: 标签常量 + 单测断言日志路径被调用 (可注入 logger)。

## R4-E — 发布工程收敛 (从 rc 走向可公开发布)

- [ ] **rc.2 候选重建**。基于 R4-0 修复后的干净 HEAD 重建签名候选:
  `tools/build_runnable_provider.ps1` 全程通过, 产出新的 receipt。
  完成判据: receipt 中 revision 为新 HEAD; `deviceVerified`/
  `runtimeVerified` 按流程翻转为 true 的设备证据单独归档。
- [ ] **设备安装 + 宿主端到端冒烟归档**。在真机 (arm64-v8a) 与模拟器
  (x86_64) 各完成一次: 安装 → Plugin Center 发现 → 一次真实 Lua 脚本
  执行 → 结果/console 回传正确。
  完成判据: 归档带日期与 APK SHA-256 的证据记录 (沿用 R3 证据文体)。
- [ ] **API 兼容矩阵扩展**。在 API 24 (minSdk)、31、36 (targetSdk) 三档
  完成安装/执行/卸载回归 (R3 曾做 24/31/37 样本, 需对齐当前 targetSdk=36)。
  完成判据: 三档证据归档; 发现的兼容问题各开独立勾选项。
- [ ] **升级/回滚路径证据**。rc.1 → rc.2 覆盖安装升级、rc.2 卸载重装、
  以及宿主 5276 与更早版本 (应拒绝 dispatch) 的组合行为。
  完成判据: 每个组合一条归档记录; 早宿主拒绝路径给出确定性错误码。
- [ ] **公开发布物料**。LICENSE/THIRD_PARTY_NOTICES 复核、GitHub Release
  草稿 (含 SHA-256 与最低宿主版本声明)、公开 tag 命名方案
  (`v0.1.0` 系列) 。(联网项, 放在网络良好窗口执行)
  完成判据: 物料在仓库内成稿; 实际发布动作单独人工确认。
- [ ] **生产 soak 计划**。定义 soak 通过标准 (连续 N 天日常脚本零
  fail-stop、零 FD 增长、零 ANR), 并按标准执行首轮。
  完成判据: soak 标准文档 + 首轮结果记录。

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
Python 静态/敌意套件扩展为 31 项，覆盖受控 `print`/`warn`、capability 注册表、
PFD/deadline、CI cache/retry、fault checklist 与 SSOT 篡改；
`tools/verify_local.ps1` 实测输出 `LOCAL_OFFLINE_GATE_PASS tests=48 ...
network=disabled`。

## 执行顺序建议

1. **R4-0 全部完成后再动其他区块** (它们是当前门禁红/绿的直接决定项)。
2. R4-A 与 R4-F 可并行推进 (均为本地/离线工作)。
3. R4-B/R4-C 每项先出决策记录再写代码, 保持"能力默认关闭 + fail-closed"
   的既有纪律。
4. R4-E 依赖 R4-0 与 R4-A 的大部分项, 且含联网/真机步骤, 统一安排在
   网络与设备条件良好的窗口。
