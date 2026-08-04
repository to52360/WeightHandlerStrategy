# §2 核心组件详解

## 2.1 ConfigDispatcher

| 成员                                          | 说明                               |
|---------------------------------------------|----------------------------------|
| `handlers: List<ConfigHandler<*>>`          | 配置处理器列表，按 `configType` 索引        |
| `bindInfoFind: List<WeightInfoFinder<*>>`   | 查询器列表，按 `targetType` 索引          |
| `processUniformList(ids, cardConfigs)`      | 主入口：先查绑定，再分发配置                   |
| `processByType<T>(ids, cardConfigs)`        | 优化入口：统一类型时跳过 groupBy/dispatch 分桶 |
| `processMoreConfig(key, vararg cardConfig)` | 便捷扩展函数，单键快捷调用                    |

### processByType 优化方法

大多数调用场景下，`ids` 为统一类型、`cardConfigs` 也为统一类型，此时无需 `groupBy` 和 `dispatch` 分桶。`processByType<T>`
专门处理此场景：

```kotlin
fun <T : Any> processByType(ids: List<T>, cardConfigs: List<CardConfig>) {
    // 1. 直接取首个 id 的 KClass，查 finder（跳过 groupBy）
    // 2. flatMap + distinctBy 去重
    // 3. 若 cardConfigs 全部同类型 → 直接调用对应 handler（跳过 dispatch 分桶）
    // 4. 否则 fallback 到 dispatch(cardConfigs, cardWeightInfos)
}
```

| 对比         | `processUniformList`  | `processByType<T>`        |
|------------|-----------------------|---------------------------|
| ids 类型     | 可混搭，需 groupBy         | 必须统一，直接取 first()::class   |
| configs 分派 | 走 dispatch 分桶         | 同类型直达 handler，否则 fallback |
| 适用场景       | SPI 初始化，ids 来源不确定     | 业务代码明确类型时                 |
| 去重         | distinctBy { cardId } | distinctBy { cardId }     |

---

## 2.2 CardConfig（配置标记接口）

所有配置均实现 `CardConfig`，当前子类型体系：

```
CardConfig
  └─ BaseConfig
       ├─ CardAttributeConfig — 直接修改 CardWeightInfo 属性/上下文的配置
       │    ├─ UseConfig          — 使用策略配置 (groupId, order, strategies)
       │    ├─ CardType           — 卡牌类型标记
       │    ├─ CardWeightConfigurer — 通用修改器 (函数式接口)
       │    └─ CardWeightContext<T> — 上下文元数据注入
       └─ Rule               — 规则配置
            ├─ RuleMap       — 旧意图规则 (Map<RuleLevel, List<IntentRule>>) [已弃用]
            ├─ EvaluatorTreeRoot — 新意图规则 (AST 树根节点 EvaluatorInstanceNode)
            └─ Rules         — 权重规则 (List<WeightRule>)
```

> **RuleMap → EvaluatorTreeRoot 迁移**：`RuleMap` 已弃用，替换为 `EvaluatorTreeRoot`，通过 AST 树结构（
> `RuleNode/AndNode/OrNode/BranchNode`）表达条件组合（评估树不支持 NOT，取反仅用于条件树）。`ConfigHandler` 中新增
> `is EvaluatorTreeRoot` 分支，将根节点注入
> `CardWeightInfo.intentEvaluatorRoots`。

---

## 2.3 ConfigHandler

```kotlin
interface ConfigHandler<T : CardConfig> {
    val configType: KClass<out T>
    fun processConfig(cardConfigs: List<T>, cardWeightInfos: List<CardWeightInfo>)
}
```

当前实现按配置边界拆分：

- `UseConfigHandler : ConfigHandler<CardAttributeConfig>`：处理 `UseConfig`、`CardType`、`CardWeightConfigurer`、
  `CardWeightContext`，用于直接修改 `CardWeightInfo` 属性或上下文。
- `RuleConfigHandler : ConfigHandler<Rule>`：处理 `RuleMap`(弃用)、`EvaluatorTreeRoot`、`Rules`，用于注入意图规则/权重规则。

拆分原因：`ConfigDispatcher` 按 `configType` 分桶，若多个 handler 都挂 `BaseConfig` 会导致边界过宽或注册覆盖；因此新增
`CardAttributeConfig` 作为非规则配置的中间边界。

---

## 2.4 WeightInfoFinder

```kotlin
interface WeightInfoFinder<T : Any> {
    val targetType: KClass<T>
    fun process(key: T): List<CardWeightInfo>
}
```

按查询键类型查找对应卡牌权重信息。已注册实现：

- `WeightInfoFinder<String>` — 按 cardId 查找
- `WeightInfoFinder<Double>` — 按 groupId 查找
- `WeightInfoFinder<BindingGroupId>` — 按 bindingGroupId 查找（委托 BindingGroupFinder）

---

## 2.5 BindingGroupId 与 BindingGroupFinder

为了在 ConfigDispatcher 中类型安全地区分 bindingId 与普通 String(cardId)，引入 value class：

```kotlin
@JvmInline
value class BindingGroupId(val value: String)
```

`BindingGroupFinder` 实现 `WeightInfoFinder<BindingGroupId>`，流程：

```
BindingGroupFinder.process(BindingGroupId)
  │
  ├─ 1. SPI 加载所有 BindingCardIdProvider → 构建 bindingIndex: Map<bindingId, List<cardId>>
  │
  └─ 2. 从 bindingIndex 查出 cardIds → 委托 cardIdFinder.process(cardId) 逐一查找
        → 返回 List<CardWeightInfo>
```

| 组件                         | 说明                                                      |
|----------------------------|---------------------------------------------------------|
| `BindingGroupId`           | value class，类型安全的 bindingId 键                           |
| `BindingGroupFinder`       | finder 实现，SPI 懒加载 bindingId→cardIds 索引，委托 String finder |
| `BindingCardIdProvider`    | SPI 接口，提供 `Map<String, List<String>>`                   |
| `SpiBindingCardIdProvider` | SPI 实现，从 `CardGroupService` 读取启用的 CardGroup             |

SPI 注册文件：`META-INF/services/lin.serviceLoader.provider.BindingCardIdProvider`

---

## 2.6 BindInfo / BindInfoProvider

```kotlin
data class BindInfo(val findKey: List<Any>, val cardConfigs: List<CardConfig>)

interface BindInfoProvider {
    fun provide(): List<BindInfo>
}
```

通过 SPI 加载，声明「哪些查询键」绑定「哪些配置」。
