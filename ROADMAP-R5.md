# Lua 运行时插件 Roadmap — R5 阶段 (功能优先)

> 本文件承接 [`ROADMAP-R4.md`](ROADMAP-R4.md) 中已完成的 R4 阶段.
> 勾选仅表示对应源码, 测试或静态证据已存在并可复核, 不代表设备, 签名或
> 发布层面的验收. 每一项均给出"完成判据".
>
> 验证约定: Gradle 验证默认附加 `--offline`; 纯静态验证优先使用
> `python tools/verify_repository.py --require-build-ready` 与
> `python -m unittest discover -s tools/tests`. 需要网络, 设备或外部签名材料的
> 步骤单独标注, 且不得用静态证据替代.

## R5-0 — 显式构建变体

- [x] **以显式变体取代 Boolean 构建模式**. 四个
  `-Pautojs.lua.*.enabled` 开关已完整移除, 避免继续表达实际不受支持的组合状态.
  原生 Lua 现在是所有变体的必备能力; `providerDebug` 是普通可运行开发构建,
  `providerRelease` 是唯一发布变体. 隔离的 `nativeTestDebug` 与
  `faultTestDebug` 使用独立 application ID, 并从合并 manifest 中物理移除两个
  生产发现服务; 只有 `faultTestDebug` 会编译 fault 服务, 恢复 instrumentation
  与破坏性 JNI symbol.
  完成判据: 非 Provider release 变体在任务创建前禁用; Provider APK/AAB 任务
  继续要求 RC 版本格式; 未提供签名材料的 Gradle 聚合只能生成不可发布的
  unsigned 制品; CI, 本地门禁, 发布脚本, 制品 verifier 与静态门禁均按具体
  variant task 工作, 并拒绝旧属性重新进入活跃路径.

R5-0 本地证据 (2026-08-26): `providerDebug/providerRelease/nativeTestDebug/
faultTestDebug` 已成为唯一受支持的行为选择面. instrumentation 源码分别迁入
`androidTestNativeTest` 与 `androidTestFaultTest`; fault service/JNI 仅属于
`faultTest`; `nativeTest` 与 `faultTest` manifest 均以 `tools:node="remove"`
排除 INFO/RUNTIME 服务. release 聚合可以生成 unsigned Provider, 严格发布脚本
仍要求外部同签名材料并执行完整制品门禁.

## R5-A — 使用者文档与家族多语言机制

- [x] **面向使用者重写 README 与 CHANGELOG**. README 以"功能, 四问式快速
  上手, 使用示例, 脚本 API, 限制"为前半部, 将维护者门禁后置; CHANGELOG 使用
  `提示/新增/修复/优化/依赖` 行内标签按候选版本重组. 候选身份,
  fault-harness 检查表, 文本加载器和许可证摘要等发布边界继续保留.
  完成判据: 10 种语言具有相同 JSON key 与列表 shape; 生成器检查通过;
  `verify_repository.py --require-build-ready` 与 `tools/tests` 全绿.
- [x] **迁移到兄弟插件统一的十语言生成机制**. 使用
  `.python/generate_markdown.py`, `.readme/` 与 `.changelog/`; 语言集合固定为
  `zh-Hans`, `zh-Hant-HK`, `zh-Hant-TW`, `en`, `fr`, `es`, `ja`, `ko`, `ru`,
  `ar`, 根 `README.md` 默认采用 `zh-Hans`. README 生成到 `.readme/`, CHANGELOG
  生成到 `app/src/main/assets/doc/`, 中文 Android alias 与兄弟项目一致.
  Android `strings.xml` 由标准 `values-*` 目录独立维护, 不再与 Markdown 生成器
  共用一份仓库特有 manifest.
  完成判据: `python .\.python\generate_markdown.py --check` 输出
  `MARKDOWN_OK languages=10 artifacts=25 mode=check`; 根 README 与
  `.readme/README-zh-Hans.md` 逐字节一致; 10 套 Android 本地化资源 key 对齐;
  仓库中不再存在旧 `localization/`, `tools/generate_localized_content.py`,
  `README.zh-CN.md` 或根级 `CHANGELOG*.md`.

R5-A 多语言迁移证据 (2026-08-27): 共享模板与 10 份完整语言 JSON 已取代旧
en/zh-CN active + 8 planned 槽位模型. 生成器 fail-closed 检查重复 JSON key,
语言 key/类型/列表长度, changelog 版本与分类 shape, 中文全角标点, 翻译占位标记,
Android string inventory, 版本一致性及 25 个生成物漂移. 原先单列的
"zh-TW / zh-HK 获得真实翻译后转 active"待办不再存在: 两个繁体区域和其余语言
已经是同一标准集合中的完整输入, 不是计划槽位.

## R5-B — Provider 语言边界精进 (可独立关闭)

- [x] **受控 `pcall` 重新评估** (按 `docs/pcall-boundary-decision.md` 的
  Reconsideration gate). R5 再次拒绝脚本可见 `pcall`/`xpcall`: 当前没有提交
  能阻止 stock `xpcall` handler 在控制事件后运行, 又能覆盖 yieldable
  continuation 与 OOM 构造路径的可审计 wrapper. 保留二者缺席比引入仅靠错误文本
  或 watchdog 收尾的 catch 边界更安全.
  完成判据: main/coroutine 可见性, nested catch, OOM, xpcall handler,
  deadline 与 cancel 矩阵的 native instrumentation 证据齐备; 再次拒绝的决策记录
  入库; `TerminationReason` 与 sticky allocator failure 继续在任何结果装箱前优先.

R5-B 本地证据 (2026-08-27): `NativeLuaRuntimeInstrumentationTest` 新增 main 与
coroutine 中 `pcall`/`xpcall` 同时缺席、嵌套 catch 体不能执行 Host call、
`xpcall` message handler 不能执行 Host call 三项显式用例. 既有 coroutine
deadline/cancel 与 caught-OOM 后拒绝成功结果、进程立即复用用例共同组成重新评估
矩阵. 生产边界继续逐项移除两个全局函数, verifier 同时锁定源码与测试证据.
API 37 `emulator-5560` (`x86_64,arm64-v8a`, 16 KiB page) 上完整类通过
20/20; 仅安装 `.native_test` 与其 test APK, 未触碰任何实体设备.

## R5-C — 需宿主协同的 capability (Provider 单方不得关闭)

- [ ] **`storage.kv.v1` 协同实现** (`docs/storage-kv-v1.md`).
  阻塞判据: 冻结宿主 revision `3b7378758c5a4f68e8680a78cf2c541c23628489`
  只启用 `device.info` 与 `module.snapshot.v1`, 没有稳定 script principal、存储
  dispatcher 或持久化层; protocol 1.0 的 `TaggedWire` 单文档上限为 256 KiB,
  不能承载原设计允许的 256 KiB 逻辑值加 envelope, 更不能承载 320 KiB 编码值.
  完成判据: 先由宿主冻结 principal/持久化/每 principal 256 keys 与 2 MiB 配额,
  并修订可传输的单值上限或协议通道; 再落地 Provider Kotlin/JNI fixed-shape 桥、
  每执行 64 次操作/32 次 mutation、清除语义、授予/拒绝对称测试及 mutation
  不重试证据. 在此之前不得广告 `storage.kv.v1`.
- [ ] **`module.snapshot.v2` 点分层级模块名协同实现** (设计已冻结于
  `docs/module-snapshot-v2.md`).
  完成判据: 宿主能在 session admission 冻结 V1/V2 选择并授予 V2; Provider
  点分 ASCII 正则、16 段/255 字节名称上限、64 模块/512 KiB 聚合配额落地;
  循环加载 fail-closed 测试同步扩展; 测试禁止 V2 错误回退到 V1.
- [ ] **`ui.toast.v1` 宿主端到端可见交付** (Provider 侧已完成并归档).
  完成判据: 启用该 capability 的宿主 revision 与仅在模拟器或明确授权设备上的
  真实冒烟证据归档 (可见 Toast + 配额/拒绝行为不回退); Provider 侧若需改动则
  另附回归用例.

R5-C 依赖审计 (2026-08-27): 本仓只锁定 protocol AAR, 不拥有宿主 dispatcher、
principal 或持久化数据库. 测试内的 fake broker 只能证明 Provider request/response
形状, 不能替代宿主命名空间隔离、耐久提交、重启、数据清除或迁移证据. 因此这三项
保持未勾选, 也不会以 Provider metadata 广告来冒充端到端交付.

## R5-D — 需宿主协议演进的能力 (protocol 1.1 协同, 单方无法关闭)

- [ ] **结果模型 V2 落地** (`docs/result-model-v2.md`).
  完成判据: protocol 1.1 + `result.model.v2` 双重协商在宿主与 Provider 两侧
  落地; 有界 LuaValue tree 与 ordered returns 各配 JVM/native 测试;
  未协商时保持 V1 行为逐字节不变.
- [ ] **执行统计 `execution.stats.v1` 落地** (`docs/execution-statistics-v1.md`).
  完成判据: 七字段清单, 六位 validity mask 与三个终态父 tag 按设计实现;
  仅统计不含脚本内容; 未协商时不采集, 不广告.

## R5-E — 发布执行

> 以下是联网项, 统一安排在网络良好窗口. 全程留意 Cloudflare 502/524/529,
> 尤以 524 超时为甚; 失败时退避重试, 不改变已冻结流程.

- [ ] **远程仓库与不可变 tag**. 按 `docs/public-release-policy.md` 配置
  remote 并创建 annotated tag (候选 `v0.1.0-rc.N` 系列), 禁止移动或复用.
  完成判据: remote 与 tag 存在且指向经过完整门禁的干净 revision;
  tag 规则与政策文档一致.
- [ ] **GitHub Release 正式发布**. 以 `docs/release-v0.1.0-rc.2-draft.md`
  为底稿, 附三制品 SHA-256, 最低宿主版本 5276 声明与已知限制.
  完成判据: Release 页面内容与草稿一致; 移除 `DRAFT — DO NOT PUBLISH`
  阻止标记的动作由人工显式确认执行, 不由自动化代做.

## 执行顺序建议

1. R5-A 与 R5-0 已完成, 后续文档和门禁均以显式 variant 与十语言生成机制为准.
2. R5-B 的 Provider 独立项先更新决策记录再实现, 保持 fail-closed; 当前 pcall
   重新评估已经关闭.
3. R5-C 先取得宿主 capability 实现与稳定 principal/session 选择证据, Provider
   单方不得广告未完成能力.
4. R5-D 必须与 Host/protocol 1.1 同步演进, 未协商时不得采集或广告.
5. R5-E 只在干净 revision, 完整门禁和人工发布授权同时具备时执行.

缺陷处理策略: 使用中发现的问题按报告立即修复, 每次修复附最小回归证据
(JVM 或 instrumentation 用例), 不再设置长时测试前置门禁.
