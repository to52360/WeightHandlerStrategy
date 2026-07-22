# T-127 实施计划：D-9 正交组件 memoize 装饰器方案

> 决策依据：[DECISIONS.md D-9](../DECISIONS.md)（memoize 装饰器方案）+ [D-10](../DECISIONS.md)（不做配置层引用，只做运行时缓存）
> 任务追踪：[TRACKER.md T-127](../TRACKER.md)
>
> 本文档为实施前规划，不含具体代码。所有签名变更需先在接口/DSL 层落地，再做组件迁移。

---

## 目标

1. 让正交三组件（DataSource/Transform/Operator）成为纯函数，相同输入相同输出
2. 通过通用 memoize 装饰器统一缓存基础设施，避免每个组件自己写缓存代码
3. 修复 D-002 那个"前面加管道破坏缓存"的洞
4. 实现跨规则树自动复用计算结果（解决用户痛点"多个规则树声明相同 DataSource+Transform+Operator+参数时无法引用统一份"）

---

## 子任务拆分

### T-127a：缓存基础设施（前置）

> 不动现有组件行为，只搭基础设施。可独立编译验证。

#### 步骤 A1：`PipelineCache` 现状盘点

- 当前 `PipelineCache` 已支持 `getOrCompute(key: String, compute: () -> T): T`
- 已实现 `GameLifecycle`，整局开始 `store.clear()`
- **结论：无需扩展接口**，只需约定 cacheKey 字符串格式

#### 步骤 A2：`DataSource` 接口 + DSL 扩展

- `DataSource` 接口加属性：
    - `val memoize: Boolean`（默认 false）
    - `val dependsOnCallCard: Boolean`（默认 false）
- `dataSource(...)` DSL 加同名参数：
    - `memoize: Boolean = false`
    - `dependsOnCallCard: Boolean = false`
- 加扩展函数 `fun <T : Any> DataSource<T>.memoized(): DataSource<T>`：
    - 返回一个包装类 `MemoizedDataSource`，内部委托原 DataSource
    - `resolve(context, env)` 时：
        - 拼 cacheKey：
          `if (dependsOnCallCard) "src:$id:${env.version()}:${context.callCard.cardId()}" else "src:$id:${env.version()}"`
        - 调 `env.pipelineCache().getOrCompute(cacheKey) { delegate.resolve(context, env) }`
    - 其他属性（id/name/description/categories/outputType）透传

#### 步骤 A3：`Transform` 接口 + DSL 扩展

- `Transform` 接口加属性：`val memoize: Boolean`（默认 false）
- `transform(...)` DSL（两个 overload）加 `memoize: Boolean = false` 参数
- 加扩展函数 `fun <I : Any, O : Any> Transform<I, O>.memoized(): Transform<I, O>`：
    - 返回包装类 `MemoizedTransform`，内部委托原 Transform
    - `transform(input, context, env, args)` 时：
        - 拼 cacheKey：`tf:$id:${args.hashCode()}:${System.identityHashCode(input)}`
        - 调 `env.pipelineCache().getOrCompute(cacheKey) { delegate.transform(input, context, env, args) }`
    - 其他属性透传

#### 步骤 A4：`env.version()` 抽象

- 当前 `MatchState.playEventVersion()` 只反映 MatchState 写入侧
- 未来 warInfo（手牌/战场/血量）变化时 version 不会更新
- **本期决策**：暂不抽象 `RuleEnv.version()`，cacheKey 直接拼 `env.matchState().playEventVersion()`
- **风险记录**：如果未来出现"管道只用 warInfo 不用 MatchState"的场景，cacheKey 不会因 warInfo 变化失效，需要扩展
  `RuleEnv.version()` 抽象（关联挂起新任务）
- **备选**：直接在 `MemoizedDataSource` 内部硬编码 `env.matchState().playEventVersion()`，等真有 warInfo-only 管道再抽象

#### 步骤 A5：`PipelineAssembler` 装配改造

- `assemble(ref)` 内部，对 `source` 和每个 `transform` 检查 `memoize` 字段
- 若为 true，包装成 `memoized()` 后再装入闭包
- 闭包内调用流不变：`source.resolve(...)` → `transform.transform(...)` → `operator.evaluate(...)`

#### 步骤 A6：T-127a 验证

- LSP 检查无错误
- `mvn -pl WeightHanderStrategy compile` 通过
- 旧测试全跑通（基础设施加默认 false，行为零变化）

---

### T-127b：现有组件迁移 + D-002 标记修正 + 测试

> 依赖 T-127a 完成。

#### 步骤 B1：现有 Source 标注 `memoize` 和 `dependsOnCallCard`

| Source                         | memoize          | dependsOnCallCard | 理由                                                     |
|--------------------------------|------------------|-------------------|----------------------------------------------------------|
| `WarViewSource`                | ✅ true          | ❌ false          | 只读 `env.warInfo().toWarView()`，跨规则树可复用         |
| `MeBoardCardsSource`           | ✅ true          | ❌ false          | 只读 `env.warInfo().getPlayCards()`                      |
| `RivalBoardCardsSource`        | ✅ true          | ❌ false          | 只读 `env.warInfo().rivalCardsByPlayArea()`              |
| `BoardCardsSource`             | ✅ true          | ❌ false          | 同上                                                     |
| `HandCardsSource`              | ✅ true          | ❌ false          | 只读 `env.warInfo().getHandCards()`                      |
| `HandComboCardsSource`         | ✅ true          | ❌ false          | 只读 `env.warInfo().handComboCards`                      |
| `MeComboCardsSource`           | ✅ true          | ❌ false          | 只读 `env.warInfo().playComboCards`                      |
| `MyGraveyardCardsSource`       | ✅ true          | ❌ false          | 只读 `env.warInfo().getGraveyardCards()`                 |
| `MyHeroHealthSource`           | ✅ true          | ❌ false          | 只读 `env.warInfo().meBlood()`                           |
| `MyManaCrystalSource`          | ✅ true          | ❌ false          | 只读 `env.warInfo().resource()`                          |
| `MatchGroupPlayedCountsSource` | ✅ true          | ❌ false          | 只读 `env.matchState().allGroupPlayCounts()`             |
| `MatchActivityEventsSource`    | ✅ true          | ❌ false          | 读 MatchState + warInfo.getGraveyardCards，不读 callCard |
| `MatchTurnCountSource`         | ✅ true          | ❌ false          | 只读 `env.matchState().turnCount()`                      |
| `EvaluatingCardSource`         | ❌ false（默认） | ✅ true           | 输出 `callCard`，每张卡不同，不可跨规则树复用            |

#### 步骤 B2：常用 Transform 标注 `memoize = true`

| Transform                      | memoize          | 理由                                      |
|--------------------------------|------------------|-------------------------------------------|
| `RaceFilterTransform`          | ✅ true          | filter 计算，跨规则树复用有价值           |
| `CardTypeFilterTransform`      | ✅ true          | 同上                                      |
| `GroupFilterTransform`         | ✅ true          | 同上                                      |
| `PurposeFilterTransform`       | ✅ true          | 同上                                      |
| `CountProjectionTransform`     | ✅ true          | 聚合计算                                  |
| `SumAttackTransform`           | ✅ true          | 聚合计算                                  |
| `SumHealthTransform`           | ✅ true          | 聚合计算                                  |
| `EvaluatingCardCostTransform`  | ❌ false（默认） | 输入是 `callCard`，每张卡不同，缓存命中低 |
| `TauntFilterTransform`         | ✅ true          | filter                                    |
| `ExcessDamageTransform`        | ❌ false（默认） | 简单字段提取，几乎零成本，memoize 收益小  |
| `AcceptableAttackTransform`    | ❌ false（默认） | 同上                                      |
| `RivalCardsFromViewTransform`  | ❌ false（默认） | 同上                                      |
| `MeCardsFromViewTransform`     | ❌ false（默认） | 同上                                      |
| `ToCardsTransform`             | ✅ true          | map 创建新 List，有成本                   |
| `PickGroupCountTransform`      | ✅ true          | Map 查找，跨规则树复用有价值              |
| `WeightedActivitySumTransform` | ✅ true          | 聚合求和（重构后，见 B3）                 |

> 备注：简单字段提取（如 `input.excessDamage`）memoize 反而增加 map 存取成本，默认 false。具体哪些开/不开，可在 code review
> 阶段微调。

#### 步骤 B3：`WeightedActivitySumTransform` 重构（删内嵌缓存）

- 删除 `DefaultTransforms.kt:269-298` 的 `// @defect D-002 begin/end` 整段 class 定义
- 改回 DSL val 形式（参考其他 Transform 的写法）：
    - 闭包体只做求和逻辑：`input.sumOf { event -> ... }`
    - 删除 `env.matchState().playEventVersion()` 和 `env.pipelineCache().getOrCompute(cacheKey) { ... }` 整段
    - 加 `memoize = true` 参数，由 `memoized()` 装饰器统一接管缓存
- 闭包体保留事件匹配判定（`when (event.kind) { CARD_PLAYED -> ...; CARD_GRAVEYARD -> ... }`）—— 这部分归 D-002 后续重构（拆
  played/graveyard 两个独立 Source），本期不动
- 注释更新：去掉 `@defect D-002` 标记（缓存管理已归 D-9），保留"事件匹配判定职责待 D-002 拆分"的提示

#### 步骤 B4：`@defect D-002` 标记位置修正

- `DefaultTransforms.kt:258-269`（`WeightedActivitySumTransform` 注释 + `@defect D-002 begin`）：
    - 移除 `@defect D-002 begin/end` 标记
    - 注释改为："`WeightedActivitySumTransform` 当前混合求和（Transform 本职）+ 事件匹配判定（应归 Operator）。缓存职责已归
      D-9 memoize 装饰器。事件匹配拆分归 D-002 后续任务（@defect D-002 在 MatchActivityEventsSource 上标记）"
- `DefaultDataSources.kt:171-174`（`MatchActivityEventsSource` 注释）：
    - 加 `@defect D-002` 主标记
    - 注释改为："对局活动事件数据源（@defect D-002，DataSource 职责混合：played+graveyard 合并输出）。多数据源重构时应拆为
      played_activity_events / graveyard_activity_events 两个独立 DataSource。下游 `WeightedActivitySumTransform` 因此不得不用
      when 分派事件类型。缓存职责已归 D-9 memoize 装饰器统一接管（详见 DECISIONS.md D-9）。"

#### 步骤 B5：测试

新增 `MemoizeCacheTest.kt`（位置：`WeightHanderStrategy/src/test/kotlin/condition/MemoizeCacheTest.kt`）：

1. **Source 单组件命中**：同一 Source 连续调用两次，第二次直接命中（用 FakeRuleEnv + spy 验证 resolve 只调一次）
2. **Source + Transform 链式级联命中**：同一管道链连续执行两次，Source 和 Transform 都第二次命中
3. **前面加管道改 input 引用后 cacheKey miss**：`Source → FilterTransform → Aggregator`，FilterTransform 改变
   input，Aggregator 第二次 miss（验证 D-002 那个洞已修复）
4. **dependsOnCallCard=true 跨 callCard 不命中**：两个 RuleContext（不同 callCard），Source 各自计算
5. **dependsOnCallCard=false 跨 callCard 命中**：两个 RuleContext（不同 callCard），Source 第二次命中（解决跨规则树复用）
6. **GameLifecycle.start 清空缓存**：调 `pipelineCache.start()` 后所有 cacheKey miss
7. **跨规则树复用端到端**：模拟两个独立 ConditionLogic 闭包（参数完全相同），第二个执行几乎零成本（用 spy 验证 delegate 调用次数）

同步修改 `MatchActivityPipelineTest.kt`：

- 去除对 `WeightedActivitySumTransform` 内嵌缓存的依赖
- 改为通过 `memoize = true` 装饰器统一缓存

#### 步骤 B6：T-127b 验证

- LSP 检查无错误
- `mvn -pl WeightHanderStrategy test` 全跑通
- 重点关注：缓存命中行为是否符合预期（看 spy 调用次数）

---

## 依赖关系图

```
A1 (PipelineCache 盘点) ── 无依赖，立即开始
        ↓
A2 (DataSource 接口) ──┐
A3 (Transform 接口)   ──┤  A2/A3 可并行
        ↓               ↓
A4 (env.version 抽象) ─ 本期不抽象，硬编码 playEventVersion
        ↓
A5 (PipelineAssembler 装配)
        ↓
A6 (T-127a 验证) ────── 基础设施完成，可独立提交
        ↓
B1 (Source 标注) ──┐
B2 (Transform 标注)──┤  B1/B2 可并行
        ↓           ↓
B3 (WeightedActivitySumTransform 重构)
        ↓
B4 (D-002 标记修正) ── 与 B3 同提交
        ↓
B5 (测试) ────────── 与 B3/B4 同提交
        ↓
B6 (T-127b 验证)
```

---

## 风险点

### 风险 1：`identityHashCode` 的 GC 风险

- **理论问题**：input 被 GC 后，`identityHashCode` 可能被新对象重用，导致错误命中
- **实际边界**：同一对局内 input 引用在管道执行栈上不会被 GC；整局结束 `PipelineCache.start()` 清空
- **当前结论**：边界 case 不会触发，无需特殊处理
- **未来信号**：若引入跨局缓存或长生命周期缓存，需重新审视（关联挂起新任务）

### 风险 2：`dependsOnCallCard` 默认 false 的隐式契约

- **问题**：依赖 callCard 的 Source 忘记声明 `dependsOnCallCard=true`，会导致 cacheKey 错误（跨 callCard 复用了不该复用的缓存，产生错误结果）
- **缓解措施**：
    1. 测试覆盖（B5 测试用例 4）
    2. Code review 清单：每个新 Source 必须明确标注 `dependsOnCallCard`，不依赖默认值
    3. 可选：DSL 强制要求显式声明 `dependsOnCallCard`（不带默认值），但这会破坏现有 Source 兼容性
- **当前决策**：保持默认 false，靠测试 + review 把关

### 风险 3：同一对局多卡评估的缓存膨胀

- **问题**：`dependsOnCallCard=true` 的 Source（如 `EvaluatingCardSource`）每个 callCard 一份缓存条目，N 张卡评估可能 N 倍膨胀
- **缓解**：
    - `EvaluatingCardSource` 默认不 memoize（已在 B1 标注），避免膨胀
    - `PipelineCache` 整局清空，单局内膨胀可控
    - 未来如有性能问题，可加 LRU 淘汰策略（关联挂起新任务）

### 风险 4：`env.version()` 暂未抽象

- **问题**：当前 cacheKey 用 `env.matchState().playEventVersion()`，只覆盖 MatchState 写入侧
- **场景**：如果未来出现"管道只用 warInfo 不用 MatchState"的场景，warInfo 变化时 version 不会更新，cacheKey 错误命中
- **当前缓解**：本期所有 Source 要么读 MatchState（version 覆盖），要么读 warInfo 但 warInfo 变化时 MatchState
  也通常变化（如同回合打出牌，warInfo 和 MatchState 都变）
- **未来信号**：出现"只读 warInfo 不读 MatchState 的 Source" + "warInfo 变化但 MatchState 不变"场景时，扩展
  `RuleEnv.version()` 抽象

---

## 后续衔接任务（不在 T-127 范围）

1. **Transform 移除 env/context 签名清理**（独立任务）：T-127 完成后，所有 Transform 再无 env 依赖（
   `WeightedActivitySumTransform` 删内嵌缓存后即可），可一刀切 Transform 签名为 `transform(input, args): Out`
2. **D-002 完整重构**（挂起任务）：拆 `MatchActivityEventsSource` 为 played/graveyard 两个独立 Source + 独立匹配 Operator
3. **`RuleEnv.version()` 抽象**（按需）：等 warInfo-only 管道出现

---

## 验收标准

- [ ] T-127a：基础设施编译通过，旧测试全绿，零行为变化
- [ ] T-127b：现有组件迁移完成，`WeightedActivitySumTransform` 无内嵌缓存，`@defect D-002` 标记位置修正
- [ ] `MemoizeCacheTest` 7 个用例全绿
- [ ] LSP 检查无错误
- [ ] `mvn -pl WeightHanderStrategy test` 全跑通
- [ ] DECISIONS.md / TRACKER.md / MEMORY.md 已同步
