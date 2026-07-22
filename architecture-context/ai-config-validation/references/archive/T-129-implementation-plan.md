# T-129 实施计划：MatchActivityEventsSource 引用化重构

> 决策依据：[DECISIONS.md D-11](../DECISIONS.md)（评估级 Map）+ D-002 部分解决
> 任务追踪：[TRACKER.md T-129](../TRACKER.md)
> 前置：[T-128](./T-128-implementation-plan.md) 已完成（memoize 删除 + 评估级 Map 基础设施）

---

## 背景

T-128 完成后，`MatchActivityEventsSource` 仍用 `buildList` 合并 played events + graveyard cards，并通过评估级 Map 缓存。问题是：

1. **仍创建对象**：首次 resolve 时 `buildList` + `graveyard.map { MatchActivityEvent(...) }` 创建 30+ 个新对象
2. **类型不一致**：played 侧是 `List<MatchActivityEvent>`，graveyard 侧是 `List<Card>`，合并后丢失类型信息
3. **评估级 Map 是权宜之计**：缓存只是避免重复创建，没有消除创建本身

**本任务目标**：Source 改为返回 `Map<MatchActivityKind, List<Card>>`，两侧都是引用，零对象创建。不再需要评估级 Map 缓存此
Source。

---

## 设计

### 核心变更

```
现状（T-128 后）：
  Source: buildList { addAll(playedEvents.toList()); addAll(graveyard.map{...}) }
         → 创建 1 List + 30 MatchActivityEvent → 评估级 Map 缓存

目标（T-129）：
  Source: mapOf(CARD_PLAYED to matchState.playedCards(), CARD_GRAVEYARD to warInfo.getGraveyardCards())
         → 两侧都是引用，零元素创建 → 不需要缓存
```

### 类型变更

| 组件                                | 变更前                     | 变更后                               |
|-------------------------------------|----------------------------|--------------------------------------|
| `MatchActivityEventsSource` 输出    | `List<MatchActivityEvent>` | `Map<MatchActivityKind, List<Card>>` |
| `WeightedActivitySumTransform` 输入 | `List<MatchActivityEvent>` | `Map<MatchActivityKind, List<Card>>` |

### MatchState 新增 `playedCards()`

`recordCardPlayed(card: ComboCard)` 已接收 ComboCard（内含 Card），当前只存 cardId 到 `playedEventsList`。新增存储 Card 引用：

```kotlin
private val playedCardsList = mutableListOf<Card>()

fun recordCardPlayed(card: ComboCard) {
    recordLogic(card)
    playedEventsList += MatchActivityEvent(MatchActivityKind.CARD_PLAYED, card.cardId())
    playedCardsList += card.card  // ← 新增：存 Card 引用
    playEventVersion++
}

/** 本局已打出卡牌列表（引用返回，零拷贝） */
fun playedCards(): List<Card> = playedCardsList
```

**注意**：`playedCards()` 返回内部 `MutableList` 的直接引用（非 `.toList()` 防御拷贝）。这是有意为之——Source 只读不写，Transform
只遍历不修改，单线程访问，防御拷贝正是要消除的对象创建。`start()` 清空时同步清理。

### MatchActivityEventsSource 改为引用返回

```kotlin
val MatchActivityEventsSource = dataSource<Map<MatchActivityKind, List<Card>>>(
    id = "match_activity_events",
    name = "对局活动事件",
    description = "获取本局已打出卡牌与墓地卡牌，按事件类型分组返回卡牌列表引用",
    categories = setOf(OrthogonalCategoryCatalog.GAME_STATE.id)
) { env ->
    mapOf(
        MatchActivityKind.CARD_PLAYED to env.matchState().playedCards(),
        MatchActivityKind.CARD_GRAVEYARD to env.warInfo().getGraveyardCards()
    )
}
```

- `mapOf(...)` 创建 1 个 Map + 2 个 Entry，可忽略
- 两个 `List<Card>` 都是引用，零元素创建
- **不需要 `env.cache(...)`**——Map 创建成本可忽略

### WeightedActivitySumTransform 适配

```kotlin
val WeightedActivitySumTransform = transform<
        Map<MatchActivityKind, List<Card>>,
        Int,
        WeightedActivitySumParams
        >(
    id = "weighted_activity_sum",
    name = "活动加权求和",
    description = "按事件类型与卡牌ID匹配对局活动事件，累计加权求和得到贡献值"
) { input, params ->
    val played = input[MatchActivityKind.CARD_PLAYED].orEmpty()
    val graveyard = input[MatchActivityKind.CARD_GRAVEYARD].orEmpty()
    var sum = 0
    for (card in played) {
        if (card.cardId in params.playedCardIds) sum += params.weightPerEvent
    }
    for (card in graveyard) {
        if (card.cardId in params.graveyardCardIds) sum += params.weightPerEvent
    }
    sum
}
```

- 不再需要 `when (event.kind)` 分派——kind 由 Map key 体现
- 直接遍历 `List<Card>`，用 `card.cardId` 匹配参数
- `MatchActivityEvent` 不再参与管道流转

### 评估级 Map 保留 + 加注释

`RuleEnv.cache()` 保留（`WarViewSource` 等仍在用），加临时注释：

```kotlin
/**
 * 评估级缓存：同一评估内多规则树共享，评估结束随 RuleEnv 回收。
 * 由组件闭包按需调用，cacheKey 由组件自己拼。
 *
 * ⚠️ 临时基础设施：当前仅 WarViewSource 使用。MatchActivityEventsSource 已改为
 * 引用返回（Map<Kind, List<Card>>），不再需要缓存。后续其他 Source 逐步改为
 * 引用返回后，本方法可废弃。
 */
fun <T> cache(key: String, compute: () -> T): T
```

---

## 子任务拆分

### S1：MatchState 暴露 `playedCards()`

文件：`WeightHanderStrategy/src/main/kotlin/lin/domain/MatchState.kt`

1. 新增 `private val playedCardsList = mutableListOf<Card>()`
2. 新增 `import club.xiaojiawei.hsscriptcardsdk.bean.Card`
3. `recordCardPlayed(card: ComboCard)` 内追加 `playedCardsList += card.card`
4. 新增 `fun playedCards(): List<Card> = playedCardsList`（引用返回，注释说明非防御拷贝的原因）
5. `start()` 内追加 `playedCardsList.clear()`

**不改**：`playedEvents()` / `playedEventsList` / `playEventVersion` 保留不动（兼容现有代码，后续清理）。

### S2：MatchActivityEventsSource 改为 `Map<Kind, List<Card>>`

文件：`WeightHanderStrategy/src/main/kotlin/lin/rule/orthogonal/DefaultDataSources.kt`

1. 返回类型 `List<MatchActivityEvent>` → `Map<MatchActivityKind, List<Card>>`
2. 闭包体改为
   `mapOf(CARD_PLAYED to env.matchState().playedCards(), CARD_GRAVEYARD to env.warInfo().getGraveyardCards())`
3. 删除 `env.cache(...)` 调用
4. 删除 `buildList` + `graveyard.map { MatchActivityEvent(...) }`
5. 更新 description
6. 更新 `@defect D-002` 注释（见 S5）

### S3：WeightedActivitySumTransform 适配新输入类型

文件：`WeightHanderStrategy/src/main/kotlin/lin/rule/orthogonal/DefaultTransforms.kt`

1. 输入类型 `List<MatchActivityEvent>` → `Map<MatchActivityKind, List<Card>>`
2. 删除 `import lin.domain.MatchActivityEvent`（如不再使用）
3. 闭包体改为按 kind 取 list，分别遍历匹配（见设计部分代码）
4. 删除 `when (event.kind)` 分派逻辑

### S4：评估级 Map 加临时注释

文件：`WeightHanderStrategy/src/main/kotlin/lin/rule/context/RuleEnv.kt`

1. `cache()` 方法的 KDoc 加"⚠️ 临时基础设施"注释（见设计部分）
2. 不改实现，不加删除标记

### S5：D-002 标记更新

文件：`DefaultDataSources.kt`（`MatchActivityEventsSource` 注释）

更新 `@defect D-002` 注释为：

```
@defect D-002（部分解决）：Source 已改为 Map<Kind, List<Card>> 引用返回，
不再合并创建对象。但 Transform 仍内嵌事件类型匹配判定（按 kind 分派遍历），
完整重构方向是拆为 played_events / graveyard_events 两个独立 Source + 独立管道。
触发条件不变：再有 Transform 内嵌匹配判定的同类案例出现时启动完整拆分。
```

### S6：MatchActivityEvent 清理评估

**不在本任务范围**，但记录状态供后续参考：

- `MatchActivityEvent` data class：管道流转不再使用（Source 改为 `Map<Kind, List<Card>>`，Transform 直接用 `Card.cardId`）
- `MatchState.playedEvents()` / `playedEventsList`：仍保留，但管道不再调用
- **后续清理任务**：确认无其他调用方后，可废弃 `playedEvents()` / `playedEventsList` / `MatchActivityEvent`

### S7：测试

文件：`WeightHanderStrategy/src/test/kotlin/condition/MatchActivityPipelineTest.kt`

1. 现有 3 个场景测试适配新类型（管道输入从 `List<MatchActivityEvent>` 变为 `Map<Kind, List<Card>>`）
2. 测试 fake 的 `matchStateWithPlays` 需同步存储 Card 引用（调 `recordCardPlayed` 时 ComboCard 已含 Card）
3. 新增测试用例：
    - **引用返回验证**：同一 RuleEnv 内多次 resolve，返回的 Map 中 List 引用相同（`===`）
    - **零 MatchActivityEvent 创建**：确认管道流转不创建 MatchActivityEvent 对象

文件：`WeightHanderStrategy/src/test/kotlin/condition/EvalCacheTest.kt`（如存在）

- 删除或更新 `MatchActivityEventsSource` 相关的缓存命中测试（该 Source 不再用评估级 Map）
- `WarViewSource` 缓存测试保留

### S8：文档同步

- [ ] TRACKER.md：新增 T-129，更新 D-002 挂起任务描述
- [ ] MEMORY.md：更新评估级 Map 段落（MatchActivityEventsSource 不再用缓存）
- [ ] DECISIONS.md：D-002 状态更新为"部分解决"

---

## 依赖关系图

```
S1 (MatchState playedCards) ── 无依赖，立即开始
        ↓
S2 (Source 改 Map) ── 依赖 S1（需要 playedCards()）
        ↓
S3 (Transform 适配) ── 依赖 S2（输入类型变更）
        ↓
S4 (评估级 Map 注释) ── 独立，随时可做
        ↓
S5 (D-002 标记) ── 与 S2 同提交
        ↓
S6 (清理评估) ── 仅记录，不执行
        ↓
S7 (测试) ── 依赖 S1-S3
        ↓
S8 (文档同步) ── 最后
```

---

## 风险点

### 风险 1：`playedCards()` 返回可变列表引用

- **问题**：返回 `playedCardsList` 直接引用，调用方可修改内部状态
- **实际边界**：Source 只读、Transform 只遍历、单线程访问
- **缓解**：KDoc 注释明确标注"引用返回，调用方不得修改"
- **替代方案**：用 `Collections.unmodifiableList(playedCardsList)` 包装，但创建包装对象（轻量）

### 风险 2：`MatchActivityEvent` 废弃后 `playedEvents()` 残留

- **问题**：`playedEvents()` / `playedEventsList` 保留但管道不再使用，可能造成混淆
- **缓解**：加 `@Deprecated` 注解或 KDoc 标注"管道已改用 playedCards ()，本方法保留供其他调用方"
- **后续**：确认无调用方后统一清理

### 风险 3：Transform 签名变更影响其他 agent 的并行工作

- **问题**：用户已安排其他 agent 做 Transform 签名清理（去掉 env/context）。本任务也改 Transform 输入类型，可能冲突
- **缓解**：本任务只改 `WeightedActivitySumTransform` 一个组件的输入类型，其他 Transform 不受影响。如果其他 agent
  的签名清理先完成，本任务的 Transform 闭包签名自然适配（`(In, P) -> Out`）
- **协调**：两个任务改不同文件不同层面，冲突风险低

---

## 验收标准

- [ ] S1：`MatchState.playedCards()` 返回 `List<Card>` 引用，`recordCardPlayed` 存储 Card
- [ ] S2：`MatchActivityEventsSource` 返回 `Map<MatchActivityKind, List<Card>>`，零元素创建
- [ ] S3：`WeightedActivitySumTransform` 接收 `Map<Kind, List<Card>>`，按 kind 分别遍历
- [ ] S4：评估级 Map KDoc 标注"临时基础设施"
- [ ] S5：`@defect D-002` 注释更新为"部分解决"
- [ ] S7：`MatchActivityPipelineTest` 3 场景全绿 + 新增引用返回验证用例
- [ ] LSP 检查无错误
- [ ] `mvn -pl WeightHanderStrategy test` 全跑通
- [ ] S8：TRACKER.md / MEMORY.md / DECISIONS.md 已同步
