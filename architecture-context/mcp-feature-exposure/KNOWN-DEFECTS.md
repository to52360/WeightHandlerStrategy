# 已知缺陷 - mcp-feature-exposure

> **规则**：本文件始终保留在 Topic 根目录，记录架构演进与 MCP 工具链暴露过程中发现的待修补卡点或缺陷。

| 缺陷编号       | 现象与影响描述                                                                                                                          | 影响模块 / 工具                                 | 拟修补方案 / 计划                                           |
|----------------|-----------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------|-------------------------------------------------------------|
| **DEFECT-201** | `CreateDraftRequest` 中的 `bindingType=PURPOSE_TAG` 字段注明“暂无工具支持”，导致 AI 无法直接通过草稿池创建用途标签绑定的评估树。        | `AiDraftTreeToolProvider.kt` / `DraftModels.kt` | 计划在 **T-214** 补齐 PurposeTag 校验逻辑并移除文本限制。   |
| **DEFECT-202** | Combo 方案（`ComboPlanDefinition`）在 UI 中可进行增删改，但 MCP 工具链中完全缺失查询与编排接口，导致 AI 无法感知卡组的时序 Combo 策略。 | `ComboPlanWorkbench.kt` / `lin.mcp`             | 计划在 **T-201~T-203** 新增 `ComboPlanToolProvider` 解决。  |
| **DEFECT-203** | 评估树只能通过 `delete_evaluator_tree` 进行物理删除，缺失状态切换（enable/disable）工具，AI 在调试时可能误删有用配置。                  | `AiTreeConfigToolProvider.kt`                   | 计划在 **T-221** 新增 `toggle_evaluator_tree_status` 工具。 |
| **DEFECT-204** | `changeWeight`（来源:静态卡牌/Trie）与 `coreMutex`（来源: `ComboPlanDefinition`）数据源不一致，且 `combo_plan_definition` 兼顾出牌编排与起手留牌硬互斥，导致起手留牌变化点分散，需进一步评估架构收拢。 | `ComboDomain.kt` / `ChangeCardSelector.kt` / `ComboPlanDefinition.kt` | 放入架构复盘待评估项（计划在 Combo MCP 暴露重构时统一梳理边界）。 | |
