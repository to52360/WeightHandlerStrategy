# 决策记录 - orthogonal-condition

>
历史详情请阅读 [ARCHIVE-INDEX.md](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/architecture-context/orthogonal-condition/ARCHIVE-INDEX.md)

## 已归档决策快照 (详情见归档文件)

- [x] **D-008: 引擎防御性校验与职责边界的权衡** - 状态: ✅ 已锁定 | 一句话结论：保留装配与绑定期的 Fail-Fast
  防御拦截以保障运行时高可靠性。 (归档批次: 2026-06-16)
- [x] **D-009: 放弃特征路由自适应，拥抱多态多维强类型 ConditionPayload** - 状态: ✅ 已锁定 | 一句话结论：采用强类型多态
  `ConditionPayload.OrthogonalRef` 彻底隔离参数并支持 AST。 (归档批次: 2026-06-16)
- [x] **D-010: 规则树与评估树职责分离，并规划“规则（Rule）正交化”** - 状态: ✅ 已锁定 | 一句话结论：确立正交化大方向
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
       策略中区分并过滤，或探索将当前大下拉框拆分为“分类下拉框”+“具体项下拉框”的多级筛选结构。
    2. **规则多态隔离 (Q-013.2)**：参考条件侧的 `ConditionPayload`（分为 `ConditionRef` 与 `OrthogonalRef` 两个子类），规则侧也可以设计
       `RulePayload`（子类分别为代表硬编码规则的 `RuleRef` 以及代表配置规则的 `OrthogonalRuleRef`
       ），将特定配置逻辑（如守卫、ScoreEffect 等）完全锁在 `OrthogonalRuleRef` 子类边界内，避免其扩散影响扁平的
       `EvaluatorLeafConfig` 结构。
- **后续规划**: 暂不在本次重构中实现此改动，将此设计方向作为架构待评估项（对应 `T-017`），并在后续阶段中进一步验证和设计。
- **日期**: 2026-06-16

## D-014: 真实参数化过滤数据源（如随从种族）的推进决策

- **状态**: ✅ 已锁定(搁置)
- **背景**: 现有的 `HandCardCountSource`
  仅是简单的数量占位演示。在实际卡牌决策中，我们迫切需要诸如“我方战场上属于【野兽】种族的随从数量 >= 3”的精确过滤，如果每个过滤维度都开发一个
  SPI 实体，将导致严重的类爆炸。
- **决定**: 将开发“真实参数化过滤数据源”列为后续重点迭代任务（`T-018`）。
- **设计细节**:
    - 数据源在 `fields` 中声明如 `CardFilterParams(race: CardRaceEnum?, cardType: CardTypeEnum?)`。
    - 在 `resolve(context, args)` 执行时从 `WarContext` 的战场或手牌视图中执行参数化的 `filter` 匹配，返回真正动态过滤后的数据或计数。
- **日期**: 2026-06-16

## D-015: EvaluatorLeafSourceType 物理简化可行性与边界隔离权衡决策

- **状态**: ✅ 已锁定(搁置)
- **背景**: 讨论是否应该将评估叶子来源类型 `EvaluatorLeafSourceType` 简并为 `RULE` 与 `CONDITION` 两个大类，以简化概念。
- **分析与评估结论**:
    1. **解析层兼容度**: 极高。在引入 Jackson 多态 Mixin 后，Jackson 物理上完全是自适应解析，不受外层 `sourceType` 枚举缩减的影响。
  2. **UI 联动复杂度**: 影响极大。如果直接缩减为 2 类，配置端 UI ComboBox 将难以直接通过 `sourceType`
     判别这是一个“普通代码规则”还是“配置型正交规则”；难以区分这是一个“单体代码条件”还是“引用的条件树”，进而需要去猜测
     `sourceId` 的特殊魔法值，会导致 UI 表单与弹窗控制层出现逻辑混淆。
    3. **限制与隔离边界的最佳实践**:
        - **继续保留 4 种物理类型为 UI 路由提供支持**。这样既能保证 UI 最直观地根据 `sourceType` 弹窗和联动（如 ComboBox
          过滤），又能利用 `effectiveRulePayload` 在核心引擎绑定层自动收拢为 Guard 逻辑和 Score 逻辑两部分。
        - **通过 UI 校验与 Editor 隔离控制物理边界**。例如我们已经实施的 `EvaluatorPropertyEditorStrategy`，在渲染 Branch
          节点时，自动通过代码拦截 RULE 类型，阻止用户在界面上把规则绑定到条件分支。
- **日期**: 2026-06-16
