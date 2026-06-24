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
- [x] **D-014: 真实参数化过滤数据源与多映射体处理决策** - 状态: ✅ 已锁定 |
  一句话结论：放弃复合大参数过滤对象，改用高内聚的多个具体映射体或简单特定数据源处理。 (归档批次: 2026-06-22)
- [x] **D-015: EvaluatorLeafSourceType 类型化隔离优化** - 状态: ✅ 已锁定 | 一句话结论：针对 `EvaluatorLeafSourceType`
  导致的细节泄漏进行类型化隔离重构，避免了 UI-Engine 间多级冗余垫片。 (归档批次: 2026-06-22)
- [x] **D-016: 评估树 Prune 语义单一化与守卫三态模型** - 状态: ✅ 已锁定 | 一句话结论：引入 `EvalOutcome`
  三态（Matched/Skipped/Pruned），将剪枝职责移至守卫侧并移除 `RuleResult.Prune`。 (归档批次: 2026-06-22)
- [x] **D-017: 评估树替代条件树的方向规划** - 状态: ✅ 已锁定 | 一句话结论：阶段一稳定后，用"条件叶子（守卫+ScoreEffect）"
  表达条件树语义并逐步废弃条件树模块。 (归档批次: 2026-06-22)

---

## D-018: 正交条件数据源分层供给与 ComboCard→Card 桥接方案

**状态**: ✅ 已锁定

**背景**: 手牌/战场数据在系统中同时存在 `Card` 和 `ComboCard` 两种类型。95%+ 的守卫条件只需要 Card 级数据，少数场景（分组判别、combo
权重）需要 ComboCard。若全用 ComboCard 统一，敌方卡牌无配表数据会造成无意义的空包装；若全用 Card，则 ComboCard 特有操作需要额外转换。

**决策**:

1. **数据源分层供给**：
    - Card 级数据源（`hand_cards` / `me_board_cards` / `rival_board_cards` / `board_cards`）覆盖基础条件
    - ComboCard 级数据源（`hand_combo_cards` / `me_combo_cards`）直接读 `WarInfo` 预计算缓存（`MyWarManage.reLoad()`
      中已预计算），零额外开销
    - 不设 `rival_combo_cards`——敌方卡牌无权重配表，语义无意义
2. **ComboCard→Card 桥接**：提供 `to_cards` Transform（`input.map { it.card }`，约5行），避免为 ComboCard 重写 Card 级
   Transform 变体（如 `RaceFilterForComboCard`）
3. **Card→ComboCard 方向不设 Transform**：直接用 ComboCard 数据源读缓存，避免重复 `reLoad()` 的工作

**理由**:

- 数据源分层后，ComboCard 路径走缓存、Card 路径走原始数据，互不交叉，零冗余
- `to_cards` 桥仅读取已持有字段引用，n≤10 场景微秒级，热路径开销可忽略
- 避免了要么全 Card（丢便利）、要么全 ComboCard（敌方浪费）、要么写两套变体 Transform（膨胀）的三难困境

**后备方案**: 若未来系统失去"唯一事实源"导致桥方案不可持续，考虑引入字段访问器（`FieldAccessor<T>`）抽象层统一字段读取入口。

**影响范围**: `DefaultComponents.kt` 新增 `HandComboCardsSource`、`MeComboCardsSource`、`ToCardsTransform`；
`DataSource.kt` / `Transform.kt` 无需改动

**日期**: 2026-06-23

---

## 活跃讨论与评估中决策

## D-013: 规则叶子类型多态简化与边界收拢方向

**状态**: ✅ 已锁定

**背景**: 评估树叶子节点原 `EvaluatorLeafSourceType` 定义了 4 种类型 (`RULE`, `ORTHOGONAL_RULE`, `CONDITION`,
`CONDITION_TREE`)，存在概念扩散和 UI-Engine 间多级冗余垫片问题（详见 D-015）。

**决策**: 将 `EvaluatorLeafSourceType` 重命名为 `EvaluatorLeafKind`，通过 D-015 的类型化隔离重构收拢边界，消除 UI-Engine
间的细节泄漏。原有 4 种类型的语义通过 `EvaluatorLeafKind` 保留，未激进简化为 2 种，而是以类型隔离 + 命名语义化的方式完成边界收拢。

**理由**: 重命名 (`SourceType` → `Kind`) 更准确表达叶子节点的种类语义而非来源语义；配合 D-015 的类型隔离达成边界清晰化，风险低于激进简化。

**影响范围**: `EvaluatorLeafMeta.kt` 中 `kind: EvaluatorLeafKind`；D-015 涉及的 UI-Engine 隔离层

**日期**: 2026-06-16（评估）/ 2026-06-23（落地确认）
