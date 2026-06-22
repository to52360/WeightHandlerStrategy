# 决策记录 - orthogonal-condition

>
历史详情请阅读 [ARCHIVE-INDEX.md](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/architecture-context/orthogonal-condition/ARCHIVE-INDEX.md)

## 已归档决策快照 (详情见归档文件)

- [x] **D-008: 引擎防御性校验与职责边界的权衡** - 状态: ✅ 已锁定 | 一句话结论：保留装配与绑定期的 Fail-Fast
  防御拦截以保障运行时高可靠性。 (归档批次: 2026-06-16)
- [x] **D-009: 放弃特征路由自适应，拥抱多态多维强类型 ConditionPayload** - 状态: ✅ 已锁定 | 一句话结论：采用强类型多态
  `ConditionPayload.OrthogonalRef` 彻底隔离参数并支持 AST。 (归档批次: 2026-06-16)
- [x] **D-010: 规则树与评估树职责分离，并规划"规则（Rule）正交化"** - 状态: ✅ 已锁定 | 一句话结论：确立正交化大方向
  `Rule = 守卫条件 + 评分效应 + 控制动作`，将 Boolean 条件从算分中剥离。 (归档批次: 2026-06-16)
- [x] **D-011: ScoreEffect 三层叶子模型与 builtInFields 声明式生成** - 状态: ✅ 已锁定 | 一句话结论：区分 CONDITION（固定
  ConstantScore）、编码 Rule（动态 builtInFields）与配置型 Rule，简化 UI 并打通重构线。 (归档批次: 2026-06-16)
- [x] **D-012: Operator 与 评分效应参数的强类型约束规范** - 状态: ✅ 已锁定 | 一句话结论：统一 categories 常量，严禁使用
  `value class` 作为参数类型以防 Jackson 反序列化崩溃，并保留单字段包装类。 (归档批次: 2026-06-16)

---

## 活跃讨论与评估中决策

## D-013: 规则叶子类型多态简化与边界收拢方向 (待评估)

- **状态**: 🔶 待验证/待评估
- **背景**: 评估树叶子节点 `EvaluatorLeafSourceType` 目前定义了 4 种类型 (`RULE`, `ORTHOGONAL_RULE`, `CONDITION`,
  `CONDITION_TREE`)。为防范概念扩散，确保设计的高纯净度，讨论是否应简化为 `RULE` 与 `CONDITION` 两种核心类型。
- **讨论内容**:
    1. **评估树下拉框边界约束 (Q-013.1)**：有必要限制非法类型。例如 Branch 节点不能选择规则相关的 Leaf，因此需要在 UI
       策略中区分并过滤，或探索将当前大下拉框拆分为"分类下拉框"+"具体项下拉框"的多级筛选结构。
    2. **规则多态隔离 (Q-013.2)**：参考条件侧的 `ConditionPayload`（分为 `ConditionRef` 与 `OrthogonalRef` 两个子类），规则侧也可以设计
       `RulePayload`（子类分别为代表硬编码规则的 `RuleRef` 以及代表配置规则的 `OrthogonalRuleRef`
       ），将特定配置逻辑（如守卫、ScoreEffect 等）完全锁在 `OrthogonalRuleRef` 子类边界内，避免其扩散影响扁平的
       `EvaluatorLeafConfig` 结构。
- **后续规划**: 暂不在本次重构中实现此改动，将此设计方向作为架构待评估项（对应 `T-017`），并在后续阶段中进一步验证和设计。
- **日期**: 2026-06-16

## D-014: 真实参数化过滤数据源（如随从种族）的推进决策

- **状态**: ✅ 已锁定(搁置)(批注:改用多个映射体来处理)
- **背景**: 现有的 `HandCardCountSource`
  仅是简单的数量占位演示。在实际卡牌决策中，我们迫切需要诸如"我方战场上属于【野兽】种族的随从数量 >= 3"的精确过滤，如果每个过滤维度都开发一个
  SPI 实体，将导致严重的类爆炸。
- **决定**: 将开发"真实参数化过滤数据源"列为后续重点迭代任务（`T-018`）。
- **设计细节**:
    - 数据源在 `fields` 中声明如 `CardFilterParams(race: CardRaceEnum?, cardType: CardTypeEnum?)`。
    - 在 `resolve(context, args)` 执行时从 `WarContext` 的战场或手牌视图中执行参数化的 `filter` 匹配，返回真正动态过滤后的数据或计数。
- **日期**: 2026-06-16

## D-015: EvaluatorLeafSourceType 物理简化可行性与边界隔离权衡决策

- **状态**: ✅ 已锁定(搁置)(批注:已经类型化处理,导致其他模块也需要知道无关细节,从而进行一堆隔离一堆隔离操作)
- **背景**: 讨论是否应该将评估叶子来源类型 `EvaluatorLeafSourceType` 简并为 `RULE` 与 `CONDITION` 两个大类，以简化概念。
- **分析与评估结论**:
    1. **解析层兼容度**: 极高。在引入 Jackson 多态 Mixin 后，Jackson 物理上完全是自适应解析，不受外层 `sourceType` 枚举缩减的影响。
  2. **UI 联动复杂度**: 影响极大。如果直接缩减为 2 类，配置端 UI ComboBox 将难以直接通过 `sourceType`
     判别这是一个"普通代码规则"还是"配置型正交规则"；难以区分这是一个"单体代码条件"还是"引用的条件树"，进而需要去猜测
     `sourceId` 的特殊魔法值，会导致 UI 表单与弹窗控制层出现逻辑混淆。
    3. **限制与隔离边界的最佳实践**:
        - **继续保留 4 种物理类型为 UI 路由提供支持**。这样既能保证 UI 最直观地根据 `sourceType` 弹窗和联动（如 ComboBox
          过滤），又能利用 `effectiveRulePayload` 在核心引擎绑定层自动收拢为 Guard 逻辑和 Score 逻辑两部分。
        - **通过 UI 校验与 Editor 隔离控制物理边界**。例如我们已经实施的 `EvaluatorPropertyEditorStrategy`，在渲染 Branch
          节点时，自动通过代码拦截 RULE 类型，阻止用户在界面上把规则绑定到条件分支。
- **日期**: 2026-06-16

## D-016: 评估树 Prune 语义单一化与守卫三态模型 (阶段一)

- **状态**: ✅ 已锁定 | 一句话结论：引入 `EvalOutcome` 三态（Matched/Skipped/Pruned），将剪枝职责从 rule 移至守卫侧，
  `RuleResult.Prune` 移除。
- **背景**: 原 `RuleResult.Prune` 同时承载"逻辑不成立"（Or 全不命中、Not 取反）和"控制流剪枝"（终止整棵评估树）两个语义，在
  And/Or/Not 组合中互相冲突。rule 兼了"评分"和"剪枝"两个职责，需拆开。
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

- **状态**: 🔶 待评估/待实施 | 一句话结论：阶段一稳定后，用"条件叶子（守卫+ScoreEffect）"在评估树 And/Or/Not/Branch
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
