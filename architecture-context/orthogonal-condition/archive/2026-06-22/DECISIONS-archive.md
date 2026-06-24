# 决策归档 - orthogonal-condition - 2026-06-22

> 归档日期: 2026-06-22
> 原位置: `g:\liw_work\jiaoBen\Deck-Plugin-Market\architecture-context\orthogonal-condition\DECISIONS.md`

## D-014: 真实参数化过滤数据源的推进与多映射体处理决策

- **状态**: ✅ 已锁定
- **背景**: 现有的 `HandCardCountSource` 仅是简单的数量占位演示。在实际卡牌决策中，我们需要诸如"
  我方战场上属于【野兽】种族的随从数量 >= 3"的精确判定。若为每个维度单独开发复杂的通用过滤参数类型，将造成引擎接口的过度耦合。
- **决定**: 放弃开发高度复杂的参数化过滤数据源（如 `CardFilterParams`
  全量过滤参数结构），改用多个具体的、职责单一的映射体分别处理种族和卡牌类型的检索需求，以维持设计的简洁。
- **设计细节**:
    - 在 `resolve(context, args)` 执行时，通过具体数据源或特定的映射逻辑，直接从 `WarContext` 对战场或手牌数据进行映射计算，保证各映射组件纯净高内聚。
- **日期**: 2026-06-16

## D-015: EvaluatorLeafSourceType 物理简化可行性与边界隔离权衡决策

- **状态**: ✅ 已锁定
- **背景**: 讨论是否应该将评估叶子来源类型 `EvaluatorLeafSourceType` 简并为 `RULE` 与 `CONDITION` 两个大类，以简化概念。
- **分析与评估结论**:
    1. **避免泄露无关细节**: 早期多重隔离的设计（为 UI 路由强行在 `sourceType` 保留 4 种类型）导致其他引擎模块需要知道 UI
       的具体配置细节，进而引入了复杂的类型隔离操作和一堆繁琐垫片。
    2. **类型化隔离原则**: 决定采用更底层的类型化隔离优化，减少非 UI 模块对无关配置细节的知晓。后续接口重构应贯彻此方针，避免多层包装隔离代码泛滥。
- **日期**: 2026-06-16

## D-016: 评估树 Prune 语义单一化与守卫三态模型 (阶段一)

- **状态**: ✅ 已锁定 | 一句话结论：引入 `EvalOutcome` 三态（Matched/Skipped/Pruned），将剪枝职责从 rule 移至守卫侧，
  `RuleResult.Prune` 移除。
- **背景**: 原 `RuleResult.Prune` 同时承载"逻辑不成立"（Or 全不命中、Not 取反）和"控制流剪枝"（终止整棵评估树）两个语义，在
  And/Or/Not 组合中互相冲突。rule 兼了"评分"与"剪枝"两个职责，需拆开。
- **决定**:
    1. **引入 `EvalOutcome` 三态**：`Matched(score, modifyCard?)` / `Skipped(score)` / `Pruned`
       。Matched=守卫通过+规则评分完成；Skipped=守卫未命中+兜底分；Pruned=控制流剪枝。
    2. **守卫仍返回 Boolean**（`ConditionLogic` 不变），叶子闭包根据 `guardMissBehavior` 配置将 Boolean 转为
       EvalOutcome：true→Matched(跑rule)；false+SCORE→Skipped(missValue)；false+PRUNE→Pruned。
    3. **`RuleResult.Prune` 移除**：rule 工厂只返回 `RuleResult.Continue`，不再有剪枝能力。
    4. **树执行语义更新**：
        - And：遍历所有子节点，遇 Pruned 短路；否则累加 score。全 Skipped→Skipped(总分)；至少一个 Matched→Matched(总分)。
        - Or：第一个 Matched 返回；遇 Pruned 返回；全 Skipped→Skipped(0)。
        - Not：Matched↔Skipped 反转（score 清零）；Pruned 透传。
        - Branch：condition 为 Boolean（不变），true→onTrue，false→onFalse。
    5. **叶子配置新增 `guardMissBehavior: GuardMissBehavior` 字段**（枚举 SCORE/PRUNE，默认 SCORE），UI 暴露选择器。
- **不动 `ConditionLogic` 的理由**：`ConditionLogic = () -> Boolean` 被
  ConditionCompiler、ConditionRegistry、PipelineAssembler 等广泛引用，全局改为 `() -> GuardResult` 风险高且 condition 不知
  missValue。GuardResult 语义在叶子层通过 `guardMissBehavior` 配置转换，编译器通过 `EvalOutcome` 类型隔离守卫与 rule 职责。
- **日期**: 2026-06-21

## D-017: 评估树替代条件树的方向规划 (阶段二，待实施)

- **状态**: ✅ 已锁定 | 一句话结论：阶段一稳定后，用"条件叶子（守卫+ScoreEffect）"在评估树 And/Or/Not/Branch
  中组合表达条件树语义，逐步废弃条件树模块。
- **背景**: 当前条件树模块（`ConditionCompiler`）与评估树共用 `LogicNode<L>` 骨架但语义不同（条件树=纯布尔逻辑，评估树=评分累积+短路）。阶段一统一了评估树的
  Matched/Skipped/Pruned 语义后，评估树已具备表达条件树逻辑的能力。
- **设计方向**:
    1. **不引入新叶子类型**：纯条件用已有 `ConditionLeafConfig` + `ConstantScore(0, missValue=0)`
       表达（命中给0分=纯门控，未命中给0分=无影响）。带分条件用 `ConstantScore(5, missValue=0)`，惩罚条件用
       `ConstantScore(0, missValue=-3)`。三种全用同一叶子类型，仅 ScoreEffect 参数不同。
    2. **守卫仍只返回 Boolean→EvalOutcome**，不承担评分。条件叶子命中=Matched(0)，未命中=Skipped(0)。
    3. **条件树→评估树映射**：And→AndNode；Or→OrNode；Not→NotNode；Branch(条件,T,F)→BranchNode(条件叶子,T子树,F子树)。
    4. **BranchNode 的 condition 升级**：当前 BranchNode.condition 为 `ConditionLogic`(Boolean)。阶段二可升级为返回
       `EvalOutcome`，Pass→onTrue，Skipped→onFalse，Pruned→Pruned。
- **待决策点**：
    - **And 的 Skipped 短路语义**：阶段一中 And 不短路（累加所有子节点 score）。若要替代条件树的逻辑与语义，And 应短路（任一
      Skipped→整体 Skipped，后续子节点不跑）。但这会改变当前评分累积行为，需在阶段二落地时评估影响面。
- **实施前提**：阶段一（D-016）稳定运行，无回归问题。
- **影响面**：条件树配置迁移、ConditionTreeLeafConfig 废弃、UI 调整、ConditionCompiler 逐步废弃。需单独起任务拆分。
- **日期**: 2026-06-21
