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
- 将 `console.info`/`console.warn` 作为 stdout/stderr 别名加入，不扩展冻结的双流
  wire 枚举。
- 形成仅设计且不广告的 `module.snapshot.v2`，明确点分 ASCII 名、每执行聚合配额
  与缓存指标。
- 将 Host 模块 capability 不可用时的原生 instrumentation 预期从 Lua runtime
  错误修正为 `HOST_CAPABILITY`。

## 0.1.0-rc.1 — 历史本地候选版本

- 构建并签名首个启用 Provider 的本地候选制品。
- 为该历史提交归档定向的设备、ABI、兼容性与回滚证据。
- 未创建公开 tag，也未执行公开发布。
