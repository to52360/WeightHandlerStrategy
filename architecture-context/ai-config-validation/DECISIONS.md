# 决策记录 - ai-config-validation

## 决策清单

| ID  | 决策名                             | 状态    | 相关任务          |
|-----|---------------------------------|-------|---------------|
| D-1 | MatchState 跨回合状态容器              | ✅ 已锁定 | T-108         |
| D-2 | 使用动作（UseAction）声明式记录            | ✅ 已锁定 | T-108         |
| D-3 | 读侧正交管道新 DataSource              | ✅ 已锁定 | T-108 / P-2.1 |
| D-4 | CardConfigBindingTask Step 模式重构 | ✅ 已锁定 | T-107         |

## 决策详情

### D-1: MatchState — 跨回合状态容器

- 状态: ✅ 已锁定
- 相关任务: T-108
- 决策内容: 新建 `MatchState` 类作为跨回合状态容器，挂 `MyWarManage`（同 `warStatus` 模式），实现 `GameLifecycle` 管理重置，Koin
  注册。存储 `cardId → count`、`groupId → count`。
- 理由: 当前架构纯无状态/快照式（RuleEnv 只有 WarView，WarStatus 每回合清空），无跨回合统计能力。"圣契打了几张"
  这类对局状态统计无归属，需要独立的状态容器。不放入 `EvaluatorLeafConfig`（配置是静态声明，状态是动态追踪，职责不同）。
- 替代方案: [已否决] 在 `UseDomain` 全量记录——违背声明式原则，且 `UseDomain` 已够乱不应再加属性中转。
- 记录时间: 2026-07-10

### D-2: 使用动作（UseAction）— 声明式记录，非全量

- 状态: ✅ 已锁定
- 相关任务: T-108
- 决策内容: `UseStrategy` 改名为 `UseAction`（UseBeforeAction / UseAfterAction），语义从"策略"拓宽为"出牌时执行的动作"。
  `RecordPlayAction` 是 AfterUseAction 的一种，只有配置了此动作的卡牌才记录（声明式 opt-in，非全量）。`RecordPlayAction` 通过
  Koin 直接注入 MatchState，不经过 `UseDomain` 中转。配置走 ConfigDispatcher（UseConfig → UseConfigHandler →
  CardWeightInfo.addUseStrategy），不碰 CardConfigBindingTask。
- 理由: 配置驱动系统中，状态追踪应声明式 opt-in（通过卡牌配置声明使用动作），而非全量记录。`UseDomain` 职责已成乱麻，不应新增属性做
  MatchState 中转。Koin DI 让动作直接获取所需服务，`UseDomain` 零改动。
- 替代方案: [已否决] 在 `UseDomain.useCard()` / `tryUseCard()` 中全量记录每张牌——记录一切且需给 `UseDomain` 加属性，违反声明式与单一职责。
  2026-07-10 末用户明确否决此全量方案，确认按本决策（opt-in）落地。
- 实施记录（2026-07-10）:
  - `lin.domain.MatchState` 新建（GameLifecycle + RoundLifecycle + KoinComponent）；`recordCardPlayed(ComboCard)` 按 `cardId()` 与
    `groupIds()` 计入整局累计；`currentTurnPlayedCards` 仅记录本回合打出（墓地不存）。
  - `lin.domain.use.RecordPlayAction`（`object : UseAfterStrategy, KoinComponent`，`by inject<MatchState>()`，在 `afterExtAction`
    调 `matchState.recordCardPlayed(context.card)`）。
  - `MyWarManage.init` 创建并 Koin 注册 `MatchState` + `registerLifecycle(matchState)`。
  - 接口重命名（`UseStrategy`→`UseAction`）暂缓：沿用现有 `UseAfterStrategy` 名称，`RecordPlayAction` 已实现为 `UseAfterStrategy`，避免大面积无收益改名。
- 记录时间: 2026-07-10

### D-3: 读侧 — 正交管道新 DataSource

- 状态: ✅ 已锁定
- 相关任务: T-108 / P-2.1
- 决策内容: 新增 `MatchGroupPlayedCountSource`（输出 Int），AI 可配置管道 `match_group_played_count(groupId=X) → gte(N)`。归入
  P-2.1（枚举缺失 DataSource）范畴。DataSource 通过 Koin 注入 MatchState 查询，不扩展 RuleEnv（RuleEnv 仍只管 WarView 快照）。
- 理由: 正交管道已是"可组合条件"系统，新增 DataSource 让 AI 配置式引用，无需改代码。RuleEnv 保持战场快照职责纯粹，MatchState
  通过 DataSource 间接暴露。
- 替代方案: [已否决] 扩展 RuleEnv 加 `matchState()` 方法给编码类规则直接访问——RuleEnv 职责膨胀，且绕过正交管道声明式配置。
- 记录时间: 2026-07-10

### D-4: CardConfigBindingTask — Step 模式重构

- 状态: ✅ 已锁定
- 相关任务: T-107
- 决策内容: 抽取 `ConfigBindingStep` 接口，每数据源独立成 Step（WeightInfoStep / GroupIndexStep / PurposeStep /
  ComboStep），execute() 仅编排 + 注册。不用 ConfigDispatcher 模式。对 ConfigDispatcher 零影响。
- 理由: `execute()` 混合 4 数据源加载 + 2 assembler + Koin 注册，加功能需感知全部模块。Step 模式是 N→1 汇聚（多源 →
  一个组装结果），与 ConfigDispatcher 的 1→N 分派（按类型路由到 handler）模式不同，不应混用。Step 模式让新维度 = 新 Step，不碰
  execute()，不感知其他 Step。
- 替代方案: [已否决] 用 ConfigDispatcher 的类型分派模式重构——两系统职责不同（组装流水线 vs 运行时分派），模式不匹配。
- 记录时间: 2026-07-10
