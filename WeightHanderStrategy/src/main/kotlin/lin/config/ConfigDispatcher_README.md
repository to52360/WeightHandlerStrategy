# ConfigDispatcher 使用与扩展说明书

## 1. 定位

`ConfigDispatcher` 是配置分发中枢，负责将 **卡牌查询键（findKey）** 映射到 **卡牌权重信息（CardWeightInfo）**，再将 *
*卡牌配置（CardConfig）** 按类型分派给对应的 `ConfigHandler` 处理。

核心流程：

```
BindInfoProvider (SPI)
  └─> BindInfo(findKey, cardConfigs)
        └─> ConfigDispatcher.processUniformList()
              ├─ 1. findKey → WeightInfoFinder → List<CardWeightInfo>  (查绑定)
              └─ 2. cardConfigs → ConfigHandler.processConfig()        (派配置)
```

## 2. 核心组件

### 2.1 ConfigDispatcher

| 成员                                          | 说明                               |
|---------------------------------------------|----------------------------------|
| `handlers: List<ConfigHandler<*>>`          | 配置处理器列表，按 `configType` 索引        |
| `bindInfoFind: List<WeightInfoFinder<*>>`   | 查询器列表，按 `targetType` 索引          |
| `processUniformList(ids, cardConfigs)`      | 主入口：先查绑定，再分发配置                   |
| `processByType<T>(ids, cardConfigs)`        | 优化入口：统一类型时跳过 groupBy/dispatch 分桶 |
| `processMoreConfig(key, vararg cardConfig)` | 便捷扩展函数，单键快捷调用                    |

#### 2.1.1 processByType 优化方法

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

### 2.2 CardConfig（配置标记接口）

所有配置均实现 `CardConfig`，当前子类型体系：

```
CardConfig
  └─ BaseConfig
       ├─ UseConfig          — 使用策略配置 (groupId, order, strategies)
       ├─ CardType           — 卡牌类型标记
       ├─ Rule               — 规则配置
       │    ├─ RuleMap       — 旧意图规则 (Map<RuleLevel, List<IntentRule>>) [已弃用]
       │    ├─ EvaluatorTreeRoot — 新意图规则 (AST 树根节点 EvaluatorInstanceNode)
       │    └─ Rules         — 权重规则 (List<WeightRule>)
       ├─ CardWeightConfigurer — 通用修改器 (函数式接口)
       └─ CardWeightContext<T> — 上下文元数据注入
```

> **RuleMap → EvaluatorTreeRoot 迁移**：`RuleMap` 已弃用，替换为 `EvaluatorTreeRoot`，通过 AST 树结构（
`RuleNode/AndNode/OrNode/NotNode/BranchNode`）表达条件组合。`ConfigHandler` 中新增 `is EvaluatorTreeRoot` 分支，将根节点注入
`CardWeightInfo.intentEvaluatorRoots`。

### 2.3 ConfigHandler

```kotlin
interface ConfigHandler<T : CardConfig> {
    val configType: KClass<out T>
    fun processConfig(cardConfigs: List<T>, cardWeightInfos: List<CardWeightInfo>)
}
```

当前唯一实现：`UseConfigHandler`，内部分发处理 `UseConfig`、`CardType`、`CardWeightConfigurer`、`CardWeightContext`、
`RuleMap`(弃用)、`EvaluatorTreeRoot`、`Rules`。

### 2.4 WeightInfoFinder

```kotlin
interface WeightInfoFinder<T : Any> {
    val targetType: KClass<T>
    fun process(key: T): List<CardWeightInfo>
}
```

按查询键类型查找对应卡牌权重信息。已注册实现：

- `WeightInfoFinder<String>` — 按 cardId 查找
- `WeightInfoFinder<Double>` — 按 groupId 查找
- `WeightInfoFinder<BindingGroupId>` — 按 bindingGroupId 查找（委托 BindingGroupFinder，详见 §2.5）

### 2.5 BindingGroupId 与 BindingGroupFinder

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

### 2.6 BindInfo / BindInfoProvider

```kotlin
data class BindInfo(val findKey: List<Any>, val cardConfigs: List<CardConfig>)

interface BindInfoProvider {
    fun provide(): List<BindInfo>
}
```

通过 SPI 加载，声明「哪些查询键」绑定「哪些配置」。

## 3. 数据流详解

### 3.1 初始化阶段

```
ConfigDispatcher 构造
  │
  ├─ handlerMap = handlers.associateBy { it.configType }
  ├─ bindInfoFindMap = bindInfoFind.associateBy { it.targetType }
  │
  └─ init 块：遍历 SPI 加载的所有 BindInfoProvider
       └─ 对每个 BindInfo 调用 processUniformList(findKey, cardConfigs)
```

### 3.2 processUniformList 流程

```
processUniformList(ids: List<Any>, cardConfigs: List<CardConfig>)
  │
  ├─ 1. 将 ids 按 KClass 分组
  │     ids.groupBy { it::class } → Map<KClass, List<Any>>
  │
  ├─ 2. 按类型查找对应的 WeightInfoFinder
  │     对每组 key 调用 finder.process(key) → List<CardWeightInfo>
  │     合并为 cardWeightInfos
  │     ★ distinctBy { it.cardId } 去重（多个 BindingGroupId 可能映射到相同 cardId）
  │
  └─ 3. 调用 dispatch(cardConfigs, cardWeightInfos)
        │
        ├─ 将 cardConfigs 按 ConfigHandler 支持的 configType 分桶
        └─ 对每个非空桶调用 handler.processConfig(group, cardWeightInfos)
```

### 3.3 processByType 流程（优化路径）

```
processByType<T : Any>(ids: List<T>, cardConfigs: List<CardConfig>)
  │
  ├─ 1. 取 ids.first()::class → 查 finder（跳过 groupBy）
  │
  ├─ 2. flatMap { finder.process(it) } + distinctBy { it.cardId }
  │
  └─ 3. 判断 cardConfigs 类型
        ├─ 全部同类型 → 直接调用 handler.processConfig（跳过 dispatch 分桶）
        └─ 混合类型 → fallback 到 dispatch(cardConfigs, cardWeightInfos)
```

### 3.4 去重机制

多个 `BindingGroupId` 可能映射到相同的 `cardId`（例如不同 CardGroup 包含相同卡牌），导致 `CardWeightInfo` 重复。去重策略：

- **去重位置**：在 `ConfigDispatcher` 层统一执行 `distinctBy { it.cardId }`，而非在 `BindingGroupFinder` 内部
- **原因**：去重是所有 finder 组合的通用关注点，放在 dispatcher 层使所有 finder 受益，且不影响 finder 的单键语义
- **影响范围**：`processUniformList` 和 `processByType` 均在 flatMap 后执行去重

## 4. Koin 注册方式

在 `ModulesLoad.configHandler` 模块中：

```kotlin
val configHandler = module {
    singleOf(::UseConfigHandler) bind ConfigHandler::class

    single<WeightInfoFinder<String>>(named("finderByTypeString")) {
        val infoMap: Map<String, CardWeightInfo> = get(named("weightInfo"))
        findBy { cardId -> listOfNotNull(infoMap[cardId]) }
    }
    single<WeightInfoFinder<Double>>(named("finderByTypeDouble")) {
        val infoMap = get<Map<String, CardWeightInfo>>(named("weightInfo")).values.groupBy { it.groupId }
        findBy { groupId -> infoMap[groupId] ?: emptyList() }
    }
    single<WeightInfoFinder<BindingGroupId>> {
        BindingGroupFinder(get(named("finderByTypeString")))
    }

    // ConfigDispatcher 自动收集所有 ConfigHandler 和 WeightInfoFinder
    single<ConfigDispatcher> { ConfigDispatcher(getAll(), getAll()) }
}
```

## 5. 使用场景示例

### 5.1 添加新的 CardConfig 类型

1. 定义配置类：`data class MyConfig(val value: Int) : BaseConfig`
2. 在 `UseConfigHandler.processConfig` 中添加对应 `when` 分支（或新建 ConfigHandler 实现）
3. 通过 `BindInfoProvider` 或直接调用 `processMoreConfig` 绑定

### 5.2 通过 SPI 注册绑定

```kotlin
class MyBindInfoProvider : BindInfoProvider {
    override fun provide(): List<BindInfo> = listOf(
        BindInfo(listOf("CARD_ID_123"), listOf(UseConfig(useGroupId = 1))),
        BindInfo(listOf(1.0), listOf(Rules(someWeightRule)))
    )
}
// 在 META-INF/services/lin.serviceLoader.provider.BindInfoProvider 中注册
```

### 5.3 直接调用（非 SPI）

```kotlin
val dispatcher: ConfigDispatcher = get()
dispatcher.processMoreConfig("CARD_ID_123", UseConfig(useGroupId = 1))
dispatcher.processMoreConfig(1.0, Rules(someWeightRule))
```

### 5.4 添加新的查询键类型

1. 实现 `WeightInfoFinder<YourKey>`（或使用 value class 包装，如 `BindingGroupId`）
2. 在 Koin 模块中注册
3. `ConfigDispatcher(getAll(), getAll())` 会自动收集

### 5.5 通过 BindingGroupId 绑定意图规则（EvaluatorTreeRoot）

```kotlin
// IntentRuleHandler.bindRule() 中的典型用法
val treeProviders: List<TreeConfigProvider> = ...
val roots = treeProviders.map { it.provide() }

// BindingGroupId 类型的 ids + EvaluatorTreeRoot 类型的 configs
val bindingIds = cardGroups.map { BindingGroupId(it.id) }
val evaluatorRoots = roots.map { EvaluatorTreeRoot(it) }

val dispatcher: ConfigDispatcher = get()
dispatcher.processByType(bindingIds, evaluatorRoots)
// → BindingGroupFinder 解析 bindingId → cardIds → CardWeightInfo
// → distinctBy 去重
// → UseConfigHandler 将 EvaluatorTreeRoot.root 注入每个 CardWeightInfo.intentEvaluatorRoots
```

## 6. 已知问题与演进方向

| 标记               | 说明                                                                                                                 |
|------------------|--------------------------------------------------------------------------------------------------------------------|
| `todo-future`    | 分管配置过渡版，存放位置待迁移                                                                                                    |
| `todo-future`    | `dispatch` 中 `cardWeightInfos` 强耦合，待新需求一起改                                                                         |
| `todo-future`    | 配置暂时放在一起，分组方案待定                                                                                                    |
| `RuleMap` 弃用     | `RuleMap(Map<RuleLevel, List<IntentRule>>)` 是旧意图规则绑定方式，已替换为 `EvaluatorTreeRoot`（AST 树结构）。`IntentRuleHandler` 已完成迁移 |
| 去重               | 多个 BindingGroupId 可能映射到相同 cardId，已在 dispatcher 层通过 `distinctBy { it.cardId }` 统一去重                                 |
| processByType 优化 | 新增 `processByType<T>` 方法，统一类型场景跳过 groupBy 和 dispatch 分桶，`processMoreConfig` 已改为委托此方法                               |
| 内循环 bug          | `dispatch()` 中遍历 `cardConfigs` 时，内层循环对每个 config 都执行全部非空桶的 processConfig，应移到外层                                      |

## 7. 相关文件索引

| 文件                       | 路径                                                                                           |
|--------------------------|----------------------------------------------------------------------------------------------|
| ConfigDispatcher         | `WeightHanderStrategy/src/main/kotlin/lin/config/ConfigDispatcher.kt`                        |
| CardConfig               | `WeightHanderStrategy/src/main/kotlin/lin/config/CardConfig.kt`                              |
| BaseConfig 体系            | `WeightHanderStrategy/src/main/kotlin/lin/config/BaseConfig.kt`                              |
| ConfigHandler            | `WeightHanderStrategy/src/main/kotlin/lin/config/handler/ConfigHandler.kt`                   |
| WeightInfoFinder         | `WeightHanderStrategy/src/main/kotlin/lin/config/find/def/WeightInfoFinder.kt`               |
| BindingGroupId           | `WeightHanderStrategy/src/main/kotlin/lin/rule/tree/BindingGroupId.kt`                       |
| BindingGroupFinder       | `WeightHanderStrategy/src/main/kotlin/lin/config/find/BindingGroupFinder.kt`                 |
| BindingCardIdProvider    | `WeightHanderStrategy/src/main/kotlin/lin/serviceLoader/provider/BindingCardIdProvider.kt`   |
| SpiBindingCardIdProvider | `configUi/.../card_group/service/SpiBindingCardIdProvider.kt`                                |
| BindInfo                 | `WeightHanderStrategy/src/main/kotlin/lin/config/find/def/BindInfo.kt`                       |
| BindInfoProvider         | `WeightHanderStrategy/src/main/kotlin/lin/serviceLoader/provider/BindInfoProvider.kt`        |
| Koin 注册                  | `WeightHanderStrategy/src/main/kotlin/lin/domain/ModulesLoad.kt`                             |
| IntentRuleHandler        | `WeightHanderStrategy/src/main/kotlin/lin/rule/handler/IntentRuleHandler.kt`                 |
| ConditionWeightHandler   | `WeightHanderStrategy/src/main/kotlin/lin/weightHandler/condition/ConditionWeightHandler.kt` |
| RuleInfoRegister         | `WeightHanderStrategy/src/main/kotlin/lin/rule/RuleInfoRegister.kt`                          |
| CardWeightInfo           | `WeightHanderStrategy/src/main/kotlin/lin/.../CardWeightInfo.kt`                             |
| CardBaseExt              | `WeightHanderStrategy/src/main/kotlin/lin/.../CardBaseExt.kt`                                |
