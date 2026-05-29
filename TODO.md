# 卡牌排序与持久化架构后续评估及待办事项 (TODO)

本文件记录了在第四版出牌时序编排与多模块 SQLite 持久层集成落地后，遗留的核心架构争议点与后续评估事项。

---

## 📋 核心评估列表

### 1. Provider 层层级嵌套过深评估 (Repository / Service / Provider)

* **当前现状**：
  在 `configUi` 模块的依赖链条中，一个底层的数据库请求往往会经历：
  `Koin 注入` -> `SqliteComboPlanDefinitionProvider` -> `ComboPlanDefinitionRepository` -> `JdbcTemplate` ->
  `SQLite 数据库`。
  部分场景甚至夹杂了 `Service` 中间层（例如 `ConditionTreeConfigService`）。
* **潜在痛点**：
    * 样板代码较多，跨模块跳转或新增字段时需要同时修改 Entity、Repository、Provider 多个类文件。
    * 相比引擎层极致纯净的“函数式”思想，持久层存在一定的“过度设计（Over-engineering）”嫌疑。
* **优化方向**：
    * **方向 A (维持现状)**：当配置端（JavaFX GUI）变得复杂时，Service 层可以承担 UI 数据校验、状态缓存和多源聚合逻辑，Repository
      只管基础读写，Provider 负责给引擎做只读翻译。
    * **方向 B (拍扁扁平化)**：若 UI 侧读写极其简单，可以直接让 `SqliteComboPlanDefinitionProvider` 持有 `JdbcTemplate`
      直接执行 SQL，彻底废弃 Repository 和 Entity 的中转，减少 50% 物理文件。

---

### 2. PurposeTag (战略用途) 到 UseStage (物理阶段) 的硬编码映射是否合理

* **当前现状**：
  在 `UseIntentDeriver.kt` 中，我们采用了硬编码转换逻辑：
  ```kotlin
  config.purposeTags.contains(PurposeTag.SAVE_LIFE) -> UseStage.SAVE_LIFE
  config.purposeTags.contains(PurposeTag.CLEAN) -> UseStage.CLEAN
  ```
* **潜在痛点**：
    * 硬编码映射导致战略用途（例如保命）与出牌物理排序的绑定关系是死板的。
    * 在某些高阶场景下，保命牌不一定非要在 `CLEAN` 阶段之后出，或者用户希望能通过 UI 动态调整这种映射。
* **优化方向**：
    * **方案 A (规则驱动)**：在后续“决策推演层 (Inference Engine)”引入后，利用“条件规则”去动态输出卡牌本回合的 `UseStage`
      ，而不是在 Deriver 中静态写死。
    * **方案 B (持久层配置)**：在 `card_use_config` 表中直接允许用户配置 `purpose_to_stage_mapping` 映射表，或者把这层映射配置化（如
      properties 文件），支持无缝动态扩展。

---

### 3. 使用关系强约束 (MustUseTogether & MustUseBefore) 一对一配对合理性评估

* **当前现状**：
  在 `ComboUseConstraintBuilder.kt` 中：
    * 对于强相邻 `MustUseTogether`，我们通过 `coreCards.zip(depCards)` 强制进行 **1对1 压缩配对**。
    * 对于非强相邻 `MustUseBefore`，我们暂时保留了原有的 `flatMap` 笛卡尔积（一对多/多对多拓扑关系）。
* **思考争论点**：
    * **`MustUseTogether` (强相邻)**：1对1 压缩在逻辑上非常清晰（如伺机待发1与刺骨1绑定，防止多张牌重叠成环），目前看来具备很强的实践价值，但仍需在实战对局中验证复杂手牌下的边缘情况。
    * **`MustUseBefore` (先后拓扑)**：是否真的有必要像强相邻一样也做 1对1 压缩？如果做了 1对1 压缩（例如 2个解场 1个过牌，如果
      1对1 压缩，多出来的解场牌会不会由于失去时序约束而乱序）？
* **后续评估要点**：
    * 在实战对局日志中，重点抓取并分析手牌同时存在**多张同名/同组依赖牌及核心牌**时的拓扑排序结果（是否存在死锁、成环、或者是多余卡牌出牌顺序错乱的现象）。
    * 进一步验证是否可以完全摒弃 `MustUseBefore`，仅依靠 `UseStage` 的细分阶段（或者微调 Stage 内部的权重）就能天然解决先后顺序，把系统负荷降到最低。
