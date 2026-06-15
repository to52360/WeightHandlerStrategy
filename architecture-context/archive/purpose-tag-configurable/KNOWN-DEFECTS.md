# KNOWN-DEFECTS: purpose-tag-configurable

> 以下为用途标签可配置化改造中已收敛但与"理想状态"存在差距的已知缺陷。
> 用于日后评估是否修复，不在本轮实施中处理。

## D-001: 评估树 GROUP 绑定依赖分组管理，可观测性未验证

- 类型: ⚠️ 可接受
- 位置: `SqliteTreeConfigProvider.filterBindings()` —
  `configUi/src/main/kotlin/lin/provider/SqliteTreeConfigProvider.kt:50-69`
- 现象: GROUP 绑定的启用/禁用跟随 `CardManagerEntity.enabled`（通过 `CardGroupRepository.findManagers(onlyEnabled=true)`
  过滤）。分组被禁用时，其关联的评估树绑定在 SPI 边界静默删除，引擎层无感知。
- 绕过方式: 当前实现是设计意图——过滤在 SPI Provider 边界完成。引擎层不做启用/禁用判断，保持简洁。
- 待评估项:
    1. 分组管理状态变更（启用→禁用）后，评估树是否会被重新加载？（热更新路径是否存在）
    2. 如果分组被误禁用，评估树挂载静默消失，日志中仅有一条 `debug` 级别记录。排查链路是否需要在特定场景下提升为 `warn`？
    3. 分组管理与评估树绑定之间是否需要更显式的 UI 关联提示（例如在分组管理界面标注"此分组关联 N 个评估树"）？
- 记录时间: 2026-06-03

## D-002: 评估树绑定与分组禁用的关联无冗余记录，DB 不自解释

- 类型: ⚠️ 可接受
- 位置: `tree_config` 表 / `SqliteTreeConfigProvider.filterBindings()`
- 现象: `tree_config.configData` 始终存储原始全部绑定，`bindingsSummary` 也是保存时的原始值。分组被禁用后，绑定在运行时被内存过滤掉，但
  DB 中无任何字段记录"哪些绑定因目标禁用而失效"。排查需要跨表推理（读 `tree_config` → 解析 JSON → 对照
  `card_group_manager.enabled`）。
- 绕过方式: 保持现状，排查时手工查 `card_group_manager` 表。
- 建议方案: `tree_config` 表加 `effective_bindings_summary` 列，在 `filterBindings()` 过滤后回写快照。
- 待评估项:
    1. `effective_bindings_summary` 是否应该放在 `tree_config` 表，还是另建关联表（如 `tree_config_binding_status`）更合理？
    2. 分组 `enabled` 状态变更后，effective 快照的重新计算时机与触发方式。
    3. 是否需要另外的 `PURPOSE_TAG` 绑定的有效快照（当前过滤依赖 `PurposeTagTreeBindingPolicy` 内存对象）。
- 记录时间: 2026-06-03

> TRACKER.md 创建后需将以上缺陷追加为任务。
