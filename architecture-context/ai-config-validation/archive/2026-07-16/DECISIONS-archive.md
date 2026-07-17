# 决策归档 - ai-config-validation - 2026-07-16

> 归档日期: 2026-07-16
> 原位置: `architecture-context/ai-config-validation/DECISIONS.md`

## D-1: MatchState — 跨回合状态容器

- 状态: ✅ 已锁定
- 相关任务: T-108
- 决策内容: 新建 `MatchState` 类作为跨回合状态容器，挂 `MyWarManage`（同 `warStatus` 模式），实现 `GameLifecycle` 管理重置，Koin 注册。存储 `cardId → count`、`groupId → count`。
- 理由: 当前架构纯无状态/快照式（RuleEnv 只有 WarView，WarStatus 每回合清空），无跨回合统计能力。
- 替代方案: [已否决] 在 `UseDomain` 全量记录。
- 记录时间: 2026-07-10

## D-2: 使用动作（UseAction）— 声明式记录

- 状态: ✅ 已锁定
- 相关任务: T-108
- 决策内容: `UseStrategy` 改名为 `UseAction`（UseBeforeAction / UseAfterAction）。`RecordPlayAction` 是 AfterUseAction，opt-in 声明。通过 Koin 直接注入 MatchState，不经过 `UseDomain` 中转。配置走 ConfigDispatcher。
- 理由: 状态追踪应声明式 opt-in，非全量。Koin DI 让动作直接获取所需服务。
- 替代方案: [已否决] 在 `UseDomain.useCard()` 中全量记录每张牌。
- 实施记录（2026-07-10）:
  - `lin.domain.MatchState` 新建；`recordCardPlayed(ComboCard)` 按 `cardId()` 与 `groupIds()` 计入整局累计。
  - `lin.domain.use.RecordPlayAction`（`object : UseAfterStrategy, KoinComponent`）。
  - `MyWarManage.init` 创建并 Koin 注册 `MatchState` + `registerLifecycle(matchState)`。
- 记录时间: 2026-07-10

## D-3: 读侧 — 正交管道新 DataSource

- 状态: ✅ 已锁定
- 相关任务: T-108 / P-2.1
- 决策内容: 新增 `MatchGroupPlayedCountSource`（输出 Int），AI 可配置管道 `match_group_played_count(groupId=X) → gte(N)`。DataSource 通过 Koin 注入 MatchState 查询，不扩展 RuleEnv。
- 理由: 正交管道已是"可组合条件"系统，新增 DataSource 让 AI 配置式引用。
- 替代方案: [已否决] 扩展 RuleEnv 加 `matchState()` 方法。
- 记录时间: 2026-07-10

## D-4: CardConfigBindingTask — Step 模式重构

- 状态: ✅ 已锁定
- 相关任务: T-107
- 决策内容: 抽取 `ConfigBindingStep` 接口，每数据源独立成 Step（WeightInfoStep / GroupIndexStep / PurposeStep / ComboStep），execute() 仅编排 + 注册。不用 ConfigDispatcher 模式。
- 理由: execute() 混合 4 数据源加载 + 2 assembler，加功能需感知全部模块。Step 模式是新维度 = 新 Step。
- 替代方案: [已否决] 用 ConfigDispatcher 的类型分派模式重构。
- 记录时间: 2026-07-10

## D-5: Q-2a 事件流管道 — 参数形状与首批限制

- 状态: ✅ 已锁定
- 相关任务: Q-2a（Q-2a-1 ~ Q-2a-7）
- 决策内容:
  - **管道形状**：`match_activity_events → weighted_activity_sum → gte(4)`，单源线性。
  - **MatchActivityEvent**：仅含 `kind`（CARD_PLAYED / CARD_GRAVEYARD）+ `cardId`。
  - **weighted_activity_sum 参数**：`playedCardIds: List<String>` + `graveyardCardIds: List<String>` + `weightPerEvent: Int`。
  - **MatchState 记录语义**：`recordCardPlayed` 同步追加 `CARD_PLAYED` 事件，与 `currentDimensions` 解耦。
  - **墓地事件**：DataSource 从 `getGraveyardCards()` 取 `cardId` 转 `CARD_GRAVEYARD` 事件。
- 理由: `List<String>` + `Int` 是 FieldParser 稳定支持的参数类型。cardId 精确匹配覆盖圣契场景。
- 替代方案:
  - [暂不采用] `List<ActivityWeightRule>`：FieldParser 不支持，SpecValidator 校验失败。
  - [暂不采用] Map 形参数：FieldParser 不识别 Map。
  - [暂不采用] 通用多 DataSource/DAG：牵动 PipelineRef AST。
- 记录时间: 2026-07-16
