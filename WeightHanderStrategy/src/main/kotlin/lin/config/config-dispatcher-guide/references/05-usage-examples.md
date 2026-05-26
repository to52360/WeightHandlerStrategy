# §5 使用场景示例

## 5.1 添加新的 CardConfig 类型

1. 先判断配置边界：直接修改 `CardWeightInfo` 属性/上下文时实现 `CardAttributeConfig`；规则类配置实现 `Rule`。
2. 属性类配置：在 `UseConfigHandler.processConfig` 中添加对应 `when` 分支，或在必要时新增更窄的 handler。
3. 规则类配置：在 `RuleConfigHandler.processConfig` 中添加对应 `when` 分支。
4. 通过 `BindInfoProvider` 或直接调用 `processMoreConfig` 绑定

---

## 5.2 通过 SPI 注册绑定

```kotlin
class MyBindInfoProvider : BindInfoProvider {
    override fun provide(): List<BindInfo> = listOf(
        BindInfo(listOf("CARD_ID_123"), listOf(UseConfig(useGroupId = 1))),
        BindInfo(listOf(1.0), listOf(Rules(someWeightRule)))
    )
}
// 在 META-INF/services/lin.serviceLoader.provider.BindInfoProvider 中注册
```

---

## 5.3 直接调用（非 SPI）

```kotlin
val dispatcher: ConfigDispatcher = get()
dispatcher.processMoreConfig("CARD_ID_123", UseConfig(useGroupId = 1))
dispatcher.processMoreConfig(1.0, Rules(someWeightRule))
```

---

## 5.4 添加新的查询键类型

1. 实现 `WeightInfoFinder<YourKey>`（或使用 value class 包装，如 `BindingGroupId`）
2. 在 Koin 模块中注册
3. `ConfigDispatcher(getAll(), getAll())` 会自动收集

---

## 5.5 通过 BindingGroupId 绑定意图规则（EvaluatorTreeRoot）

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
// → RuleConfigHandler 将 EvaluatorTreeRoot.root 注入每个 CardWeightInfo.intentEvaluatorRoots
```
