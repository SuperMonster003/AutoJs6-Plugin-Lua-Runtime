# 变更记录

本文件记录仓库的重要检查点。项目尚未公开发布；候选版本标签只表示本地验证
制品，不代表已经对外提供下载或获得生产发布资格。

## 未发布

- 为传入、复制、回调和结果 `ParcelFileDescriptor` 所有权增加精确的逻辑记账。
- 让启动前 deadline 过期稳定地产生一个位于队列阶段的 `TIMEOUT` 终态。
- 将 JVM 测试数量收敛到 `verification.properties`，并加入仓库自带的离线总门禁。
- 使用固定 Android SDK 缓存和有界重试循环强化 CI。
- 建立经过审阅的英文/简体中文文档与 Android 资源生成工作流；其余八个语言槽位
  在获得真实且经过审阅的翻译之前保持为空。
- 记录 R4 中继续关闭 Lua 层 `pcall`/`xpcall` 的决策，并起草需要宿主协同演进的
  V2 结构化及多返回值模型。
- 新增零参数 `autojs.now()`，保留有界的 `string.format` 与伪随机能力，并要求
  `math.randomseed` 使用显式整数 seed，避免返回上游实现中的状态地址 seed。
- 仅纳入 PUC Lua 5.4.8 `lcorolib.c`，在 coroutine yield/resume 之间保持继承的
  deadline/取消 hook 与同一个 allocator；被子协程捕获的 OOM 仍会粘性映射为
  `MEMORY_LIMIT`。
- 将 `console.info`/`console.warn` 作为 stdout/stderr 别名加入，不扩展冻结的双流
  wire 枚举。
- 形成仅设计且不广告的 `module.snapshot.v2`，明确点分 ASCII 名、每执行聚合配额
  与缓存指标。
- 将 Host 模块 capability 不可用时的原生 instrumentation 预期从 Lua runtime
  错误修正为 `HOST_CAPABILITY`。
- 为所有已注册 Host capability 增加对称的授予/拒绝 JVM 覆盖；拒绝路径保持有界，
  并从 `DENIED` 稳定映射为 `HOST_CAPABILITY`。
- 新增 Provider 侧 `ui.toast.v1` 固定桥：请求/回执闭合为
  `{text=string}` / `{accepted=true}`，严格 UTF-8 上限 1,024 字节，每执行扣取四次
  额度，并且 Host 派发单次且绝不重试；可见交付仍需 Host 协同实现。
- 形成仅设计且不广告的 `storage.kv.v1` 契约，固定稳定脚本 principal 隔离、有界
  键/值/操作、Host 侧原子持久化、显式清除语义，以及 Provider 不重试 mutation。
- 审计冻结的 Lua 终态/runtime-info 模型，将每执行统计明确标记为需要 Host 协议
  演进，冻结嵌套字段、validity mask、终态 tag 与 protocol 1.1 capability 协商，
  并加入 JVM 门禁，证明当前 V1 没有被误当作具备统计承载位。
- 新增原子、固定 20 字节的进程私有崩溃诊断，仅保存闭合 failure kind、执行阶段、
  源码 SHA-256 前 8 字节与校验和；runtime 重启后只上报不含内容的 runtime-info
  标志，下一次健康且已验证的 native 返回会同时清除标志和记录。
- 在每次拥有 token 的 watchdog fail-stop 前新增一条固定 `AutoJs6LuaWatchdog`
  logcat 事件，以闭合标签区分 deadline/stop/control 原因，不含执行内容；注入式 JVM
  用例覆盖日志路径，并保证 logger 失败不能阻断进程终止。
- 增加仅限 debug 的远端 `/proc/self/fd` 记账，证明批量成功、digest 失败、取消、
  callback 死亡和 broker 死亡路径均精确恢复到同一基线。
- 增加悬挂 pipe 源的 fail-stop/rebind 覆盖，以及用于 callback/broker 独立死亡的
  隔离 Binder peer 进程；两个 debug 服务均继续从 release 变体中物理排除。
- 将 release-candidate Gradle 门禁收窄到实际 APK/AAB 制品任务，允许 fault-harness
  排除审计生成无签名 release 中间产物，同时继续保护所有可发布 package；规范审计
  先用 `:app:clean` 移除旧构建输出，再通过 `--rerun-tasks` 强制生成本次调用的
  新鲜中间产物。
- 强化可运行 Provider 构建器：要求干净 revision 及版本/提交数一致，强制 clean
  离线重建，调用完整签名 release 制品门禁，并在回执中绑定 revision 与 APK
  SHA-256。

## 0.1.0-rc.1 — 历史本地候选版本

- 构建并签名首个启用 Provider 的本地候选制品。
- 为该历史提交归档定向的设备、ABI、兼容性与回滚证据。
- 未创建公开 tag，也未执行公开发布。
