# 任务归档 - ai-config-validation - 2026-07-16

> 归档日期: 2026-07-16
> 原位置: `architecture-context/ai-config-validation/TRACKER.md`

## Q-2a: 两源加权管道 — 7 子任务全部完成

### Q-2a 收敛方案（事件流，2026-07-16 评估通过）

- **DataSource** `match_activity_events`：无参，输出 `List<MatchActivityEvent>`（`kind: CARD_PLAYED|CARD_GRAVEYARD` + `cardId`），统一暴露对局活动事件。
- **Transform** `weighted_activity_sum`：单输入 `List<MatchActivityEvent>`，参数 `playedCardIds: List<String>` + `graveyardCardIds: List<String>` + `weightPerEvent: Int`，输出 `Int`。
- **Operator** `gte(4)`：阈值判断。
- **管道**：`match_activity_events → weighted_activity_sum(playedCardIds=[GDB_726,...], graveyardCardIds=[GDB_726], weightPerEvent=1) → gte(4)`

**被否决/暂不采用**：①短路停止决策（误解需求）；②currentCost==0 信号（手牌外读不到）；③组合 DataSource（组合爆炸）；④`Map<groupId,Int>`（单位不统一）；⑤减费贡献=静态KV（权重动态）；⑥方案Z 复合快照（DataSource 变聚合器）；⑦通用多 DataSource/DAG（牵动 AST/校验/UI/MCP，不在本问题范围）。

**首批限制（2026-07-16 审查定论）**：
- 仅 support cardId 精确匹配，不做 groupId（墓地 `Card` 无 groupIds，`RuleEnv` 无 `cardGroupIndex`）。
- 参数形状只用 `List<String>` + `Int`（统权重），**不做** `List<ActivityWeightRule>`（FieldParser 降级为 StringType 会被 SpecValidator 校验挡住）和 `Map<String, Int>`（FieldParser 不识别 Map）。
- 不同卡不同权重 → 后续单独开「复杂参数 schema / FieldParser Map 支持」任务。

**已决议 4 点**：
- ①参数形状：`playedCardIds: List<String>` + `graveyardCardIds: List<String>` + `weightPerEvent: Int`（FieldParser 稳定支持）。
- ②MatchState 事件记录：`recordCardPlayed` 时同步追加 `CARD_PLAYED` 事件，不依赖 `currentDimensions` 是否为空。
- ③墓地 groupIds：DataSource 不解析，仅暴露 cardId；Transform 按 cardId 精确匹配。
- ④groupId 权重：首批不实现。

### Q-2a-1: 定义事件模型边界
- 新增 `MatchActivityKind` 枚举（`CARD_PLAYED`、`CARD_GRAVEYARD`）+ `MatchActivityEvent` data class（`kind`、`cardId`）。
- 首批不支持 groupIds。
- 落点：`WeightHanderStrategy/src/main/kotlin/lin/domain/MatchActivity.kt`。

### Q-2a-2: 调整 MatchState 读侧事件记录
- 在 `recordCardPlayed(card)` 语义下稳定追加 `CARD_PLAYED` 事件。
- 事件记录**不**依赖 `currentDimensions` 是否为空。
- 暴露 `playedEvents(): List<MatchActivityEvent>` 查询方法；`start()`（GameLifecycle）清空事件，`start(warInfo)`（RoundLifecycle）保留不清空。

### Q-2a-3: 新增 `match_activity_events` DataSource
- 实现 `DataSource<List<MatchActivityEvent>>`，id=`match_activity_events`，class 形式。
- 从 `env.matchState().playedEvents()` 取已打出事件；从 `context.warInfo.getGraveyardCards()` 取墓地 cardId。
- 不碰 `RuleEnv` 接口。

### Q-2a-4: 新增 `weighted_activity_sum` Transform
- 实现 `Transform<List<MatchActivityEvent>, Int>`，id=`weighted_activity_sum`。
- 参数类 `WeightedActivitySumParams`（`playedCardIds: List<String>` + `graveyardCardIds: List<String>` + `weightPerEvent: Int`）。
- 后续改为 class 形式 + PipelineCache 缓存（playEventVersion 驱动失效）。

### Q-2a-5: 注册组件与元数据验证
- `DefaultDataSourceProvider` 注册 `MatchActivityEventsSource()`；`DefaultTransformProvider` 注册 `WeightedActivitySumTransform()`。

### Q-2a-6: 测试圣契最小链路
- 管道：`match_activity_events → weighted_activity_sum → gte(4)`。
- 3 场景全部通过：①只打出不足 4 → false；②打出+少量墓地不足 4 → false；③打出+墓地达到 4 → true。
- 测试文件：`WeightHanderStrategy/src/test/kotlin/condition/MatchActivityPipelineTest.kt`

### Q-2a-7: 文档收敛
- TRACKER.md：Q-2a 标已完成；DECISIONS.md：D-5 已记录。

## T-113: D-3 基础版 — MatchGroupPlayedCountsSource
- 实施 D-3 已锁定决策的基础版：DataSource `match_group_played_counts`（无参，输出 `Map<String,Int>`）→ Transform `pick_group_count`（参数 groupId）→ `gte(N)`。
- MatchState 加 `allGroupPlayCounts()`；DefaultComponents.kt 新增 `MatchGroupPlayedCountsSource` + `PickGroupCountTransform`。

## Q-2b: latch 机制 — 评估收敛
- 结论：圣契场景直接读 `callCard.cost() == 0`（游戏引擎内置减费计数天然单调递增），通用 latch（achievedFlags + 触发点）暂缓至有真实非引擎-latch 场景时再启动。
