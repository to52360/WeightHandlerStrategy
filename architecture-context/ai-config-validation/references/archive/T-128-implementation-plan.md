# T-128 实施计划：删除 memoize 装饰器 + 评估级 Map

> 决策依据：[DECISIONS.md D-11](../DECISIONS.md)（废弃 D-9 memoize，替换为评估级 Map）
> 任务追踪：[TRACKER.md T-128](../TRACKER.md)
> 取代：[T-127](./T-127-implementation-plan.md)（D-9 memoize 装饰器方案，已废弃）

---

## 背景

T-127（D-9 memoize 装饰器）实施后发现以下问题：

1. **致命 bug**：`WarInfoEnv.memoizeStore()` 每次调用返回新实例，导致缓存永远 miss，整个 memoize 机制空转
2. **缺失测试**：TRACKER 声称新增 `MemoizeCacheTest.kt`（7 用例），但该文件从未创建，bug 6 天未被发现
3. **过度工程化**：90 行基础设施 + 2 装饰器类 + 接口属性侵入 + 24 个组件标注，用于消除微秒级重复计算
4. **隐式契约脆弱**：`identityHashCode(input)` 级联失效依赖"Source 必须 memoize 才能给 Transform 提供 stable reference"，
   `dependsOnCallCard` 默认 false 是静默错误脚枪

D-11 决策：删除 memoize 全套，替换为评估级 Map（`RuleEnv.cache(key, compute)`），只在真正有成本的 Source 闭包里手动启用。

---

## 设计

### 评估级 Map

```kotlin
// RuleEnv 接口新增
fun <T> cache(key: String, compute: () -> T): T

// WarInfoEnv 实现
private val evalCache = mutableMapOf<String, Any?>()
override fun <T> cache(key: String, compute: () -> T): T =
    evalCache.getOrPut(key) { compute() } as T
```

- 生命周期：随 RuleEnv 实例（每次评估新建），评估结束回收
- 启用方式：组件闭包内手动调 `env.cache(key) { ... }`，零接口侵入
- cacheKey：由组件自己拼，所见即所得

### 与 memoize 的对比

|                | memoize (D-9)                                                | 评估级 Map (D-11)                                   |
|----------------|--------------------------------------------------------------|-----------------------------------------------------|
| 基础设施       | 90 行 + 2 装饰器类                                           | 3 行（1 方法 + 1 Map）                              |
| 接口侵入       | `memoize`/`dependsOnCallCard` 挂在 DataSource/Transform 接口 | 零侵入                                              |
| 启用方式       | 全局标 `memoize=true`，Assembler 自动包装装饰器              | 组件闭包内手动 `env.cache(key){}`                   |
| 覆盖面         | 24 个组件全标（13 Source + 11 Transform）                    | 只标 2-3 个真正贵的 Source                          |
| Transform 缓存 | `identityHashCode(input)` 级联                               | 不缓存（filter/sum 微秒级，不值得）                 |
| 脚枪风险       | `dependsOnCallCard` 忘标 → 静默错误                          | 无（不依赖 callCard 的 Source 自然不含 callCardId） |

---

## 子任务拆分

### S1：删除 memoize 基础设施

> 纯删除，不引入新代码。删除后组件回到纯函数直连调用。

#### S1.1：删除 `MemoizeCache.kt`

- 删除整个文件：`WeightHanderStrategy/src/main/kotlin/lin/rule/orthogonal/MemoizeCache.kt`
- 包含：`MemoizeCache` object、`MemoizeStore` class、`MemoizedDataSource` class、`MemoizedTransform` class

#### S1.2：`DataSource` 接口清理

文件：`WeightHanderStrategy/src/main/kotlin/lin/rule/orthogonal/DataSource.kt`

- 删除 `val memoize: Boolean get() = false` 属性
- 删除 `val dependsOnCallCard: Boolean get() = false` 属性
- 删除对应的 KDoc 注释
- DSL `dataSource(...)` 函数删除 `memoize: Boolean = false` 和 `dependsOnCallCard: Boolean = false` 参数
- DSL object 实现删除 `override val memoize = memoize` 和 `override val dependsOnCallCard = dependsOnCallCard`

#### S1.3：`Transform` 接口清理

文件：`WeightHanderStrategy/src/main/kotlin/lin/rule/orthogonal/Transform.kt`

- 删除 `val memoize: Boolean get() = false` 属性及 KDoc
- DSL `transform(...)` 两个 overload 删除 `memoize: Boolean = false` 参数
- DSL object 实现删除 `override val memoize = memoize`

#### S1.4：`PipelineAssembler` 清理

文件：`WeightHanderStrategy/src/main/kotlin/lin/rule/condition/PipelineAssembler.kt`

- 删除 `import lin.rule.orthogonal.MemoizeCache.memoized`
- `assemble(ref)` 方法内：
    - `rawSource.memoize` 判断 + `rawSource.memoized()` 包装 → 直接用 `rawSource`
    - `raw.memoize` 判断 + `raw.memoized()` 包装 → 直接用 `raw`

修改前（当前代码）：

```kotlin
val source: DataSource<Any> = if (rawSource.memoize) rawSource.memoized() else rawSource
// ...
val wrapped = if (raw.memoize) raw.memoized() else raw
```

修改后：

```kotlin
val source: DataSource<Any> = rawSource
// ...
val wrapped = raw
```

#### S1.5：`RuleEnv` / `WarInfoEnv` 清理

文件：`WeightHanderStrategy/src/main/kotlin/lin/rule/context/RuleEnv.kt`

- 删除 `import lin.rule.orthogonal.MemoizeStore`
- 删除 `fun memoizeStore(): MemoizeStore` 方法及 KDoc

文件：`WeightHanderStrategy/src/main/kotlin/lin/rule/context/RuleContext.kt`

- `WarInfoEnv` 删除 `override fun memoizeStore(): MemoizeStore = MemoizeStore()`
- 删除 `import lin.rule.orthogonal.MemoizeStore`

#### S1.6：组件标注清理

文件：`DefaultDataSources.kt`

- 13 个 Source 删除 `memoize = true` 参数
- `EvaluatingCardSource` 删除 `dependsOnCallCard = true` 参数
- `MatchActivityEventsSource` 的 `@defect D-002` 注释中删除"缓存职责已归 D-9 memoize 装饰器统一接管"相关描述

文件：`DefaultTransforms.kt`

- 11 个 Transform 删除 `memoize = true` 参数
- `WeightedActivitySumTransform` 注释中删除"缓存管理"相关描述，更新为"缓存职责已归 D-11 评估级 Map（按需启用）"

#### S1.7：测试 fake 清理

文件：`WeightHanderStrategy/src/test/kotlin/condition/FakeRuleContext.kt`

- `fakeRuleEnv` 删除 `override fun memoizeStore() = MemoizeStore()`
- 删除 `import lin.rule.orthogonal.MemoizeStore`

文件：`WeightHanderStrategy/src/test/kotlin/condition/MatchActivityPipelineTest.kt`

- `ruleEnv` 方法删除 `override fun memoizeStore() = MemoizeStore()`
- 删除 `import lin.rule.orthogonal.MemoizeStore`

#### S1.8：S1 验证

- LSP 检查无错误
- `mvn -pl WeightHanderStrategy compile` 通过
- 旧测试全跑通（纯删除，行为零变化——memoize 本来就是空转的）

---

### S2：添加评估级 Map

> 在 S1 清理干净后，引入最小缓存基础设施。

#### S2.1：`RuleEnv` 接口加方法

文件：`WeightHanderStrategy/src/main/kotlin/lin/rule/context/RuleEnv.kt`

```kotlin
/**
 * 评估级缓存：同一评估内多规则树共享，评估结束随 RuleEnv 回收。
 * 由组件闭包按需调用，cacheKey 由组件自己拼。
 */
fun <T> cache(key: String, compute: () -> T): T
```

#### S2.2：`WarInfoEnv` 实现

文件：`WeightHanderStrategy/src/main/kotlin/lin/rule/context/RuleContext.kt`

```kotlin
class WarInfoEnv(private val warManage: MyWarManage) : RuleEnv {
    private val evalCache = mutableMapOf<String, Any?>()

    override fun warInfo(): WarInfo = warManage
    override fun warView(): WarView = warManage.toWarView()
    override fun matchState(): MatchState = warManage.matchState

    @Suppress("UNCHECKED_CAST")
    override fun <T> cache(key: String, compute: () -> T): T =
        evalCache.getOrPut(key) { compute() } as T
}
```

#### S2.3：测试 fake 实现

文件：`FakeRuleContext.kt` — `fakeRuleEnv` 加：

```kotlin
private val evalCache = mutableMapOf<String, Any?>()
@Suppress("UNCHECKED_CAST")
override fun <T> cache(key: String, compute: () -> T): T =
    evalCache.getOrPut(key) { compute() } as T
```

文件：`MatchActivityPipelineTest.kt` — `ruleEnv` 方法加同样的实现。

#### S2.4：S2 验证

- LSP 检查无错误
- `mvn -pl WeightHanderStrategy compile` 通过
- 旧测试全跑通（新方法零调用，行为零变化）

---

### S3：Source 按需启用缓存

> 只在真正有构建成本的 Source 闭包内调用 `env.cache()`。

#### S3.1：`MatchActivityEventsSource`（必做）

文件：`DefaultDataSources.kt`

当前：

```kotlin
val MatchActivityEventsSource = dataSource<List<MatchActivityEvent>>(
    id = "match_activity_events",
    ...
) { env ->
    buildList {
        addAll(env.matchState().playedEvents())
        addAll(env.warInfo().getGraveyardCards().map { card ->
            MatchActivityEvent(MatchActivityKind.CARD_GRAVEYARD, card.cardId)
        })
    }
}
```

修改后：

```kotlin
val MatchActivityEventsSource = dataSource<List<MatchActivityEvent>>(
    id = "match_activity_events",
    ...
) { env ->
    // 评估级缓存：同一评估内多规则树共享，避免重复构建事件列表
    // cacheKey 包含 playEventVersion（MatchState 写入侧）+ graveyardSize（warInfo 墓地侧）
    val graveyard = env.warInfo().getGraveyardCards()
    env.cache("match_activity_events:${env.matchState().playEventVersion()}:${graveyard.size}") {
        buildList {
            addAll(env.matchState().playedEvents())
            addAll(graveyard.map { card ->
                MatchActivityEvent(MatchActivityKind.CARD_GRAVEYARD, card.cardId)
            })
        }
    }
}
```

**cacheKey 设计说明**：

- `playEventVersion`：MatchState 写入侧版本号，打出事件有新写入时递增
- `graveyard.size`：墓地卡牌数量，覆盖"墓地变化但无打出事件"的场景（如对方回合随从死亡入墓）。调用 `.size` 是 O (1)，不构成性能负担
- 不含 `callCardId`：本 Source 不依赖 callCard，跨规则树/跨卡复用

#### S3.2：`WarViewSource`（可选，建议做）

文件：`DefaultDataSources.kt`

当前：

```kotlin
val WarViewSource = dataSource<WarView>(..., memoize = true) { env ->
    env.warInfo().toWarView()
}
```

修改后：

```kotlin
val WarViewSource = dataSource<WarView>(...) { env ->
    // toWarView 构建 SideSnapshot（canHurt filter + taunt filter + sumAtc 计算），有构建成本
    env.cache("war_view") {
        env.warInfo().toWarView()
    }
}
```

**cacheKey 说明**：不拼版本号——`WarView` 在同一评估内不会变化（游戏状态冻结），整个评估用一个 key 即可。如果未来出现"评估中途
warInfo 变化"的场景，再补版本号。

#### S3.3：不启用缓存的 Source（确认清单）

以下 Source 计算成本极低（返回引用或简单字段提取），不启用缓存：

| Source                         | 理由                                                                                   |
|--------------------------------|----------------------------------------------------------------------------------------|
| `MeBoardCardsSource`           | `getPlayCards()` 返回 List 引用，零构建                                                |
| `RivalBoardCardsSource`        | `rivalCardsByPlayArea()` 返回引用                                                      |
| `BoardCardsSource`             | `+` 拼接两个 List，但元素少（双方随从），微秒级                                        |
| `HandCardsSource`              | `getHandCards()` 返回引用                                                              |
| `HandComboCardsSource`         | `handComboCards` 返回预计算缓存引用                                                    |
| `MeComboCardsSource`           | `playComboCards` 返回预计算缓存引用                                                    |
| `MyGraveyardCardsSource`       | `getGraveyardCards()` 返回引用（`MatchActivityEventsSource` 已缓存包含墓地的事件列表） |
| `MyHeroHealthSource`           | `meBlood()` 返回 Int，零成本                                                           |
| `MyManaCrystalSource`          | `resource()` 返回 Int，零成本                                                          |
| `MatchGroupPlayedCountsSource` | `allGroupPlayCounts()` 返回 Map 引用                                                   |
| `MatchTurnCountSource`         | `turnCount()` 返回 Int，零成本                                                         |
| `EvaluatingCardSource`         | 返回 `callCard`，零成本，且依赖 callCard 不可跨卡复用                                  |

> 备注：如果后续 profiling 发现某个 Source 是热点，在那个闭包里加一行 `env.cache(...)` 即可，不需要改基础设施。

#### S3.4：S3 验证

- LSP 检查无错误
- `mvn -pl WeightHanderStrategy compile` 通过

---

### S4：测试

#### S4.1：新增 `EvalCacheTest.kt`

位置：`WeightHanderStrategy/src/test/kotlin/condition/EvalCacheTest.kt`

测试用例：

1. **同一评估内 Source 缓存命中**：同一 `RuleEnv` 实例，`MatchActivityEventsSource` 连续 resolve 两次，第二次命中缓存（用
   spy 验证 `buildList` 只执行一次）
2. **跨规则树复用**：两个独立 `ConditionLogic` 闭包（参数完全相同），共享同一 `RuleEnv`，第二个闭包执行时 Source 命中缓存
3. **playEventVersion 变化后缓存 miss**：`MatchState` 写入新事件后 version 递增，cacheKey 变化，重新计算
4. **graveyardSize 变化后缓存 miss**：mock 不同的 graveyard 列表，cacheKey 变化，重新计算
5. **不同 callCard 共享缓存**：两个 `RuleContext`（不同 callCard），同一 `RuleEnv`，`MatchActivityEventsSource` 第二次命中（不依赖
   callCard）

#### S4.2：更新 `MatchActivityPipelineTest.kt`

- 确认现有 3 个场景测试仍然通过（功能正确性不受影响）
- 测试 fake 的 `ruleEnv` 方法已有 `cache()` 实现（S2.3）

#### S4.3：S4 验证

- `mvn -pl WeightHanderStrategy test` 全跑通
- 重点关注：缓存命中行为是否符合预期

---

### S5：文档同步

- [ ] DECISIONS.md：D-9 标记为 ❌ 废弃，D-11 新增
- [ ] TRACKER.md：T-127 标记为被 T-128 取代，T-128 新增
- [ ] MEMORY.md：替换"正交组件 memoize 缓存方案"段落为"评估级缓存方案"
- [ ] D-002 标记注释更新：去掉 D-9 引用，改为 D-11

---

## 依赖关系图

```
S1 (删除 memoize) ── 纯删除，独立可验证
    ↓
S2 (评估级 Map) ──── 加基础设施，独立可验证
    ↓
S3 (Source 启用) ─── 依赖 S2
    ↓
S4 (测试) ────────── 依赖 S3
    ↓
S5 (文档同步) ────── 最后
```

S1 和 S2 可以分两次提交（S1 纯删除先验证，S2 再加新基础设施），也可以合为一次。

---

## 风险点

### 风险 1：cacheKey 未覆盖 warInfo 变化

- **问题**：`MatchActivityEventsSource` 的 cacheKey 用 `playEventVersion + graveyard.size`。如果 warInfo 的其他部分变化（如手牌变化）但
  playEventVersion 和 graveyard.size 都没变，cacheKey 错误命中
- **实际边界**：评估期间游戏状态冻结，同一评估内不会出问题。跨评估（不同回合）时 playEventVersion 通常会变（有打出事件）
- **残余风险**：对方回合只死随从不打牌 → playEventVersion 不变但墓地变化 → graveyard.size 覆盖了这个场景（size 会变）
- **残余风险 2**：对方回合墓地数量不变但内容变化（一张随从死了，另一张被复活）→ size 不变 → stale。极端边界，暂不处理
- **未来信号**：出现 stale cache 导致错误结果时，扩展 cacheKey 覆盖更多维度

### 风险 2：评估级 Map 的生命周期依赖 RuleEnv

- **问题**：如果 `WarInfoEnv` 不是每次评估新建（而是复用），缓存会跨评估残留
- **当前状态**：`WarInfoEnv` 由 `MyWarManage` 创建，每次评估新建实例，评估结束回收
- **验证方式**：S4 测试用例 3 验证 version 变化后 miss

### 风险 3：cacheKey 拼写错误

- **问题**：组件闭包内手动拼 cacheKey，拼错了会静默错误（与其他 Source 的 key 冲突，或同一个 Source 永远 miss）
- **缓解**：cacheKey 以 `sourceId:` 前缀开头，避免跨 Source 冲突；测试覆盖命中行为
- **与 memoize 对比**：memoize 的 `dependsOnCallCard` 脚枪影响全局且静默；评估级 Map 的 cacheKey 错误只影响单个组件，范围可控

---

## 验收标准

- [ ] S1：memoize 全套删除，编译通过，旧测试全绿，零行为变化
- [ ] S2：评估级 Map 基础设施就位，编译通过，旧测试全绿
- [ ] S3：`MatchActivityEventsSource`（必做）+ `WarViewSource`（可选）启用缓存
- [ ] S4：`EvalCacheTest` 5 个用例全绿，`MatchActivityPipelineTest` 仍全绿
- [ ] S5：DECISIONS.md / TRACKER.md / MEMORY.md 已同步
- [ ] LSP 检查无错误
- [ ] `mvn -pl WeightHanderStrategy test` 全跑通
